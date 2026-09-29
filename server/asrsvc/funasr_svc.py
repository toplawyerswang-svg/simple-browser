#!/usr/bin/env python3
# FunASR 本地转写服务（零刻 SER8）：asrsvc 长音频（>3h）分流引擎
# 只绑 tailscale IP，供 ECS asrsvc 经 tailnet 调用；CPU 推理串行化
import json
import subprocess
import tempfile
import threading
import time
from pathlib import Path

from flask import Flask, jsonify, request
from funasr import AutoModel

app = Flask(__name__)
MODEL_LOCK = threading.Lock()  # 单模型实例，推理串行

print("loading FunASR models (paraformer-zh + fsmn-vad + ct-punc + cam++)...", flush=True)
_t0 = time.time()
model = AutoModel(
    model="paraformer-zh",
    vad_model="fsmn-vad",
    vad_kwargs={"max_single_segment_time": 30000},
    spk_model="cam++",
    punc_model="ct-punc",
    device="cpu",
    disable_update=True,
)
print("models ready in %.1fs" % (time.time() - _t0), flush=True)


def fmt_ms(ms):
    s = int(round((ms or 0) / 1000.0))
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    return "%02d:%02d:%02d" % (h, m, sec) if h else "%02d:%02d" % (m, sec)


def merge_to_segments(info):
    """与生产 build_segments 同规则：同说话人间隔<=1.5s合并；输出字段与百炼通道同构。"""
    segs = []
    for s in info:
        spk = int(s.get("spk", 0) or 0)
        if segs and segs[-1]["speaker"] == spk and s["start"] - segs[-1]["end_ms"] <= 1500:
            segs[-1]["text"] += s["text"]
            segs[-1]["end_ms"] = s["end"]
        else:
            segs.append({"speaker": spk, "begin_ms": s["start"],
                         "end_ms": s["end"], "text": s["text"]})
    for g in segs:
        g["begin"] = fmt_ms(g["begin_ms"])
        g["end"] = fmt_ms(g["end_ms"])
    return segs


@app.get("/healthz")
def healthz():
    return "ok"


@app.post("/transcribe")
def transcribe():
    f = request.files.get("file")
    if f is None:
        return jsonify({"error": "no file"}), 400
    with_spk = request.form.get("diarize", "1") == "1"
    with tempfile.TemporaryDirectory() as td:
        src = Path(td) / (f.filename or "audio")
        f.save(src)
        wav = Path(td) / "a16k.wav"
        try:
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", str(src),
                 "-ac", "1", "-ar", "16000", str(wav)],
                check=True, capture_output=True, timeout=7200)
        except subprocess.CalledProcessError as e:
            return jsonify({"error": "ffmpeg failed: " +
                            e.stderr.decode(errors="ignore")[-200:]}), 500
        except subprocess.TimeoutExpired:
            return jsonify({"error": "ffmpeg timeout"}), 500
        t0 = time.time()
        with MODEL_LOCK:  # CPU 推理串行，排队处理
            res = model.generate(input=str(wav), batch_size_s=300, hotword="")
        asr_s = round(time.time() - t0, 1)
        r0 = res[0]
        info = r0.get("sentence_info") or []
        if info:
            segments = merge_to_segments(info)
            if not with_spk:  # 不需要分离时统一归到0号，保持结构一致
                for g in segments:
                    g["speaker"] = 0
        else:
            txt = r0.get("text", "")
            segments = [{"speaker": 0, "begin_ms": 0, "end_ms": 0, "text": txt,
                         "begin": "00:00", "end": "00:00"}]
        duration_ms = max((g["end_ms"] for g in segments), default=0)
        return jsonify({"duration_ms": duration_ms, "segments": segments,
                        "engine": "funasr-local", "asr_s": asr_s})


if __name__ == "__main__":
    CFG = json.loads((Path(__file__).parent / "config.json").read_text(encoding="utf-8"))
    app.run(host=CFG.get("host", "100.100.19.0"), port=int(CFG.get("port", 8740)),
            threaded=True)
