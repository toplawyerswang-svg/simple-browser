#!/usr/bin/env python3
# FunASR 本地转写服务（零刻 SER8）：asrsvc 长音频（>3h）分流引擎
# v3：说话人标签「中心点归并」——funasr 批内聚类可靠（17.7min 样本跨4批次收敛为[0,1]），
#     但标签逐批累加偏移，长音频会裂出几百个标签（3.55h 实测 417 个）。
#     修法：每个 funasr 标签取其句子 ERes2NetV2 嵌入的时长加权质心，
#     在质心层面全局层次聚类归并（质心相似度远比单句干净），短句/无嵌入句就近归簇。
# 只绑 tailscale IP，供 ECS asrsvc 经 tailnet 调用；CPU 推理串行化
import json
import subprocess
import tempfile
import threading
import time
import wave
from pathlib import Path

import numpy as np
from flask import Flask, jsonify, request
from funasr import AutoModel
from sherpa_onnx import SpeakerEmbeddingExtractor, SpeakerEmbeddingExtractorConfig
from sklearn.cluster import AgglomerativeClustering

app = Flask(__name__)
MODEL_LOCK = threading.Lock()  # 单模型实例，推理串行

CFG = json.loads((Path(__file__).parent / "config.json").read_text(encoding="utf-8"))
SPK_MODEL_PATH = Path(__file__).parent / CFG.get(
    "spk_model", "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx")
# 质心归并阈值（余弦距离）：同录音同人质心 ~0.1-0.3，异人质心 ~0.4-0.7
SPK_CENTROID_DIST = float(CFG.get("spk_centroid_dist", 0.45))
MIN_EMB_SEC = float(CFG.get("spk_min_emb_sec", 0.30))  # 短于此的句子不单独提嵌入

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

_embedder = None
_embedder_lock = threading.Lock()


def get_embedder():
    global _embedder
    with _embedder_lock:
        if _embedder is None:
            cfg = SpeakerEmbeddingExtractorConfig(model=str(SPK_MODEL_PATH))
            _embedder = SpeakerEmbeddingExtractor(cfg)
    return _embedder


def load_wav16k(path):
    with wave.open(str(path), "rb") as w:
        if w.getframerate() != 16000 or w.getnchannels() != 1:
            raise RuntimeError("wav 非 16k 单声道")
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0


def embed_sentence(ext, wav_data, s):
    """单句 -> ERes2NetV2 嵌入（生产同款 API：create_stream→accept_waveform→compute）。"""
    a = int(s["start"] / 1000 * 16000)
    b = int(s["end"] / 1000 * 16000)
    seg = wav_data[a:b]
    if len(seg) < 1600:
        return None
    st = ext.create_stream()
    st.accept_waveform(16000, [float(x) for x in seg])
    st.input_finished()
    e = np.asarray(ext.compute(st), dtype=np.float32).ravel()
    if e.size == 0 or not np.isfinite(e).all():
        return None
    return e


def reconcile_speakers(info, wav_data):
    """funasr 标签 -> 全局标签（质心归并）。返回与 info 对齐的连续 spk 列表。"""
    ext = get_embedder()
    # 1. 每句提嵌入（失败句跳过）
    embs = {}  # sentence_index -> vec
    for i, s in enumerate(info):
        if (s["end"] - s["start"]) >= MIN_EMB_SEC * 1000:
            try:
                e = embed_sentence(ext, wav_data, s)
                if e is not None:
                    embs[i] = e
            except Exception as ex:
                print("embed failed at", i, repr(ex), flush=True)
    n_emb = len(embs)
    batch_labels = [int(s.get("spk", 0) or 0) for s in info]

    # 2. 按 funasr 标签分组算时长加权质心
    by_label = {}
    for i, e in embs.items():
        dur = (info[i]["end"] - info[i]["start"]) / 1000.0
        by_label.setdefault(batch_labels[i], []).append((e, dur))
    lab_ids = sorted(by_label)
    cents = []
    for lb in lab_ids:
        items = by_label[lb]
        w = np.array([d for _, d in items], dtype=np.float32)
        E = np.stack([e for e, _ in items])
        c = (E * w[:, None]).sum(axis=0) / w.sum()
        cents.append(c / (np.linalg.norm(c) + 1e-9))
    C = np.stack(cents)

    # 3. 质心层面全局归并
    if len(lab_ids) >= 2:
        merged = AgglomerativeClustering(
            n_clusters=None, metric="cosine", linkage="average",
            distance_threshold=SPK_CENTROID_DIST).fit_predict(C)
    else:
        merged = np.zeros(len(lab_ids), dtype=int)
    label_map = {lb: int(m) for lb, m in zip(lab_ids, merged)}

    # 4. 句级标签 = 所属 funasr 标签的全局标签；无嵌入句沿用时间最近句
    out = [None] * len(info)
    for i in embs:
        out[i] = label_map[batch_labels[i]]
    last = 0
    for i in range(len(info)):
        if out[i] is None:
            out[i] = last
        else:
            last = out[i]
    remap = {}
    return [remap.setdefault(v, len(remap)) for v in out], len(lab_ids), len(set(merged))


def fmt_ms(ms):
    s = int(round((ms or 0) / 1000.0))
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    return "%02d:%02d:%02d" % (h, m, sec) if h else "%02d:%02d" % (m, sec)


def merge_to_segments(info, spk_list):
    """与生产 build_segments 同规则：同说话人间隔<=1.5s合并；输出字段与百炼通道同构。"""
    segs = []
    for s, spk in zip(info, spk_list):
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
            spk_list = [int(s.get("spk", 0) or 0) for s in info]
            n_raw = n_fin = len(set(spk_list))
            if with_spk and len(info) >= 2:
                try:  # 全局重聚类：修掉 funasr 按批次聚类的标签爆炸
                    wav_data = load_wav16k(wav)
                    spk_list, n_raw, n_fin = reconcile_speakers(info, wav_data)
                except Exception as ex:
                    print("global reconciliation failed, keep batch labels:", repr(ex), flush=True)
            elif not with_spk:
                spk_list = [0] * len(info)
                n_raw = n_fin = 1
            segments = merge_to_segments(info, spk_list)
        else:
            txt = r0.get("text", "")
            segments = [{"speaker": 0, "begin_ms": 0, "end_ms": 0, "text": txt,
                         "begin": "00:00", "end": "00:00"}]
            n_raw = n_fin = 1
        duration_ms = max((g["end_ms"] for g in segments), default=0)
        print("done: asr_s=%s segs=%d labels %d->%d" % (asr_s, len(segments), n_raw, n_fin),
              flush=True)
        return jsonify({"duration_ms": duration_ms, "segments": segments,
                        "engine": "funasr-local", "asr_s": asr_s,
                        "spk_labels_raw": n_raw, "spk_labels_final": n_fin})


if __name__ == "__main__":
    app.run(host=CFG.get("host", "100.100.19.0"), port=int(CFG.get("port", 8740)),
            threaded=True)
