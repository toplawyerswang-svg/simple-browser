#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""轻舟浏览器录音转写服务 (asrsvc)

流程：轻舟浏览器上传音频 -> 本服务转存百炼临时OSS -> 提交 Fun-ASR 录音文件识别
（异步任务，轮询） -> 拉取识别JSON -> 按说话人合并分段 -> 可选 qwen LLM 将方言/口语
改写为标准普通话 -> APP 轮询取结果。

声纹：转写完成后（音频删除前）为每个说话人提取声纹向量（CAM++/ERes2Net, sherpa-onnx
CPU 推理）与基频性别判定；与声纹库比对自动标注人名（高置信直接标，中置信加"？"）。
用户在结果页标注后，声纹样本归档到对应人名下，越用越准。

分享：对已完成的任务可生成带提取码的分享链接（/s/<share_id>?pwd=XXXX），
链接 30 天有效；查看页无需 Token，但必须携带正确提取码。

安全：业务接口要求 X-LZ-Token 头；本地音频在任务结束（成功或失败）后立即删除；
百炼临时 OSS 为私有 ACL，48 小时自动清理。声纹向量仅存本机。
"""
import hmac
import html
import json
import math
import os
import re
import secrets
import subprocess
import tempfile
import threading
import time
import uuid
import zipfile
from pathlib import Path, PurePosixPath

import numpy as np
import requests
from flask import Response, Flask, jsonify, request, send_file

BASE = Path(__file__).resolve().parent
CFG = json.loads((BASE / "config.json").read_text(encoding="utf-8"))
DATA = BASE / "data"
DATA.mkdir(exist_ok=True)
VP_DIR = BASE / "voiceprints"
VP_DIR.mkdir(exist_ok=True)
MODEL_PATH = BASE / "models" / CFG.get("speaker_model", "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx")

TOKEN = CFG["token"]  # 旧版全局令牌，已停用；现按 users.json 内多用户令牌鉴权
USERS_FILE = BASE / "users.json"
AUTH_WINDOW_SECONDS = 900     # 失败计数窗口15分钟
AUTH_MAX_FAILS = 8            # 窗口内失败超过该次数即临时封禁该IP
ASR_MODEL = CFG.get("asr_model", "fun-asr")
LLM_MODEL = CFG.get("llm_model", "qwen-flash")
MAAS = CFG["maas_host"].rstrip("/")
DASH = CFG.get("dashscope_host", "https://dashscope.aliyuncs.com").rstrip("/")
API_KEY = CFG["api_key"]
DS_BASE = CFG.get("deepseek_base", "https://api.deepseek.com").rstrip("/")
DS_KEY = CFG.get("deepseek_api_key", "")
DS_MODEL = CFG.get("deepseek_model", "deepseek-flash")
SHARE_BASE = CFG.get("share_base_url", "https://api.orientlaw.cn/s").rstrip("/")
MAX_AUDIO_MB = 600
ALLOWED_EXT = {".m4a", ".mp3", ".wav", ".aac", ".amr", ".ogg", ".flac", ".wma", ".opus", ".3gp"}
VIDEO_EXT = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".flv"}  # 视频先抽音轨再识别
AUDIO_MIME = {".m4a": "audio/mp4", ".mp4": "audio/mp4", ".mov": "audio/mp4",
              ".mkv": "audio/mp4", ".avi": "audio/mp4", ".webm": "audio/webm",
              ".m4v": "audio/mp4", ".flv": "audio/mp4", ".aac": "audio/aac",
              ".mp3": "audio/mpeg", ".wav": "audio/x-wav", ".amr": "audio/amr",
              ".ogg": "audio/ogg", ".opus": "audio/ogg", ".flac": "audio/flac",
              ".wma": "audio/x-ms-wma", ".3gp": "audio/3gpp"}
AUDIO_RETENTION_SECONDS = 30 * 24 * 3600   # 成功任务的音频保留30天（与分享链接同寿命），过期自动清理
SHARE_DEFAULT_HOURS = 24    # 分享有效期默认24小时
SHARE_DEFAULT_MAX_OPENS = 5  # 可打开次数默认5次（0=不限）
MAX_SLICE_MS = 600 * 1000                  # 单次回听切片上限10分钟
PWD_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
PWD_LENGTH = 20  # 分享提取码长度（链接内自动携带，手动输入仅作兜底）
VP_AUTO_THRESHOLD = 0.72     # 高置信：直接标人名
VP_TENTATIVE_THRESHOLD = 0.60  # 中置信：标"名字？"待确认
VP_MAX_EMBEDDINGS = 5        # 每人滚动保留的声纹样本数
SAMPLE_RATE = 16000
FUNASR_URL = CFG.get("funasr_url", "").rstrip("/")  # SER8 本地FunASR服务；空=不分流
FUNASR_THRESHOLD_SEC = float(CFG.get("asr_local_threshold_sec", 10800))  # 单文件超此时长走本地引擎（默认3小时）

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_AUDIO_MB * 1024 * 1024

_lock = threading.Lock()
_vp_lock = threading.Lock()
_embedder = None
_embedder_lock = threading.Lock()


# ---------------------------------------------------------------- 基础存储

def load_task(tid):
    p = DATA / (tid + ".json")
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    return None


def save_task(st):
    """原子落盘：先写临时文件再 rename，避免并发读到半截 JSON。"""
    with _lock:
        p = DATA / (st["task_id"] + ".json")
        tmp = DATA / (st["task_id"] + ".json.tmp")
        tmp.write_text(json.dumps(st, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, p)


def save_share(share):
    with _lock:
        p = DATA / (share["share_id"] + ".share.json")
        tmp = DATA / (share["share_id"] + ".share.json.tmp")
        tmp.write_text(json.dumps(share, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, p)


def load_share(sid):
    p = DATA / (sid + ".share.json")
    if not p.exists():
        return None
    try:
        sh = json.loads(p.read_text(encoding="utf-8"))
    except ValueError:
        return None
    if time.time() > sh.get("expires_at", 0):
        return None
    return sh


# ---------------------------------------------------------------- 资料夹（录音转写+聊天记录的跨类分组）
# 文件夹把录音转写任务与聊天记录分享归到同一分组，用于统一浏览与聚合分享。
# 存储：data/<fid>.folder.json；成员只存引用（kind=task→任务id，kind=wxchat→聊天分享id），
# 成员本体被删除/过期后条目自动显示为「已失效」，文件夹本身不自动过期。

FOLDER_MAX_COUNT = 100    # 每用户文件夹数量上限
FOLDER_MAX_ITEMS = 50     # 单个文件夹条目上限


def save_folder(fd):
    with _lock:
        p = DATA / (fd["folder_id"] + ".folder.json")
        tmp = DATA / (fd["folder_id"] + ".folder.json.tmp")
        tmp.write_text(json.dumps(fd, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, p)


def load_folder(fid):
    p = DATA / (str(fid) + ".folder.json")
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except ValueError:
        return None


def _count_folders(user):
    n = 0
    for p in DATA.glob("*.folder.json"):
        try:
            if json.loads(p.read_text(encoding="utf-8")).get("owner") == user:
                n += 1
        except (OSError, ValueError):
            continue
    return n


def _folder_owned(fid, user):
    """fid 格式与归属校验，通过返回文件夹，否则 None。"""
    if not re.fullmatch(r"[0-9a-f]{6,32}", str(fid)):
        return None
    fd = load_folder(fid)
    if fd is None or fd.get("owner") != user:
        return None
    return fd


def _member_owned(kind, ref, user):
    """加入条目时校验成员存在且属于该用户；返回错误文案，None=通过。"""
    if kind == "task":
        st = load_task(str(ref))
        if st is None or st.get("owner") != user:
            return "录音转写任务不存在"
        if "result" not in st:
            return "该录音转写尚未完成，完成后才能加入"
        return None
    if kind == "wxchat":
        sh = load_share(str(ref))
        if sh is None or sh.get("type") != "wxchat" or sh.get("owner") != user:
            return "聊天记录分享不存在或已过期"
        return None
    return "未知条目类型"


def _detach_from_other_folders(user, fid, kind, ref):
    """move 语义：把条目从该用户其他文件夹中移除（一条记录最多归属一个文件夹）。
    返回被移出过的文件夹名列表。"""
    moved_from = []
    for p in DATA.glob("*.folder.json"):
        try:
            fd = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if fd.get("owner") != user or fd.get("folder_id") == fid:
            continue
        items = fd.get("items") or []
        kept = [it for it in items
                if not (it.get("kind") == kind and str(it.get("ref")) == ref)]
        if len(kept) != len(items):
            fd["items"] = kept
            save_folder(fd)
            moved_from.append(fd.get("name") or fd.get("folder_id") or "")
    return moved_from


def folder_entry_view(item):
    """单条目摘要（标题/元信息/存活状态），管理接口与分享目录页共用。"""
    kind, ref = item.get("kind"), str(item.get("ref") or "")
    if kind == "task":
        st = load_task(ref)
        if st is None:
            return {"kind": kind, "ref": ref, "alive": False, "error": "转写任务已删除"}
        if "result" not in st:
            return {"kind": kind, "ref": ref, "alive": False, "error": "转写尚未完成"}
        r = st["result"]
        sec = int((r.get("duration_ms") or 0) / 1000)
        dur = ("%d分%02d秒" % (sec // 60, sec % 60)) if sec else ""
        meta = " · ".join(x for x in (
            "视频" if st.get("is_video") else "录音",
            dur,
            time.strftime("%Y-%m-%d %H:%M", time.localtime(st.get("created_at") or 0)),
            "可回听原音" if (DATA / (ref + ".audio")).exists() else "原音已过期") if x)
        return {"kind": kind, "ref": ref, "alive": True,
                "title": st.get("display_name") or st.get("filename") or "未命名录音",
                "meta": meta, "duration_ms": r.get("duration_ms") or 0,
                "is_video": bool(st.get("is_video"))}
    if kind == "wxchat":
        sh = load_share(ref)
        if sh is None or sh.get("type") != "wxchat":
            return {"kind": kind, "ref": ref, "alive": False,
                    "error": "聊天记录分享已过期或已删除"}
        chat = sh.get("chat") or {}
        meta = " · ".join(x for x in (
            "%d条消息" % (chat.get("message_count") or 0),
            "%d人" % len(chat.get("participants") or []),
            "有效至 " + time.strftime("%Y-%m-%d", time.localtime(sh.get("expires_at") or 0))) if x)
        return {"kind": kind, "ref": ref, "alive": True,
                "title": sh.get("filename") or "聊天记录", "meta": meta,
                "url": "%s/%s?pwd=%s" % (SHARE_BASE, ref, sh["pwd"])}
    return {"kind": str(kind), "ref": ref, "alive": False, "error": "未知条目类型"}


def load_users():
    """users.json: {"users":[{"user":"用户A","token":"..."},...]} -> {token: user}"""
    try:
        data = json.loads(USERS_FILE.read_text(encoding="utf-8"))
        return {u.get("token", ""): u.get("user", "") for u in data.get("users", [])
                if u.get("token") and u.get("user")}
    except (OSError, ValueError, AttributeError):
        return {}


_auth_fails = {}


def client_ip():
    return request.headers.get("X-Real-IP") or request.remote_addr or "unknown"


def auth_blocked():
    entry = _auth_fails.get(client_ip())
    return bool(entry and entry[0] >= AUTH_MAX_FAILS
                and time.time() - entry[1] < AUTH_WINDOW_SECONDS)


def auth_record_failure():
    ip = client_ip()
    now = time.time()
    entry = _auth_fails.get(ip, [0, now])
    if now - entry[1] > AUTH_WINDOW_SECONDS:
        entry = [0, now]
    entry[0] += 1
    entry[1] = now
    _auth_fails[ip] = entry
    time.sleep(0.2 + secrets.randbelow(40) / 100.0)  # 拖慢在线枚举


def authenticate():
    """恒时比较校验令牌（请求头 X-LZ-Token 或查询参数 token）；成功返回用户名，失败计一次失败并返回 None。"""
    if auth_blocked():
        return None
    token = request.headers.get("X-LZ-Token", "") or request.args.get("token", "")
    for t, user in load_users().items():
        if hmac.compare_digest(token.encode(), t.encode()):
            _auth_fails.pop(client_ip(), None)
            return user
    auth_record_failure()
    return None


def require_user():
    """带用户上下文的鉴权：返回用户名或 None（调用方返回401）。"""
    return authenticate()


def fmt_ms(ms):
    s = int(round((ms or 0) / 1000.0))
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    return "%02d:%02d:%02d" % (h, m, sec) if h else "%02d:%02d" % (m, sec)


def api_headers(extra=None):
    h = {"Authorization": "Bearer " + API_KEY}
    if extra:
        h.update(extra)
    return h


# ---------------------------------------------------------------- 百炼 ASR

def get_upload_policy():
    r = requests.get(DASH + "/api/v1/uploads",
                     params={"action": "getPolicy", "model": ASR_MODEL},
                     headers=api_headers(), timeout=30)
    r.raise_for_status()
    return r.json()["data"]


def upload_to_oss(pol, path, name):
    key = pol["upload_dir"] + "/" + name
    with open(path, "rb") as f:
        r = requests.post(pol["upload_host"],
                          data={"OSSAccessKeyId": pol["oss_access_key_id"],
                                "Signature": pol["signature"],
                                "policy": pol["policy"],
                                "key": key,
                                "x-oss-object-acl": pol.get("x_oss_object_acl", "private"),
                                "x-oss-forbid-overwrite": pol.get("x_oss_forbid_overwrite", "true"),
                                "success_action_status": "200"},
                          files={"file": (name, f)}, timeout=1800)
    if r.status_code != 200:
        raise RuntimeError("OSS upload HTTP %d: %s" % (r.status_code, r.text[:200]))
    return "oss://" + key


def submit_asr(oss_url, diarize):
    body = {"model": ASR_MODEL,
            "input": {"file_urls": [oss_url]},
            # 新版 maas 域名下 parameters 必填（空也要给对象）
            "parameters": {"diarization_enabled": bool(diarize), "language_hints": ["zh"]}}
    r = requests.post(MAAS + "/api/v1/services/audio/asr/transcription", json=body,
                      headers=api_headers({"X-DashScope-Async": "enable",
                                           "X-DashScope-OssResourceResolve": "enable"}),
                      timeout=60)
    j = r.json()
    tid = (j.get("output") or {}).get("task_id")
    if not tid:
        raise RuntimeError("提交失败: " + r.text[:300])
    return tid


def poll_asr(dash_tid, st):
    deadline = time.time() + 7200
    while time.time() < deadline:
        r = requests.get(MAAS + "/api/v1/tasks/" + dash_tid, headers=api_headers(), timeout=30)
        out = (r.json().get("output") or {})
        stst = out.get("task_status")
        st["stage"] = "识别中(" + str(stst) + ")"
        if stst == "SUCCEEDED":
            results = out.get("results") or []
            if not results or not results[0].get("transcription_url"):
                raise RuntimeError("无识别结果: " + json.dumps(out, ensure_ascii=False)[:300])
            return results[0]["transcription_url"]
        if stst == "FAILED":
            raise RuntimeError("ASR失败: %s %s" % (out.get("code"), out.get("message")))
        save_task(st)
        time.sleep(5)
    raise RuntimeError("ASR轮询超时(2h)")


def build_segments(tj, with_speaker):
    """按说话人合并连续句子（间隔<=1.5s 视为连续发言），时间取段落起止。"""
    segments = []
    cur = None
    for tr in tj.get("transcripts", []):
        for sent in tr.get("sentences", []):
            spk = sent.get("speaker_id", 0) if with_speaker else 0
            if cur and cur["speaker"] == spk and sent["begin_time"] - cur["end_ms"] <= 1500:
                cur["text"] += sent["text"]
                cur["end_ms"] = sent["end_time"]
            else:
                if cur:
                    segments.append(cur)
                cur = {"speaker": spk, "begin_ms": sent["begin_time"],
                       "end_ms": sent["end_time"], "text": sent["text"]}
    if cur:
        segments.append(cur)
    for seg in segments:
        seg["begin"] = fmt_ms(seg["begin_ms"])
        seg["end"] = fmt_ms(seg["end_ms"])
    return segments


def to_mandarin(segments):
    """分批调 LLM 把方言/口语改写为标准普通话，行号映射防止串行。"""
    out = []
    bucket = 25
    for i in range(0, len(segments), bucket):
        chunk = segments[i:i + bucket]
        lines = "\n".join("[%d]%s" % (j, s["text"]) for j, s in enumerate(chunk))
        prompt = ("下面是一段录音转写文本（可能含方言、口语、语气词）。请把每一行改写为标准普通话书面语，"
                  "保留原意、人名、地名、数字与条款信息，删除无意义的语气词（嗯、啊、呃等）。"
                  "输出格式必须与输入一致：每行以[序号]开头，一行一条，不要输出任何其他说明。\n\n" + lines)
        r = requests.post(MAAS + "/compatible-mode/v1/chat/completions",
                          headers=api_headers(),
                          json={"model": LLM_MODEL, "temperature": 0.1,
                                "messages": [{"role": "user", "content": prompt}]},
                          timeout=300)
        r.raise_for_status()
        content = r.json()["choices"][0]["message"]["content"]
        mapped = {}
        for line in content.splitlines():
            m = re.match(r"\s*\[(\d+)\]\s*(.+)", line)
            if m:
                mapped[int(m.group(1))] = m.group(2).strip()
        for j, s in enumerate(chunk):
            s2 = dict(s)
            if mapped.get(j):
                s2["text"] = mapped[j]
            out.append(s2)
    return out


# ---------------------------------------------------------------- 声纹与性别

def get_embedder():
    """懒加载声纹嵌入模型（sherpa-onnx CPU）。"""
    global _embedder
    with _embedder_lock:
        if _embedder is None:
            import sherpa_onnx
            config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                model=str(MODEL_PATH), num_threads=2)
            _embedder = sherpa_onnx.SpeakerEmbeddingExtractor(config)
        return _embedder


def read_wav_mono16k(path):
    """读 wav 为 float32 numpy（-1..1），仅支持 16k 单声道（我们自己转的）。"""
    import wave
    w = wave.open(str(path), "rb")
    if w.getframerate() != SAMPLE_RATE or w.getnchannels() != 1:
        w.close()
        raise RuntimeError("wav 格式不符")
    n = w.getnframes()
    raw = w.readframes(n)
    w.close()
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


def embed_chunk(extractor, chunk):
    """单段音频 -> L2 归一化声纹向量。"""
    stream = extractor.create_stream()
    stream.accept_waveform(SAMPLE_RATE, [float(x) for x in chunk])
    stream.input_finished()
    v = np.asarray(extractor.compute(stream), dtype=np.float32)
    norm = float(np.linalg.norm(v))
    if norm <= 0 or not np.isfinite(norm):
        return None
    return (v / norm).tolist()


def estimate_f0(chunk):
    """自相关法基频估计：返回该段所有浊音帧的 F0（Hz）。"""
    f0s = []
    frame_n = 400          # 25ms @16k
    hop = 320              # 20ms
    lag_min, lag_max = SAMPLE_RATE // 400, SAMPLE_RATE // 70   # 70~400Hz
    rms_all = float(np.sqrt(np.mean(chunk ** 2))) + 1e-9
    for i in range(0, len(chunk) - frame_n, hop):
        frame = chunk[i:i + frame_n].astype(np.float64)
        frame -= frame.mean()
        rms = math.sqrt(float(np.mean(frame ** 2)))
        if rms < 0.35 * rms_all:      # 只统计能量较高的浊音帧
            continue
        ac = np.correlate(frame, frame, "full")[frame_n - 1:]
        if ac[0] <= 0:
            continue
        seg = ac[lag_min:lag_max]
        peak = int(np.argmax(seg)) + lag_min
        if ac[peak] / ac[0] > 0.5:    # 归一化自相关阈值判定浊音
            f0s.append(SAMPLE_RATE / float(peak))
    return f0s


def judge_gender(f0s):
    """基频中位数判定。真值校准（2026-09-18，4人实测）：男声122/144/164Hz，女声258Hz。
    170~190Hz 重叠区返回空（不强行猜测，允许用户纠正）。"""
    if len(f0s) < 20:
        return ""
    med = float(np.median(f0s))
    if med < 170:
        return "男"
    if med >= 190:
        return "女"
    return ""


def extract_speaker_features(audio_path, segments):
    """音频 + 分段 -> {speaker_id: {embedding, gender, f0}}。"""
    wav_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False, dir=str(DATA)) as tmp:
            wav_path = Path(tmp.name)
        subprocess.run(["ffmpeg", "-y", "-i", str(audio_path), "-ac", "1",
                        "-ar", str(SAMPLE_RATE), "-loglevel", "error", str(wav_path)],
                       check=True, timeout=900)
        samples = read_wav_mono16k(wav_path)
        extractor = get_embedder()
        by_spk = {}
        for seg in segments:
            by_spk.setdefault(seg["speaker"], []).append(seg)
        meta = {}
        for spk, segs in by_spk.items():
            ordered = sorted(segs, key=lambda s: s["end_ms"] - s["begin_ms"], reverse=True)
            chosen, total = [], 0
            for s in ordered:
                dur = s["end_ms"] - s["begin_ms"]
                if dur < 800:
                    continue
                take = min(dur, 20000)
                chosen.append((s["begin_ms"], s["begin_ms"] + take))
                total += take
                if total >= 30000 or len(chosen) >= 3:
                    break
            if not chosen:
                continue
            embs, f0s = [], []
            for a, b in chosen:
                piece = samples[int(a * SAMPLE_RATE / 1000): int(b * SAMPLE_RATE / 1000)]
                if len(piece) < SAMPLE_RATE // 2:
                    continue
                try:
                    e = embed_chunk(extractor, piece)
                except Exception:
                    e = None  # 单段失败不影响其他段/其他说话人
                if e:
                    embs.append(e)
                try:
                    f0s.extend(estimate_f0(piece))
                except Exception:
                    pass
            entry = {"gender": judge_gender(f0s)}
            if f0s:
                entry["f0"] = int(float(np.median(f0s)))
            if embs:
                v = np.mean(np.asarray(embs, dtype=np.float32), axis=0)
                norm = float(np.linalg.norm(v))
                if norm > 0 and np.isfinite(norm):
                    entry["embedding"] = (v / norm).tolist()
            meta[str(spk)] = entry
        return meta
    except Exception as e:
        # 声纹失败不影响转写主流程
        print("speaker feature extraction failed:", e, flush=True)
        return {}
    finally:
        try:
            if wav_path and wav_path.exists():
                wav_path.unlink()
        except OSError:
            pass


def vp_all(owner=None):
    out = {}
    for p in VP_DIR.glob("*.person.json"):
        try:
            e = json.loads(p.read_text(encoding="utf-8"))
        except ValueError:
            continue
        if owner and e.get("owner") != owner:
            continue  # 声纹库按用户隔离，不同用户的声纹/人名互不可见
        out[e["person_id"]] = e
    return out


def vp_match(embedding, owner=None):
    """在指定用户的声纹库中找最像的人：返回 (person_id, name, similarity) 或 None。"""
    if not embedding:
        return None
    v = np.asarray(embedding, dtype=np.float32)
    best = None
    for pid, person in vp_all(owner).items():
        for vec in person.get("embeddings", []):
            sim = float(np.dot(v, np.asarray(vec, dtype=np.float32)))
            if best is None or sim > best[2]:
                best = (pid, person["name"], sim)
    return best


def vp_add(name, gender, embedding, owner):
    """按「用户+名字」归档声纹样本；存在同名则追加样本（滚动保留），否则新建。"""
    with _vp_lock:
        for p in VP_DIR.glob("*.person.json"):
            try:
                e = json.loads(p.read_text(encoding="utf-8"))
            except ValueError:
                continue
            if e.get("owner") == owner and e.get("name") == name:
                if embedding:
                    embs = e.get("embeddings", [])
                    embs.append(embedding)
                    e["embeddings"] = embs[-VP_MAX_EMBEDDINGS:]
                if gender:
                    e["gender"] = gender
                e["updated_at"] = time.time()
                e["sample_count"] = len(e.get("embeddings", []))
                (VP_DIR / (e["person_id"] + ".person.json")).write_text(
                    json.dumps(e, ensure_ascii=False), encoding="utf-8")
                return e
        pid = uuid.uuid4().hex[:10]
        person = {"person_id": pid, "owner": owner, "name": name, "gender": gender or "",
                  "embeddings": [embedding] if embedding else [],
                  "sample_count": 1 if embedding else 0,
                  "created_at": time.time(), "updated_at": time.time()}
        (VP_DIR / (pid + ".person.json")).write_text(
            json.dumps(person, ensure_ascii=False), encoding="utf-8")
        return person


def propagate_person(user, person):
    """人名/性别修正后同步到该用户所有任务：凡关联同一 person_id 的说话人一并更新。"""
    pid = person["person_id"]
    updated = 0
    for p in DATA.glob("*.json"):
        if p.name.endswith(".share.json"):
            continue
        try:
            st = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if st.get("owner") != user:
            continue
        names = (st.get("result") or {}).get("speaker_names") or {}
        changed = False
        for e in names.values():
            if e.get("person_id") == pid:
                e["name"] = person["name"]
                if person.get("gender"):
                    e["gender"] = person["gender"]
                if e.get("match") == "tentative":
                    e["match"] = "user"  # "？"名已在别处确认
                changed = True
        if changed:
            save_task(st)
            updated += 1
    if updated:
        print("propagated person", pid, "to", updated, "tasks", flush=True)


def attach_speaker_names(st, speaker_meta):
    """将声纹匹配结果写入 result.speaker_names。"""
    result = st["result"]
    owner = st.get("owner")
    people = vp_all(owner)
    names = {}
    for spk, meta in (speaker_meta or {}).items():
        entry = {"gender": meta.get("gender", ""), "f0": meta.get("f0"),
                 "embedding": meta.get("embedding")}
        m = vp_match(meta.get("embedding"), owner)
        if m:
            pid, pname, sim = m
            entry["similarity"] = round(sim, 3)
            entry["person_id"] = pid
            if sim >= VP_AUTO_THRESHOLD:
                entry["name"] = pname
                entry["match"] = "auto"
            elif sim >= VP_TENTATIVE_THRESHOLD:
                entry["name"] = pname + "？"
                entry["match"] = "tentative"
            # 库内性别覆盖基频判定，必须达到可疑匹配线以上；
            # 否则任意陌生声音会继承库里最像的人的性别（曾因此把男声标错）
            if sim >= VP_TENTATIVE_THRESHOLD:
                pg = people.get(pid, {}).get("gender") or ""
                if pg:
                    entry["gender"] = pg
        names[spk] = entry
    result["speaker_names"] = names


# ---------------------------------------------------------------- AI 摘要与待办

def build_transcript_for_llm(result):
    """带说话人名字的分段文本，供摘要/待办生成。"""
    names = result.get("speaker_names") or {}
    lines = []
    for s in result.get("segments", []):
        info = names.get(str(s.get("speaker")), {})
        spk = info.get("name") or ("说话人%d" % (int(s.get("speaker", 0)) + 1))
        lines.append("%s：%s" % (spk, s.get("text", "")))
    return "\n".join(lines)


def ds_chat(messages, max_tokens=8000, json_mode=False):
    body = {"model": DS_MODEL, "messages": messages,
            "temperature": 0.3, "max_tokens": max_tokens}
    if json_mode:
        body["response_format"] = {"type": "json_object"}
    r = requests.post(DS_BASE + "/chat/completions",
                      headers={"Authorization": "Bearer " + DS_KEY,
                               "Content-Type": "application/json"},
                      json=body, timeout=240)
    r.raise_for_status()
    return r.json()["choices"][0]["message"].get("content") or ""


_gen_inflight = set()


def generate_summary_todos(st):
    """调 DeepSeek 生成摘要与待办，写入 result；失败静默（页面刷新会自动重试）。"""
    tid = st.get("task_id") or ""
    if not tid or tid in _gen_inflight:
        return
    _gen_inflight.add(tid)
    try:
        result = st.get("result") or {}
        if result.get("summary_done") or result.get("summary"):
            return  # 已生成过（可能摘要/待办为空），不重复消耗token
        transcript = build_transcript_for_llm(result)
        if not transcript.strip():
            return
        prompt = (
            "下面是一段录音的转写文本，每行格式为「说话人：内容」。请完成两件事：\n"
            "1. summary：用简洁中文概括这段录音的核心内容、关键事实与结论（150-300字，抓要点，不要流水账）。\n"
            "2. todos：提取录音中提到或隐含的待办事项/行动项。每项包含 task（做什么）、"
            "owner（谁做，尽量用说话人名字或文中人名，未知填「未指定」）、due（时间，原文没有填「未指定」）。没有待办则输出空数组。\n"
            "只输出 JSON 对象，包含 summary 与 todos 两个字段。\n\n转写文本：\n" + transcript
        )
        content = ds_chat([{"role": "user", "content": prompt}], json_mode=True)
        m = re.search(r"\{.*\}", content, re.S)
        if not m:
            raise RuntimeError("回复中未找到JSON: " + content[:200])
        data = json.loads(m.group(0))
        summary = str(data.get("summary") or "").strip()
        todos = []
        for t in (data.get("todos") or []):
            if isinstance(t, dict) and str(t.get("task") or "").strip():
                todos.append({"task": str(t["task"]).strip(),
                              "owner": str(t.get("owner") or "").strip(),
                              "due": str(t.get("due") or "").strip()})
        with _lock:
            st["result"]["summary"] = summary
            st["result"]["todos"] = todos
            st["result"]["summary_done"] = True
        save_task(st)  # save_task 内部自行加锁，此处不可重复持有
        print("summary/todos generated for", tid, flush=True)
    except Exception as e:
        print("summary/todos generation failed:", e, flush=True)
    finally:
        _gen_inflight.discard(tid)


def probe_audio_seconds(path):
    """ffprobe 取媒体时长（秒），失败抛异常。"""
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
        capture_output=True, timeout=120)
    if r.returncode != 0:
        raise RuntimeError("ffprobe失败: " + r.stderr.decode(errors="ignore")[-120:])
    return float(r.stdout.decode().strip())


def funasr_transcribe(audio_path, with_spk):
    """调 SER8 本地 FunASR 服务，返回与百炼通道同构的 result 字典。"""
    with open(audio_path, "rb") as f:
        r = requests.post(FUNASR_URL + "/transcribe",
                          files={"file": (audio_path.name, f)},
                          data={"diarize": "1" if with_spk else "0"},
                          timeout=14400)  # 超长音频转换+识别可达小时级
    r.raise_for_status()
    j = r.json()
    segments = j.get("segments") or []
    if not segments:
        raise RuntimeError("FunASR返回空结果: " + json.dumps(j, ensure_ascii=False)[:200])
    return {
        "duration_ms": j.get("duration_ms"),
        "segments": segments,
        "text": "".join(s["text"] for s in segments),
        "engine": "funasr-local",
    }


def worker(tid):
    st = load_task(tid)
    audio = DATA / (tid + ".audio")
    try:
        # 视频任务：先异步抽取音轨（失败即 FAILED）
        upload_raw = DATA / (tid + ".upload")
        if upload_raw.exists():
            st["status"] = "RUNNING"
            st["stage"] = "视频音轨提取"
            save_task(st)
            try:
                rr = subprocess.run(
                    ["ffmpeg", "-y", "-loglevel", "error", "-i", str(upload_raw),
                     "-vn", "-ac", "1", "-ar", "16000", "-c:a", "aac", "-b:a", "64k",
                     "-f", "ipod", str(audio)],
                    capture_output=True, timeout=3600)
            except subprocess.TimeoutExpired:
                raise RuntimeError("视频音轨提取超时")
            if rr.returncode != 0 or not audio.exists() or audio.stat().st_size == 0:
                raise RuntimeError("视频音轨提取失败: " +
                                   rr.stderr.decode(errors="ignore")[-200:])
            upload_raw.unlink(missing_ok=True)
        with_spk = bool(st["options"].get("diarize"))
        result = None
        if FUNASR_URL:
            # 长音频分流：单文件超过阈值走 SER8 本地FunASR（零成本），失败自动回落百炼qwen
            try:
                _sec = probe_audio_seconds(audio)
            except Exception as e:
                print("ffprobe duration failed:", e, flush=True)
                _sec = None
            if _sec is not None and _sec > FUNASR_THRESHOLD_SEC:
                st["status"] = "RUNNING"
                st["stage"] = "超长音频转本地FunASR"
                save_task(st)
                try:
                    result = funasr_transcribe(audio, with_spk)
                    st["engine"] = "funasr-local"
                except Exception as e:
                    print("funasr-local failed, fallback to qwen:", repr(e), flush=True)
                    st["engine"] = "funasr-fallback-qwen"
        if result is None:
            st["status"] = "RUNNING"
            st["stage"] = "上传音频"
            save_task(st)
            pol = get_upload_policy()
            oss_url = upload_to_oss(pol, audio, tid + ".audio")
            st["stage"] = "提交识别任务"
            save_task(st)
            dash_tid = submit_asr(oss_url, st["options"].get("diarize", True))
            st["dash_task_id"] = dash_tid
            st["stage"] = "等待识别"
            save_task(st)
            turl = poll_asr(dash_tid, st)
            st["stage"] = "整理结果"
            save_task(st)
            tj = requests.get(turl, timeout=120).json()
            segments = build_segments(tj, with_spk)
            result = {
                "duration_ms": (tj.get("properties") or {}).get("original_duration_in_milliseconds"),
                "segments": segments,
                "text": "".join(s["text"] for s in segments),
            }
        st["result"] = result
        if st["options"].get("dialect2mandarin"):
            try:
                result["mandarin_segments"] = to_mandarin(segments)
            except Exception as e:  # LLM 失败不影响主结果
                result["mandarin_error"] = str(e)[:200]
        if with_spk:
            st["stage"] = "声纹分析"
            save_task(st)
            try:
                speaker_meta = extract_speaker_features(audio, segments)
                attach_speaker_names(st, speaker_meta)
            except Exception as e:
                print("voiceprint stage failed:", e, flush=True)
        st["status"] = "SUCCEEDED"
        st["stage"] = ""
        save_task(st)
    except Exception as e:
        st["status"] = "FAILED"
        st["error"] = str(e)[:500]
        st["stage"] = ""
        save_task(st)
    finally:
        # 成功任务保留音频30天供分享回听；失败任务立即删除
        if st.get("status") != "SUCCEEDED":
            try:
                if audio.exists():
                    audio.unlink()
            except OSError:
                pass
            try:
                (DATA / (tid + ".upload")).unlink(missing_ok=True)
            except OSError:
                pass


# ---------------------------------------------------------------- API

@app.get("/healthz")
@app.get("/api/asr/healthz")
def healthz():
    return "ok"


@app.post("/api/asr/task")
def create_task():
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    f = request.files.get("audio")
    if f is None or not f.filename:
        return jsonify(error="缺少 audio 文件"), 400
    ext = Path(f.filename).suffix.lower()
    if ext not in ALLOWED_EXT and ext not in VIDEO_EXT:
        return jsonify(error="不支持的音视频格式: " + ext), 400
    try:
        opts = json.loads(request.form.get("options", "{}"))
    except ValueError:
        opts = {}
    display_name = (request.form.get("display_name") or "").strip()[:200]
    tid = str(uuid.uuid4())
    dest = DATA / (tid + ".audio")
    is_video = ext in VIDEO_EXT
    if is_video:
        # 视频先保存原始文件，音轨抽取在 worker 内异步执行（避免大视频阻塞HTTP请求）
        upload_raw = DATA / (tid + ".upload")
        f.save(upload_raw)
    else:
        f.save(dest)
    st = {"task_id": tid, "status": "PENDING", "stage": "排队中",
          "filename": f.filename, "display_name": display_name,
          "owner": user,
          "is_video": is_video,
          "audio_mime": "audio/mp4" if is_video else AUDIO_MIME.get(ext, "audio/mp4"),
          "size": upload_raw.stat().st_size if is_video else dest.stat().st_size,
          "options": {"transcribe": bool(opts.get("transcribe", True)),
                      "timestamps": bool(opts.get("timestamps", True)),
                      "diarize": bool(opts.get("diarize", True)),
                      "dialect2mandarin": bool(opts.get("dialect2mandarin", False))},
          "created_at": time.time()}
    save_task(st)
    threading.Thread(target=worker, args=(tid,), daemon=True).start()
    return jsonify(task_id=tid, status="PENDING")


@app.get("/api/asr/task/<tid>")
def get_task(tid):
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    st = load_task(tid)
    if st is None or st.get("owner") != user:
        return jsonify(error="task not found"), 404
    return jsonify(st)


@app.delete("/api/asr/task/<tid>")
def delete_task(tid):
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    st = load_task(tid)
    if st is None or st.get("owner") != user:
        return jsonify(error="task not found"), 404
    removed = {"task": False, "audio": False, "shares": 0}
    for p in DATA.glob("*.share.json"):
        try:
            sh = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if sh.get("task_id") == tid:
            p.unlink(missing_ok=True)
            removed["shares"] += 1
    removed["audio"] = (DATA / (tid + ".audio")).unlink(missing_ok=True) is None
    removed["task"] = (DATA / (tid + ".json")).unlink(missing_ok=True) is None
    print("task deleted by", user, ":", tid, removed, flush=True)
    return jsonify(deleted=True, **removed)


@app.get("/api/asr/tasks")
def list_tasks():
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    share_counts = {}
    for p in DATA.glob("*.share.json"):
        try:
            sh = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        share_counts[sh.get("task_id")] = share_counts.get(sh.get("task_id"), 0) + 1
    items = []
    for p in DATA.glob("*.json"):
        if p.name.endswith(".share.json"):
            continue
        try:
            st = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if st.get("owner") != user:
            continue
        r = st.get("result") or {}
        items.append({
            "task_id": st.get("task_id"),
            "name": st.get("display_name") or st.get("filename") or "未命名录音",
            "status": st.get("status"),
            "stage": st.get("stage", ""),
            "created_at": st.get("created_at"),
            "duration_ms": r.get("duration_ms"),
            "has_summary": bool(r.get("summary")),
            "is_video": bool(st.get("is_video")),
            "share_count": share_counts.get(st.get("task_id"), 0),
        })
    items.sort(key=lambda x: x.get("created_at") or 0, reverse=True)
    return jsonify(tasks=items)


@app.post("/api/asr/auth/verify")
def auth_verify():
    if auth_blocked():
        return jsonify(error="尝试过于频繁，请15分钟后再试"), 429
    user = authenticate()
    if user is None:
        return jsonify(error="invalid token"), 401
    return jsonify(user=user)


@app.post("/api/asr/task/<tid>/share")
def create_share(tid):
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    st = load_task(tid)
    if st is None or st.get("owner") != user or st.get("status") != "SUCCEEDED":
        return jsonify(error="task not found or not finished"), 404
    # 有效期默认24小时，可打开次数默认5次（0=不限）
    try:
        hours = int(request.form.get("validity_hours", SHARE_DEFAULT_HOURS))
        max_opens = int(request.form.get("max_opens", SHARE_DEFAULT_MAX_OPENS))
    except (TypeError, ValueError):
        return jsonify(error="validity_hours / max_opens 参数错误"), 400
    if not (1 <= hours <= 24 * 60):
        return jsonify(error="有效期需在1小时~60天之间"), 400
    if not (0 <= max_opens <= 100000):
        return jsonify(error="可打开次数参数错误"), 400
    # 段落回听与说话人名字展示默认开启，与历史分享行为一致（"0"/"false" 视为关闭）
    allow_play = request.form.get("allow_segment_play", "1") not in ("0", "false", "False")
    show_names = request.form.get("show_speaker_names", "1") not in ("0", "false", "False")
    sid = uuid.uuid4().hex[:10]
    pwd = "".join(secrets.choice(PWD_ALPHABET) for _ in range(PWD_LENGTH))
    share = {"share_id": sid, "pwd": pwd, "task_id": tid,
             "created_at": time.time(), "expires_at": time.time() + hours * 3600,
             "validity_hours": hours, "max_opens": max_opens, "opens": 0,
             "allow_segment_play": allow_play, "show_speaker_names": show_names,
             "summary_pending": True,
             "filename": st.get("display_name") or st.get("filename") or "转写结果"}
    save_share(share)
    # 异步生成 AI 摘要与待办（deepseek-flash），生成后分享页刷新即可见
    threading.Thread(target=generate_summary_todos, args=(st,), daemon=True).start()
    return jsonify(share_id=sid, pwd=pwd,
                   url="%s/%s?pwd=%s" % (SHARE_BASE, sid, pwd),
                   validity_hours=hours, max_opens=max_opens,
                   allow_segment_play=allow_play, show_speaker_names=show_names,
                   expires_at=share["expires_at"])


def purge_expired_audio():
    now = time.time()
    for p in DATA.glob("*.audio"):
        try:
            if now - p.stat().st_mtime > AUDIO_RETENTION_SECONDS:
                p.unlink()
                print("purged expired audio:", p.name, flush=True)
        except OSError:
            continue
    # 过期超过7天的分享记录、失败任务记录一并清理
    for p in DATA.glob("*.share.json"):
        try:
            sh = json.loads(p.read_text(encoding="utf-8"))
            if now > sh.get("expires_at", 0) + 7 * 86400:
                p.unlink()
                print("purged expired share:", p.name, flush=True)
        except (OSError, ValueError):
            continue
    for p in DATA.glob("*.json"):
        if p.name.endswith(".share.json"):
            continue
        try:
            st = json.loads(p.read_text(encoding="utf-8"))
            if st.get("status") == "FAILED" and now - st.get("created_at", now) > AUDIO_RETENTION_SECONDS:
                p.unlink()
                print("purged failed task:", p.name, flush=True)
        except (OSError, ValueError):
            continue


def audio_purge_loop():
    while True:
        try:
            purge_expired_audio()
        except Exception as e:
            print("purge loop error:", e, flush=True)
        time.sleep(3600)


@app.get("/s/<sid>/audio")
def share_audio(sid):
    """分享链接的段落回听：按毫秒切片转 mp3 流式返回，需携带正确提取码。"""
    if not re.fullmatch(r"[0-9a-f]{6,32}", sid):
        return jsonify(error="not found"), 404
    sh = load_share(sid)
    if sh is None:
        return jsonify(error="not found"), 404
    pwd = request.args.get("pwd", "")
    if pwd.strip().upper() != str(sh["pwd"]).upper():
        return jsonify(error="forbidden"), 403
    # 创建时未开启段落回听的分享，服务端直接拒绝切片（不依赖页面行为）
    if not sh.get("allow_segment_play", True):
        return jsonify(error="该分享未开启段落回听"), 403
    # 打开次数用完后停止回放（已打开的页面本次会话内仍可听：等于上限时放行）
    if int(sh.get("max_opens") or 0) and int(sh.get("opens") or 0) > int(sh.get("max_opens")):
        return jsonify(error="打开次数已用完"), 403
    st = load_task(sh["task_id"])
    audio = DATA / (sh["task_id"] + ".audio")
    if st is None or "result" not in st or not audio.exists():
        return jsonify(error="音频已过期或不存在"), 404
    try:
        a = max(0, int(request.args.get("a", "0")))
        b = int(request.args.get("b", "0"))
    except ValueError:
        return jsonify(error="参数错误"), 400
    if b <= a:
        return jsonify(error="参数错误"), 400
    if b - a > MAX_SLICE_MS:
        b = a + MAX_SLICE_MS
    total_ms = int((st.get("result") or {}).get("duration_ms") or 0)
    if total_ms and b > total_ms:
        b = total_ms
    if b <= a:
        return jsonify(error="参数错误"), 400
    try:
        p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error",
                            "-ss", "%.3f" % (a / 1000.0), "-i", str(audio),
                            "-t", "%.3f" % ((b - a) / 1000.0),
                            "-vn", "-acodec", "libmp3lame", "-b:a", "48k",
                            "-f", "mp3", "pipe:1"],
                           capture_output=True, timeout=120)
    except subprocess.TimeoutExpired:
        return jsonify(error="音频切片超时"), 504
    if p.returncode != 0 or not p.stdout:
        return jsonify(error="音频切片失败"), 500
    return Response(p.stdout, mimetype="audio/mpeg",
                    headers={"Cache-Control": "private, max-age=86400",
                             "Accept-Ranges": "none"})


@app.get("/api/asr/task/<tid>/audio")
def task_audio(tid):
    """音频流：所有者按令牌播放自己任务的录音。
    不带 a/b → 返回整个音频文件（支持 Range 拉流/拖动）；
    带 a、b（毫秒） → 返回该片段的 mp3 切片（结果页点段落回听）。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    st = load_task(tid)
    if st is None or st.get("owner") != user or "result" not in st:
        return jsonify(error="task not found"), 404
    audio = DATA / (tid + ".audio")
    if not audio.exists():
        return jsonify(error="音频已过期或不存在"), 404
    try:
        a = int(request.args.get("a", "0"))
        b = int(request.args.get("b", "0"))
    except ValueError:
        return jsonify(error="参数错误"), 400
    if a > 0 and b > a:
        if b - a > MAX_SLICE_MS:
            b = a + MAX_SLICE_MS
        total_ms = int((st.get("result") or {}).get("duration_ms") or 0)
        if total_ms and b > total_ms:
            b = total_ms
        if b <= a:
            return jsonify(error="参数错误"), 400
        try:
            p = subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-ss", "%.3f" % (a / 1000.0),
                 "-i", str(audio), "-t", "%.3f" % ((b - a) / 1000.0),
                 "-vn", "-acodec", "libmp3lame", "-b:a", "48k", "-f", "mp3", "pipe:1"],
                capture_output=True, timeout=120)
        except subprocess.TimeoutExpired:
            return jsonify(error="音频切片超时"), 504
        if p.returncode != 0 or not p.stdout:
            return jsonify(error="音频切片失败"), 500
        return Response(p.stdout, mimetype="audio/mpeg",
                        headers={"Cache-Control": "private, max-age=86400"})
    mime = st.get("audio_mime") or "audio/mp4"
    try:
        return send_file(audio, mimetype=mime, conditional=True,
                         as_attachment=False, download_name="audio")
    except Exception as e:
        return jsonify(error="音频读取失败: " + str(e)[:120]), 500


@app.post("/api/asr/task/<tid>/summary")
def make_summary(tid):
    """按需生成（或返回已有）AI 摘要与待办。同步调用，约需几十秒。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    st = load_task(tid)
    if st is None or st.get("owner") != user or "result" not in st:
        return jsonify(error="task not found or not finished"), 404
    generate_summary_todos(st)
    st = load_task(tid) or st
    return jsonify(result=st.get("result"))


@app.post("/api/asr/task/<tid>/label")
def label_speaker(tid):
    """标注说话人名字（并入声纹库），返回更新后的 result。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    st = load_task(tid)
    if st is None or st.get("owner") != user or "result" not in st:
        return jsonify(error="task not found or not finished"), 404
    body = request.get_json(force=True, silent=True) or {}
    try:
        spk = str(int(body.get("speaker")))
    except (TypeError, ValueError):
        return jsonify(error="speaker 参数错误"), 400
    name = (body.get("name") or "").strip()[:50]
    if not name:
        return jsonify(error="缺少名字"), 400
    gender = (body.get("gender") or "").strip()
    if gender not in ("男", "女", ""):
        return jsonify(error="性别参数错误"), 400
    result = st["result"]
    meta = (result.get("speaker_names") or {}).get(spk, {})
    embedding = meta.get("embedding")
    person = vp_add(name, gender, embedding, user)
    entry = {k: v for k, v in meta.items() if k in ("similarity", "embedding", "f0")}
    entry.update(name=name, match="user", person_id=person["person_id"])
    entry["gender"] = gender or meta.get("gender") or person.get("gender") or ""
    result.setdefault("speaker_names", {})[spk] = entry
    save_task(st)
    # 名字/性别修正同步到该用户所有关联同一人的任务（含已生成的分享页）
    propagate_person(user, person)
    return jsonify(result=result)


@app.get("/api/asr/voiceprints")
def list_voiceprints():
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    people = sorted(vp_all(user).values(),
                    key=lambda e: e.get("updated_at", 0), reverse=True)
    return jsonify(voiceprints=[{"name": p.get("name"), "gender": p.get("gender", ""),
                                 "samples": p.get("sample_count", 0)} for p in people])


# ---------------------------------------------------------------- 分享页

PAGE_CSS = """
body{margin:0;background:#f7f6f1;font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;color:#212121}
.wrap{max-width:720px;margin:0 auto;padding:20px 16px 40px}
h1{font-size:18px;margin:0 0 4px;word-break:break-all}
.meta{font-size:12px;color:#8a8a8a;margin-bottom:10px}
button{border:1px solid #ccc;background:#fff;border-radius:6px;padding:6px 12px;font-size:13px;margin:2px 4px 12px 0}
.seg{margin:0 0 14px;cursor:pointer;-webkit-tap-highlight-color:transparent}
.seg.play{background:#e8f0fe;border-radius:8px}
.label{font-size:12px;font-weight:700;margin-bottom:2px}
.txt{font-size:15px;line-height:1.7}
.s0{color:#1565c0}.s1{color:#c62828}.s2{color:#2e7d32}.s3{color:#6a1b9a}
.ai{background:#fff;border:1px solid #e5e3da;border-radius:10px;padding:14px 16px;margin:0 0 14px}
.tabs{display:flex;background:#fff;border:1px solid #e5e3da;border-radius:8px;overflow:hidden;margin-bottom:12px}
.tab{flex:1;text-align:center;padding:10px 0;font-size:14px;color:#666;cursor:pointer;-webkit-tap-highlight-color:transparent}
.tab.active{color:#1565C0;font-weight:700;background:#eef4fc}
#toast{position:fixed;bottom:64px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.78);color:#fff;padding:8px 20px;border-radius:20px;font-size:13px;display:none;z-index:9;max-width:80%;text-align:center}
.aih{font-size:13px;font-weight:700;color:#1565c0;margin:10px 0 6px}
.aih:first-child{margin-top:0}
.aitxt{font-size:14px;line-height:1.8}
.todos{margin:4px 0 0;padding-left:20px}
.todos li{margin:6px 0;font-size:14px;line-height:1.6}
.gate{max-width:360px;margin:15vh auto 0;background:#fff;border-radius:10px;padding:28px 24px;text-align:center}
.gate input{font-size:16px;letter-spacing:2px;text-align:center;width:100%;padding:8px;border:1px solid #ccc;border-radius:6px;text-transform:uppercase;font-family:monospace}
.gate .err{color:#c62828;font-size:13px;margin-top:10px}
"""


def render_page(title, meta_html, body_html):
    return ("<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            "<title>%s</title><style>%s</style></head><body>%s</body></html>"
            % (html.escape(title), PAGE_CSS, body_html))


def render_gate(sid, wrong):
    body = ("<div class=\"gate\"><div style=\"font-size:16px;margin-bottom:14px\">轻舟浏览器 · 转写结果</div>"
            "<form method=\"post\" action=\"/s/%s\">"
            "<input name=\"pwd\" maxlength=\"32\" placeholder=\"提取码\" autofocus>"
            "<div><button type=\"submit\" style=\"margin-top:14px\">查看</button></div>"
            "</form>%s</div>"
            % (sid, "<div class=\"err\">提取码不正确</div>" if wrong else ""))
    return render_page("提取码", "", body)


@app.get("/s/<sid>")
@app.post("/s/<sid>")
def share_page(sid):
    if not re.fullmatch(r"[0-9a-f]{6,32}", sid):
        return render_page("错误", "", "<div class=\"gate\">链接不存在或已过期</div>"), 404
    sh = load_share(sid)
    if sh is None:
        return render_page("错误", "", "<div class=\"gate\">链接不存在或已过期</div>"), 404
    pwd = request.args.get("pwd", "")
    if request.method == "POST":
        pwd = request.form.get("pwd", "")
    if pwd.strip().upper() != str(sh["pwd"]).upper():
        if request.method == "GET" and pwd == "":
            return render_gate(sid, False)
        return render_gate(sid, True), 200
    # 微信聊天记录转存分享：独立渲染，不计音频任务的打开次数
    if sh.get("type") == "wxchat":
        return wxchat_share_page(sh, sid)
    # 资料夹聚合分享：目录页（打开计数在目录页内完成），条目页经 /s/<sid>/item/<idx>
    if sh.get("type") == "folder":
        return folder_share_page(sh, sid)
    # 打开次数：每次密码校验通过并查看内容计一次；超出后不再展示
    max_opens = int(sh.get("max_opens") or 0)
    opens = int(sh.get("opens") or 0)
    if max_opens and opens >= max_opens:
        return render_page("错误", "",
                           "<div class=\"gate\">打开次数已用完（上限 %d 次）</div>" % max_opens), 200
    sh["opens"] = opens + 1
    save_share(sh)
    st = load_task(sh["task_id"])
    if st is None or "result" not in st:
        return render_page("错误", "", "<div class=\"gate\">结果数据不存在</div>"), 404
    result = st["result"]
    opts = st.get("options", {})
    # 分享级开关：段落回听、说话人名字展示（历史分享缺省视为开启）
    allow_play = bool(sh.get("allow_segment_play", True))
    show_names = bool(sh.get("show_speaker_names", True))

    def segs_js(key):
        arr = result.get(key) or []
        return json.dumps(arr, ensure_ascii=False).replace("</", "<\\/")

    # 分享页不携带声纹向量（生物特征信息不出服务器）；关闭名字展示时名字一并不下发
    names_public = {}
    if show_names:
        for k, v in (result.get("speaker_names") or {}).items():
            names_public[k] = {kk: vv for kk, vv in v.items() if kk != "embedding"}
    names_js = json.dumps(names_public, ensure_ascii=False).replace("</", "<\\/")

    total_ms = result.get("duration_ms") or 0
    total_sec = int(total_ms / 1000)
    dur_text = ("%d分%02d秒" % (total_sec // 60, total_sec % 60)) if total_sec else ""
    meta = []
    if dur_text:
        meta.append("时长 " + dur_text)
    if opts.get("diarize"):
        meta.append("区分说话人")
    if opts.get("timestamps"):
        meta.append("含起止时间")
    if result.get("mandarin_segments"):
        meta.append("方言已译普通话")
    meta_html = " · ".join(meta)
    has_mand = 1 if result.get("mandarin_segments") else 0

    summary = str(result.get("summary") or "")
    todos = result.get("todos") or []
    ai_inner = ""
    if summary or todos:
        parts = []
        if summary:
            parts.append('<div class="aih">AI 摘要</div><div class="aitxt">%s</div>'
                         % html.escape(summary))
        if todos:
            titems = ""
            for t in todos:
                extra = ""
                if t.get("owner") or t.get("due"):
                    extra = '<span class="meta">　%s · %s</span>' % (
                        html.escape(t.get("owner") or "未指定"),
                        html.escape(t.get("due") or "未指定"))
                titems += '<li><label><input type="checkbox">%s%s</label></li>' % (
                    html.escape(t.get("task", "")), extra)
            parts.append('<div class="aih">待办事项</div><ul class="todos">%s</ul>' % titems)
        ai_inner = ('<div class="ai"><button onclick="copyAI()">复制摘要与待办</button>'
                    + "".join(parts) + "</div>")
    elif sh.get("summary_pending") and not result.get("summary_done"):
        ai_inner = '<div class="meta" style="margin:6px 0 10px">AI 摘要与待办生成中，请稍后刷新页面查看…</div>'
        # 页面访问触发/重试生成（幂等，进行中会跳过）
        threading.Thread(target=generate_summary_todos, args=(st,), daemon=True).start()
    has_ai = 1 if ai_inner else 0
    ai_tab = '<div class="tab" onclick="switchTab(1,this)">AI 摘要 · 待办</div>' if has_ai else ""
    ai_page = ('<div id="page1" style="display:none">' + ai_inner + "</div>") if has_ai else ""
    summary_js = json.dumps(summary, ensure_ascii=False).replace("</", "<\\/")
    todos_js = json.dumps(todos, ensure_ascii=False).replace("</", "<\\/")

    body = ("<div class=\"wrap\"><h1>%s</h1><div class=\"meta\">%s · 轻舟浏览器转写</div>"
            "<div class=\"tabs\">"
            "<div class=\"tab active\" onclick=\"switchTab(0,this)\">识别结果</div>"
            "%s"
            "</div>"
            "<div id=\"page0\">"
            "<button id=\"tg\" style=\"display:%s\">查看原文</button>"
            "<button onclick=\"copyAll()\">复制全文</button>"
            "%s"
            "<div id=\"content\"></div>"
            "</div>"
            "%s"
            "</div>"
            "<div id=\"toast\"></div>"
            "<script>"
            "var PWD=%s;var SID='%s';var ORIG=%s;var MAND=%s;var NAMES=%s;var HAS_TS=%d;var HAS_SPK=%d;var CAN_PLAY=%d;var SHOW_NAME=%d;var SUMMARY=%s;var TODOS=%s;var mand=false;"
            "var colors=['s0','s1','s2','s3'];"
            "function spkInfo(id){return NAMES[String(id)]||null;}"
            "function spkLabel(id){if(!HAS_SPK)return '';"
            "var n=SHOW_NAME?spkInfo(id):null;"
            "if(n&&n.name){return n.gender?n.name+'（'+n.gender+'）':n.name;}"
            "var cmap=window.__cmap||{};var idx=(cmap[String(id)]!==undefined)?cmap[String(id)]:Number(id);"
            "return '说话人'+(idx+1);}"
            "function spkColor(id){var cmap=window.__cmap||{};var i=cmap[String(id)];"
            "return (i===undefined)?colors[0]:colors[i %% colors.length];}"
            "function esc(s){var d=document.createElement('div');d.textContent=s;return d.innerHTML;}"
            "var _tt=null;function toast(m){var t=document.getElementById('toast');t.textContent=m;"
            "t.style.display='block';if(_tt)clearTimeout(_tt);_tt=setTimeout(function(){t.style.display='none';},1600);}"
            "function legacyCopy(t){var ta=document.createElement('textarea');ta.value=t;"
            "ta.style.position='fixed';ta.style.opacity='0';document.body.appendChild(ta);ta.focus();ta.select();"
            "try{document.execCommand('copy');}catch(e){}document.body.removeChild(ta);}"
            "function copyText(t,ok){"
            "if(navigator.clipboard&&navigator.clipboard.writeText){"
            "navigator.clipboard.writeText(t).then(ok,function(){legacyCopy(t);ok();});}else{legacyCopy(t);ok();}}"
            "function switchTab(n,el){var tabs=document.querySelectorAll('.tab');"
            "for(var i=0;i<tabs.length;i++){tabs[i].classList.remove('active');}"
            "el.classList.add('active');"
            "document.getElementById('page0').style.display=(n===0)?'block':'none';"
            "var p1=document.getElementById('page1');if(p1)p1.style.display=(n===1)?'block':'none';}"
            "function render(){var arr=mand&&MAND?MAND:ORIG;var h='';"
            "var cmap={};var ci=0;"
            "for(var i=0;i<arr.length;i++){var sid=String(arr[i].speaker);"
            "if(!(sid in cmap)){cmap[sid]=ci++;}}"
            "window.__cmap=cmap;"
            "for(var i=0;i<arr.length;i++){var s=arr[i];var lab=[];"
            "var sl=spkLabel(s.speaker);if(sl)lab.push('<span class=\"'+spkColor(s.speaker)+'\">'+esc(sl)+'</span>');"
            "if(HAS_TS)lab.push(esc(s.begin+' - '+s.end));"
            "h+='<div class=\"seg\"'+(CAN_PLAY?' onclick=\"segClick(this)\"':'')+' data-a=\"'+s.begin_ms+'\" data-b=\"'+s.end_ms+'\">'+(lab.length?'<div class=\"label\">'+lab.join('   ')+'</div>':'')+'<div class=\"txt\">'+esc(s.text)+'</div></div>';}"
            "document.getElementById('content').innerHTML=h;"
            "document.getElementById('tg').textContent=mand?'查看原文':'查看普通话译文';"
            "window.__cur=arr;}"
            "function copyAll(){var arr=window.__cur||ORIG;var t='';"
            "for(var i=0;i<arr.length;i++){var s=arr[i];var sl=spkLabel(s.speaker);"
            "t+=(sl?'['+sl+']':'')+(HAS_TS?'['+s.begin+'-'+s.end+']':'')+s.text+'\\n';}"
            "copyText(t,function(){toast('复制成功');});}"
            "function copyAI(){var t='【AI 摘要】'+SUMMARY+'\\n';"
            "if(TODOS&&TODOS.length){t+='\\n【待办事项】\\n';"
            "for(var i=0;i<TODOS.length;i++){var d=TODOS[i];t+=(i+1)+'. '+d.task;"
            "if(d.owner&&d.owner!=='未指定')t+='（'+d.owner+'）';"
            "if(d.due&&d.due!=='未指定')t+=' ['+d.due+']';t+='\\n';}}"
            "copyText(t,function(){toast('复制成功');});}"
            "var audioEl=null,curSeg=null;"
            "function segClick(el){var a=el.getAttribute('data-a'),b=el.getAttribute('data-b');"
            "if(!audioEl){audioEl=new Audio();}"
            "var A=audioEl;"
            "if(curSeg===el){if(A.paused){A.play();}else{A.pause();}return;}"
            "if(curSeg){curSeg.classList.remove('play');}"
            "curSeg=el;el.classList.add('play');"
            "A.src='/s/'+SID+'/audio?a='+a+'&b='+b+'&pwd='+encodeURIComponent(PWD);"
            "A.onended=function(){if(curSeg)curSeg.classList.remove('play');};"
            "A.onerror=function(){var s=document.getElementById('audstat');if(s)s.textContent='本段音频无法回放（音频已过期或不存在）';};"
            "A.play();}"
            "document.getElementById('tg').onclick=function(){mand=!mand;render();};"
            "render();</script>"
            % (html.escape(sh.get("filename") or "转写结果"), html.escape(meta_html),
               ai_tab,
               "inline-block" if has_mand else "none",
               ('<div id="audstat" class="meta" style="margin-bottom:12px">'
                '点击任意段落可回听对应原音</div>') if allow_play else "",
               ai_page,
               json.dumps(str(sh["pwd"])), sid,
               segs_js("segments"), segs_js("mandarin_segments"), names_js,
               1 if opts.get("timestamps") else 0, 1 if opts.get("diarize") else 0,
               1 if allow_play else 0, 1 if show_names else 0,
               summary_js, todos_js))
    return render_page(sh.get("filename") or "转写结果", meta_html, body)


# ---------------------------------------------------------------- 微信聊天记录转存
# 微信把合并转发消息分享给第三方应用时打包为 聊天记录_日期_时间.zip：
# 聊天记录.txt（UTF-8，每条消息 ·发送者/时间/内容，附件为 [文件] 名字）
# 加媒体目录（聊天记录内的图片、视频和文件/）。条目名未打 UTF-8 标志，按 GBK 解码。

import base64
import glob
import io
import mimetypes
import shutil
from concurrent.futures import ThreadPoolExecutor

WXCHAT_HOURS = 30 * 24          # 与音频分享同寿命：30 天
WXCHAT_MAX_ENTRIES = 500        # zip 条目数上限（防解压炸弹）
WXCHAT_MAX_UNPACK_MB = 600      # 解压累计大小上限（与上传上限一致）


def zip_entry_name(info):
    """微信 zip 的条目名没打 UTF-8 标志位，默认 cp437 读出乱码，需转 GBK。"""
    if info.flag_bits & 0x800:
        return info.filename
    try:
        return info.filename.encode("cp437").decode("gbk")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return info.filename


_WX_TIME_RE = re.compile(r"^(?:(\d{4})年(\d{1,2})月(\d{1,2})日\s+)?(\d{1,2}):(\d{2})$")
_WX_TAG_RE = re.compile(r"^\[([^]]+)\]\s*(.*)$", re.S)


def _wx_parse_time(raw, last_date):
    m = _WX_TIME_RE.match(raw.strip())
    if not m:
        return None, last_date
    y, mo, d, hh, mm = m.groups()
    if y:
        last_date = "%04d-%02d-%02d" % (int(y), int(mo), int(d))
    return ((last_date or "") + " %02d:%02d" % (int(hh), int(mm))).strip(), last_date


def _wx_classify(text):
    """按微信标记识别消息类型：[文件]x→file、[图片]/[视频]/[语音]等按标记，其余按文本。"""
    m = _WX_TAG_RE.match(text.strip())
    if not m:
        return "text", None, text
    tag, rest = m.group(1), m.group(2).strip()
    low = tag.lower()
    if low == "文件":
        return "file", rest, rest
    if low == "图片":
        return "image", rest or None, text
    if low == "视频":
        return "video", rest or None, text
    if low == "语音":
        return "voice", rest or None, text
    if low in ("链接", "位置", "名片", "小程序", "音乐"):
        return low, rest or None, text
    return "text", None, text


def _wx_parse_chat_text(text):
    """逐行扫描：·行开新消息，下一行须是时间（一并消费），其余行进内容。"""
    lines = text.replace("\r\n", "\n").split("\n")
    msgs = []
    cur = None
    last_date = None

    def flush():
        nonlocal cur
        if cur is None:
            return
        content = "\n".join(cur["lines"]).strip()
        if content:
            mtype, fname, clean = _wx_classify(content)
            msg = {"sender": cur["sender"], "time": cur["time"], "type": mtype, "text": clean}
            if fname:
                msg["file_name"] = fname
            msgs.append(msg)
        cur = None

    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("·") and i + 1 < len(lines):
            t, last_date = _wx_parse_time(lines[i + 1], last_date)
            if t is not None:
                flush()
                cur = {"sender": line[1:].strip(), "time": t, "lines": []}
                i += 2
                continue
        if cur is not None:
            cur["lines"].append(line)
        i += 1
    flush()
    return msgs


def wxchat_parse_zip(zip_path, title):
    """解析聊天记录 zip：返回 (chat_dict, others)。others 为消息未引用的附件 (名, info)。"""
    total = 0
    with zipfile.ZipFile(zip_path) as zf:
        infos = zf.infolist()
        if len(infos) > WXCHAT_MAX_ENTRIES:
            raise ValueError("zip 内文件过多（>%d），不是聊天记录分享包" % WXCHAT_MAX_ENTRIES)
        txt_data = None
        media = []
        for info in infos:
            if info.is_dir():
                continue
            total += info.file_size
            if total > WXCHAT_MAX_UNPACK_MB * 1024 * 1024:
                raise ValueError("zip 解压内容过大，拒绝处理")
            name = zip_entry_name(info).replace("\\", "/")
            base = PurePosixPath(name).name
            if not base or base.startswith("."):
                continue
            if name.endswith(".txt") and "聊天记录" in name and txt_data is None:
                txt_data = zf.read(info)
            else:
                media.append((base, info))
        if txt_data is None:
            raise ValueError("zip 内未找到 聊天记录.txt，不是微信聊天记录分享包")
        messages = _wx_parse_chat_text(txt_data.decode("utf-8", errors="replace"))
        if not messages:
            raise ValueError("聊天记录.txt 内未解析出消息")
        by_name = {}
        for base, info in media:
            by_name.setdefault(base, info)
        # 附件与文件消息对号入座；对不上的留在 others 供页面「其他附件」展示
        attachments = []
        att_index = {}
        used = set()
        for m in messages:
            if m.get("type") == "file" and m.get("file_name") in by_name:
                base = m["file_name"]
                if base not in att_index:
                    att_index[base] = len(attachments)
                    attachments.append({"name": base, "size": by_name[base].file_size})
                m["att"] = att_index[base]
                m["size"] = by_name[base].file_size
                used.add(base)
        participants = []
        for m in messages:
            if m["sender"] not in participants:
                participants.append(m["sender"])
        times = [m["time"] for m in messages if m["time"]]
        chat = {
            "title": title or "聊天记录",
            "source": "wechat",
            "participants": participants,
            "message_count": len(messages),
            "time_start": times[0] if times else None,
            "time_end": times[-1] if times else None,
            "messages": messages,
            "attachments": attachments,
        }
        # [图片]/[视频] 消息与媒体目录中未被引用的图片/视频按顺序配对
        # （微信导出按消息顺序生成 img_N 等序号文件名，txt 中不写文件名）
        img_files = sorted((b for b, i in media
                            if b not in used and PurePosixPath(b).suffix.lower() in _IMG_EXT),
                           key=_natural_key)
        vid_files = sorted((b for b, i in media
                            if b not in used and PurePosixPath(b).suffix.lower() in _VIDEO_EXT),
                           key=_natural_key)
        for m, base in zip((m for m in messages if m.get("type") == "image"), img_files):
            used.add(base)
            m["att"] = len(attachments)
            m["size"] = by_name[base].file_size
            attachments.append({"name": base, "size": by_name[base].file_size})
        for m, base in zip((m for m in messages if m.get("type") == "video"), vid_files):
            used.add(base)
            m["att"] = len(attachments)
            m["size"] = by_name[base].file_size
            attachments.append({"name": base, "size": by_name[base].file_size})
        others = [(base, info) for base, info in media if base not in used]
        return chat, others


_IMG_EXT = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"}
_VIDEO_EXT = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v"}


def _natural_key(name):
    """文件名自然排序：img_2.jpg 排在 img_10.jpg 前（微信媒体文件按序号命名）。"""
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", name.lower())]


# ---- 视觉摘录（GLM-4V-Flash）：附件中的图片与 PDF 自动解析为 Markdown ----

GLM_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
GLM_MODEL = CFG.get("glm_vision_model", "glm-4v-flash")
GLM_KEY = CFG.get("glm_vision_api_key", "")
OCR_MAX_RETRY = 8               # 429/5xx 重试次数（配指数退避）
OCR_RETRY_DELAY = 1.0
OCR_CONCURRENCY = int(CFG.get("glm_ocr_concurrency", 10))  # GLM 摘录并发，账户额度可调
OCR_MAX_PDF_PAGES = 30
OCR_RENDER_BATCH = 20         # pdftoppm 单批渲染页数（每批独立 300 秒超时，整本不限页）
OCR_MAX_FILE_MB = 30
VISION_FILE_MB = 100          # glm-vision 服务端单文件上限（超限预检剔除，避免 400 连坐整批）
VISION_SUBMIT_MB = 200        # 单次 /v1/tasks 请求的文件总量上限（服务端请求体上限 512MB，留余量）

OCR_PROMPT = """请完整摘录这张图片中的所有文字内容，逐字不要修改、不得概括、不得省略。

要求:
1. 逐字摘录，确保不漏字、不漏行；聊天截图按「发送者: 内容」逐行摘录
2. 表格必须用 Markdown 表格格式输出（用 | 分隔单元格）
3. 页眉/页脚/页码保留；摘录结尾另起一行用「【页码: X】」标注（页面上无页码则写「【页码: 无】」）
4. 段落结构、缩进、空行尽量保持原样
5. 图章/印章/签名用【印章】【签名】标注在对应位置
6. 模糊或无法辨认的字符用 [?] 替代
7. 无文字内容则写「(本页无文字)【页码: 无】」

请开始摘录:"""


def _ocr_compress(path):
    """图片压到 GLM 限制内（≤5MB、单边≤6000px），返回 (b64, mime)。"""
    from PIL import Image
    img = Image.open(path)
    if img.mode not in ("RGB", "RGBA", "L"):
        img = img.convert("RGB")
    w, h = img.size
    if max(w, h) > 6000:
        scale = 6000 / max(w, h)
        img = img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
    buf = io.BytesIO()
    img.convert("RGB").save(buf, format="PNG", optimize=True)
    if buf.tell() <= 5 * 1024 * 1024:
        return base64.b64encode(buf.getvalue()).decode(), "image/png"
    for quality in (95, 85, 75, 65, 55):
        buf = io.BytesIO()
        img.convert("RGB").save(buf, format="JPEG", quality=quality, optimize=True)
        if buf.tell() <= 5 * 1024 * 1024:
            return base64.b64encode(buf.getvalue()).decode(), "image/jpeg"
    img = img.resize((int(img.size[0] * 0.5), int(img.size[1] * 0.5)), Image.LANCZOS)
    buf = io.BytesIO()
    img.convert("RGB").save(buf, format="JPEG", quality=60)
    return base64.b64encode(buf.getvalue()).decode(), "image/jpeg"


def _glm_extract_image(b64, mime):
    """调 GLM-4V-Flash 摘录单张图；429/5xx/超时固定 1 秒重试最多 5 次。"""
    payload = {"model": GLM_MODEL, "messages": [{"role": "user", "content": [
        {"type": "image_url", "image_url": {"url": "data:%s;base64,%s" % (mime, b64)}},
        {"type": "text", "text": OCR_PROMPT}]}]}
    last = None
    for attempt in range(1, OCR_MAX_RETRY + 1):
        try:
            r = requests.post(GLM_API_URL, timeout=120, json=payload,
                              headers={"Authorization": "Bearer " + GLM_KEY,
                                       "Content-Type": "application/json"})
            if r.status_code == 429 or r.status_code >= 500:
                last = "HTTP %s" % r.status_code
                if attempt < OCR_MAX_RETRY:
                    time.sleep(min(30, 2 ** (attempt - 1)))
                    continue
                return None, last
            if r.status_code >= 400:
                return None, "HTTP %s: %s" % (r.status_code, r.text[:200])
            content = (((r.json().get("choices") or [{}])[0]).get("message") or {}).get("content", "")
            if not content:
                last = "空响应"
                if attempt < OCR_MAX_RETRY:
                    time.sleep(OCR_RETRY_DELAY)
                    continue
                return None, last
            return content, None
        except requests.RequestException as e:
            last = str(e)[:200]
            if attempt < OCR_MAX_RETRY:
                time.sleep(OCR_RETRY_DELAY)
    return None, last or "未知错误"


def _ocr_one_image(path):
    try:
        b64, mime = _ocr_compress(path)
    except Exception as e:
        return None, "预处理失败: %s" % e
    return _glm_extract_image(b64, mime)


def _vision_extract_batch(jobs, save):
    """批量走 SER8 glm-vision 统一入口：一次 /v1/tasks 提交全部文件，统一长轮询收结果。

    服务端调度器自带数千路并发（auto 共 3150 路），逐文件提交+串行等待会把多文件
    解析拖成串行；这里把一次解析的全部附件合并提交（同一 batch_id），再轮转长轮询，
    谁先完成谁先落账。jobs = [(att, path, ext)]；结果直接写 att 并经 save() 落盘。
    config.vision_model 可点名模型（如 glm-4v-flash 保免费池）；留空 = auto，吞吐最高
    （注意：auto 优先落阿里云 batch 计费通道，GLM 免费池为溢出/兜底——见服务端 notices）。
    """
    base = (CFG.get("vision_base_url") or "").rstrip("/")
    hd = {"Authorization": "Bearer " + (CFG.get("vision_api_key") or ""),
          "Content-Type": "application/json"}
    model = str(CFG.get("vision_model") or "").strip()
    t0 = time.time()

    def finish(att, text=None, err=None):
        if text is not None:
            att["ocr"] = text
            att["ocr_status"] = "done"
        else:
            att["ocr_status"] = "failed"
            att["ocr_error"] = err
        save()

    def submit(chunk):
        """提交一批 items；返回 [(att, task_id, deadline)]，提交失败则整块按失败收尾。"""
        items = []
        for att, path, _ext in chunk:
            with open(path, "rb") as f:
                it = {"name": os.path.basename(path),
                      "data_b64": base64.b64encode(f.read()).decode(),
                      "key": att.get("name") or os.path.basename(path)}
            if model:
                it["model"] = model
            items.append(it)
        try:
            r = requests.post(base + "/v1/tasks", timeout=300, headers=hd, json={"items": items})
            if r.status_code >= 400:
                for att, _p, _e in chunk:
                    finish(att, err="vision 提交失败 HTTP %s: %s" % (r.status_code, r.text[:200]))
                return []
            tids = [t.get("task_id") for t in (r.json().get("tasks") or [])]
            if len(tids) != len(chunk) or not all(tids):
                for att, _p, _e in chunk:
                    finish(att, err="vision 响应 task 数不符: %s" % (r.text[:200]))
                return []
            return [(att, tid, t0 + (3600 if ext == ".pdf" else 600))
                    for (att, _p, ext), tid in zip(chunk, tids)]
        except requests.RequestException as e:
            for att, _p, _e in chunk:
                finish(att, err="vision 网络异常: %s" % str(e)[:200])
            return []

    running = []                      # (att, task_id, deadline)
    chunk, chunk_bytes = [], 0        # 请求体超限分块：按 base64 体积（约 4/3 原始字节）累计
    for att, path, ext in jobs:
        size = os.path.getsize(path)
        if size > VISION_FILE_MB * 1024 * 1024:
            finish(att, err="超过 glm-vision 单文件上限 %dMB" % VISION_FILE_MB)
            continue
        if chunk and chunk_bytes + size * 4 // 3 > VISION_SUBMIT_MB * 1024 * 1024:
            running.extend(submit(chunk))
            chunk, chunk_bytes = [], 0
        chunk.append((att, path, ext))
        chunk_bytes += size * 4 // 3
    if chunk:
        running.extend(submit(chunk))

    while running:                    # 轮转长轮询：所有任务在服务端并行跑，先完成先落账
        left = []
        for att, tid, dl in running:
            if time.time() > dl:
                finish(att, err="vision 任务超时，task_id=%s（服务端仍在跑，重新解析可续）" % tid)
                continue
            try:
                q = requests.get(base + "/v1/tasks/" + tid, timeout=180, headers=hd,
                                 params={"wait": 1, "timeout": 120})
            except requests.RequestException:
                left.append((att, tid, dl))   # 网络抖动：保留下一轮再查
                continue
            if q.status_code == 404:
                finish(att, err="vision 任务不存在: %s" % tid)
                continue
            if q.status_code >= 400:
                finish(att, err="vision 查询失败 HTTP %s: %s" % (q.status_code, q.text[:200]))
                continue
            tj = q.json()
            st = tj.get("status")
            if st == "done":
                res = tj.get("result") or {}
                pgs = res.get("pages") or []
                ok = sum(1 for p in pgs if p.get("ok"))
                head = "共 %d 页，成功 %d 页\n\n" % (tj.get("pages_total") or len(pgs), ok)
                finish(att, text=head + (res.get("text") or ""))
                continue
            if st == "error":
                finish(att, err="vision 任务失败: %s" % (tj.get("error") or "未知"))
                continue
            left.append((att, tid, dl))
        running = left


def _ocr_extract(path, ext):
    """直连 GLM 兜底（未配置 SER8 glm-vision 时用）：PDF 拆页并发摘录，图片单张。"""
    if ext == ".pdf":
        return _ocr_pdf_to_markdown(path)
    return _ocr_one_image(str(path))


def _ocr_pdf_to_markdown(pdf_path):
    """pdftoppm 拆页后并发摘录，合并为带页码结构的 Markdown。"""
    import tempfile
    work = tempfile.mkdtemp(prefix="wxocr_")
    try:
        # pdfinfo 取总页数；pdftoppm 分批(-f/-l)渲染整本，单批 300 秒，页数不设上限
        total_pages = None
        try:
            info = subprocess.run(["pdfinfo", str(pdf_path)], capture_output=True, timeout=60)
            if info.returncode == 0:
                m = re.search(r"^Pages:\s+(\d+)",
                              info.stdout.decode("utf-8", "replace"), re.M)
                if m:
                    total_pages = int(m.group(1))
        except Exception:
            pass
        if not total_pages:
            return None, "pdfinfo 未取得 PDF 页数"
        pages = []  # (页号, 文件路径)
        first = 1
        while first <= total_pages:
            last = min(first + OCR_RENDER_BATCH - 1, total_pages)
            bdir = os.path.join(work, "b%04d" % first)
            os.mkdir(bdir)
            p = subprocess.run(["pdftoppm", "-r", "150", "-png",
                                "-f", str(first), "-l", str(last),
                                str(pdf_path), os.path.join(bdir, "pg")],
                               capture_output=True, timeout=300)
            if p.returncode != 0:
                return None, "pdftoppm 失败(第 %d-%d 页): %s" % (
                    first, last, p.stderr.decode("utf-8", "replace")[:200])
            batch = sorted(glob.glob(os.path.join(bdir, "pg-*.png")))
            if len(batch) != last - first + 1:
                return None, "pdftoppm 渲染页数不全(第 %d-%d 页): 仅得 %d 页" % (
                    first, last, len(batch))
            pages.extend((first + i, f) for i, f in enumerate(batch))
            first = last + 1
        pagemap = dict(pages)
        with ThreadPoolExecutor(max_workers=OCR_CONCURRENCY) as ex:
            futs = {no: ex.submit(_ocr_one_image, pg) for no, pg in pages}
            results = {no: fut.result() for no, fut in futs.items()}
        for no in [no for no in sorted(results) if results[no][1]]:  # 失败页补摘一轮
            time.sleep(OCR_RETRY_DELAY)
            results[no] = _ocr_one_image(pagemap[no])
        parts = []
        ok = 0
        for no in sorted(results):
            text, err = results[no]
            parts.append("## 第 %d 页" % no)
            parts.append("")
            if err:
                parts.append("> ⚠️ 本页摘录失败：%s" % err)
            else:
                ok += 1
                parts.append(text)
            parts.append("")
        head = "共 %d 页，成功 %d 页\n\n" % (total_pages, ok)
        return head + "\n".join(parts).strip(), None
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ---- 微信昵称→真名映射（按用户隔离；分享页与 chat.json 渲染时实时应用） ----


def _wxnames_path(user):
    safe = re.sub(r"[^0-9A-Za-z_\-\u4e00-\u9fff]", "_", user)[:60]
    return DATA / ("wxnames_%s.map" % safe)  # 非 .json 后缀，避开 DATA 任务文件遍历


def load_wxnames(user):
    p = _wxnames_path(user)
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
    return {}


@app.get("/api/asr/wxchat/names")
def wxchat_names():
    """我的昵称→真名映射 + 历史分享中出现过的全部昵称（设置页管理用）。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    names = load_wxnames(user)
    known = []
    for p in DATA.glob("*.share.json"):
        try:
            sh = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if sh.get("type") != "wxchat" or sh.get("owner") != user:
            continue
        for n in (sh.get("chat") or {}).get("participants") or []:
            if n not in known:
                known.append(n)
    return jsonify(names=names, known=known,
                   self_nickname=names.get("__self__"))


@app.post("/api/asr/wxchat/names")
def wxchat_names_bulk():
    """批量设置昵称→真名；share_id 给出时同步刷新该分享的显示名快照
    （转存完成后 APP 弹窗填名的保存通道）。空真名的项跳过不覆盖。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    data = request.get_json(force=True, silent=True) or {}
    raw = data.get("mapping") or {}
    share_id = str(data.get("share_id") or "").strip()
    if not isinstance(raw, dict):
        return jsonify(error="mapping 需为对象"), 400
    names = load_wxnames(user)
    count = 0
    for k, v in raw.items():
        nick = str(k).strip()[:100]
        real = str(v).strip()[:100]
        if nick and real:  # 空真名=该项不设置（不清除已有映射）
            names[nick] = real
            count += 1
    p = _wxnames_path(user)
    tmp = DATA / (p.name + ".tmp")
    tmp.write_text(json.dumps(names, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, p)
    refreshed = False
    if share_id and re.fullmatch(r"[0-9a-f]{6,32}", share_id):
        sh = load_share(share_id)
        if sh is not None and sh.get("type") == "wxchat" and sh.get("owner") == user:
            chat = sh.get("chat") or {}
            participants = chat.get("participants") or []
            chat["name_display"] = {n: names.get(n, n) for n in participants}
            save_share(sh)
            refreshed = True
    print("wxchat names bulk by", user, ":", count, "entries, refresh", share_id or "-",
          "->", refreshed, flush=True)
    return jsonify(saved=True, count=count, refreshed=refreshed)


@app.post("/api/asr/wxchat/name")
def wxchat_set_name():
    """设置/清除（realname 空）昵称的真名映射；实时作用于所有分享展示。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    nickname = (request.form.get("nickname") or "").strip()[:100]
    realname = (request.form.get("realname") or "").strip()[:100]
    if not nickname:
        return jsonify(error="nickname required"), 400
    names = load_wxnames(user)
    if realname:
        names[nickname] = realname
    else:
        names.pop(nickname, None)
    mark_self = request.form.get("self", "")
    if mark_self == "1":
        names["__self__"] = nickname  # 标记该昵称为机主（发出方）
    elif mark_self == "0" and names.get("__self__") == nickname:
        names.pop("__self__", None)
    p = _wxnames_path(user)
    tmp = DATA / (p.name + ".tmp")
    tmp.write_text(json.dumps(names, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, p)
    print("wxchat name set by", user, ":", nickname, "->", realname or "(clear)", flush=True)
    return jsonify(saved=True, names=names)


_ocr_lock = threading.Lock()
_ocr_running = set()


def wxchat_kick_ocr(sid):
    """异步解析该分享的图片/PDF 附件；幂等（进行中不重复起线程，已完成的跳过）。

    配置了 SER8 glm-vision 时，全部待解析附件合并一批提交（吃满服务端并发）；
    未配置时退回直连 GLM 逐个解析。
    """
    with _ocr_lock:
        if sid in _ocr_running:
            return
        _ocr_running.add(sid)

    def run():
        try:
            sh = load_share(sid)
            if sh is None or sh.get("type") != "wxchat":
                return
            fdir = DATA / (sid + ".wxfiles")
            fdir_resolved = fdir.resolve()
            jobs = []  # (att, path, ext)
            for att in sh["attachments"]:
                if att.get("ocr_status") == "done":
                    continue
                ext = PurePosixPath(att["name"]).suffix.lower()
                if ext not in _IMG_EXT and ext != ".pdf":
                    continue
                if "path" not in att:
                    continue
                path = (fdir / att["path"]).resolve()
                if not str(path).startswith(str(fdir_resolved)) or not path.exists():
                    continue
                if ext != ".pdf" and att.get("size", 0) > OCR_MAX_FILE_MB * 1024 * 1024:
                    att["ocr_status"] = "skipped"
                    continue
                att["ocr_status"] = "pending"
                jobs.append((att, path, ext))
            if jobs:
                save_share(sh)
                if CFG.get("vision_base_url") and CFG.get("vision_api_key"):
                    try:
                        _vision_extract_batch(jobs, save=lambda: save_share(sh))
                    except Exception as e:
                        for att, _p, _e in jobs:  # 引擎级异常：未收尾的附件统一按失败收尾
                            if att.get("ocr_status") == "pending":
                                att["ocr_status"] = "failed"
                                att["ocr_error"] = "批量解析异常: %s" % e
                        save_share(sh)
                else:
                    for att, path, ext in jobs:
                        try:
                            text, err = _ocr_extract(path, ext)
                        except Exception as e:
                            text, err = None, "解析异常: %s" % e
                        if text:
                            att["ocr"] = text
                            att["ocr_status"] = "done"
                        else:
                            att["ocr_status"] = "failed"
                            att["ocr_error"] = err
                        save_share(sh)
            print("wxchat ocr done:", sid, flush=True)
        except Exception as e:
            print("wxchat ocr error:", sid, e, flush=True)
        finally:
            with _ocr_lock:
                _ocr_running.discard(sid)

    threading.Thread(target=run, daemon=True).start()


def _wx_safe_name(name):
    return re.sub(r'[\\/:*?"<>|]', "_", name)[:180]


def _wx_file_sha1(path):
    """附件字节指纹：图片/视频追加判重用。微信每次导出的媒体文件序号名会变，不能按文件名比对。"""
    import hashlib
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 256), b""):
            h.update(chunk)
    return h.hexdigest()


@app.post("/api/asr/wxchat")
def upload_wxchat():
    """上传微信聊天记录 zip：解析→存附件→建 30 天分享，同步返回分享链接。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    f = request.files.get("file")
    if f is None or not f.filename:
        return jsonify(error="缺少 file 文件"), 400
    display_name = (request.form.get("display_name") or "").strip()[:200]
    title = display_name or Path(f.filename).stem or "聊天记录"
    sid = uuid.uuid4().hex[:10]
    tmp = DATA / (sid + ".wxzip.tmp")
    f.save(tmp)
    attachments = []
    try:
        chat, others = wxchat_parse_zip(tmp, title)
        attachments = list(chat["attachments"])
        fdir = DATA / (sid + ".wxfiles")
        fdir.mkdir(exist_ok=True)
        with zipfile.ZipFile(tmp) as zf:
            base_to_info = {}
            for info in zf.infolist():
                if info.is_dir():
                    continue
                base = PurePosixPath(zip_entry_name(info).replace("\\", "/")).name
                base_to_info.setdefault(base, info)
            plan = []
            for a in attachments:
                info = base_to_info.get(a["name"])
                if info is not None:
                    plan.append((a, info))
            for base, info in others:
                a = {"name": base, "size": info.file_size, "unreferenced": True}
                attachments.append(a)
                plan.append((a, info))
            for idx, (a, info) in enumerate(plan):
                safe = "f%d_%s" % (idx, _wx_safe_name(a["name"]))
                with zf.open(info) as fin, open(fdir / safe, "wb") as fout:
                    shutil.copyfileobj(fin, fout)
                a["path"] = safe
    except zipfile.BadZipFile:
        tmp.unlink(missing_ok=True)
        return jsonify(error="不是有效的 zip 文件"), 400
    except ValueError as e:
        tmp.unlink(missing_ok=True)
        shutil.rmtree(DATA / (sid + ".wxfiles"), ignore_errors=True)
        return jsonify(error=str(e)), 400
    finally:
        tmp.unlink(missing_ok=True)
    names = load_wxnames(user)
    # 真名快照：生成时固化显示名，此后映射改动只影响新分享
    chat["name_display"] = {n: names.get(n, n) for n in chat["participants"]}
    pwd = "".join(secrets.choice(PWD_ALPHABET) for _ in range(PWD_LENGTH))
    share = {"share_id": sid, "pwd": pwd, "type": "wxchat",
             "owner": user, "created_at": time.time(),
             "expires_at": time.time() + WXCHAT_HOURS * 3600,
             "validity_hours": WXCHAT_HOURS, "max_opens": 0, "opens": 0,
             "filename": title, "chat": chat, "attachments": attachments}
    save_share(share)
    print("wxchat saved by", user, ":", sid, len(chat["messages"]), "msgs,",
          len(attachments), "attachments", flush=True)
    wxchat_kick_ocr(sid)
    return jsonify(share_id=sid, pwd=pwd,
                   url="%s/%s?pwd=%s" % (SHARE_BASE, sid, pwd),
                   validity_hours=WXCHAT_HOURS, expires_at=share["expires_at"],
                   participants=chat["participants"])


@app.post("/api/asr/wxchat/<sid>/append")
def append_wxchat(sid):
    """向已有聊天记录分享追加一个 zip：消息按时间稳定归并，附件续编，链接与提取码不变。
    自动去重：文本/文件类消息按 发送者+时间+内容 判重（重叠转存的同句话不再重复入库）；
    图片/视频文本均为[图片]标记，须同分钟且附件字节指纹(SHA-1)一致才判重，防止误删同分钟的不同图。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    sh = _wxchat_check(sid)
    if sh is None or sh.get("owner") != user:
        return jsonify(error="not found"), 404
    f = request.files.get("file")
    if f is None or not f.filename:
        return jsonify(error="缺少 file 文件"), 400
    tmp = DATA / (sid + ".wxzip.tmp")
    f.save(tmp)
    new_atts = []
    try:
        chat, others = wxchat_parse_zip(tmp, "")  # 标题沿用已有记录，不取 zip 名
        new_atts = list(chat["attachments"])
        fdir = DATA / (sid + ".wxfiles")
        fdir.mkdir(exist_ok=True)
        with zipfile.ZipFile(tmp) as zf:
            base_to_info = {}
            for info in zf.infolist():
                if info.is_dir():
                    continue
                base = PurePosixPath(zip_entry_name(info).replace("\\", "/")).name
                base_to_info.setdefault(base, info)
            plan = []
            for a in new_atts:
                info = base_to_info.get(a["name"])
                if info is not None:
                    plan.append((a, info))
            for base, info in others:
                a = {"name": base, "size": info.file_size, "unreferenced": True}
                new_atts.append(a)
                plan.append((a, info))
            base_idx = len(sh["attachments"])  # 文件名序号接在已有附件之后，不会相撞
            for off, (a, info) in enumerate(plan):
                safe = "f%d_%s" % (base_idx + off, _wx_safe_name(a["name"]))
                with zf.open(info) as fin, open(fdir / safe, "wb") as fout:
                    shutil.copyfileobj(fin, fout)
                a["path"] = safe
    except zipfile.BadZipFile:
        tmp.unlink(missing_ok=True)
        return jsonify(error="不是有效的 zip 文件"), 400
    except ValueError as e:
        tmp.unlink(missing_ok=True)
        return jsonify(error=str(e)), 400
    finally:
        tmp.unlink(missing_ok=True)
    old_chat = sh["chat"]
    old_n = len(old_chat["messages"])
    fdir = DATA / (sid + ".wxfiles")

    # ---- 追加去重：与旧记录重叠的消息不再重复入库 ----
    old_text_keys = {(m.get("sender"), m.get("time"), m.get("text"))
                     for m in old_chat["messages"]
                     if m.get("type") not in ("image", "video")}
    old_av_hashes = {}
    for m in old_chat["messages"]:
        if m.get("type") not in ("image", "video"):
            continue
        h = None
        atts = sh["attachments"]
        if "att" in m and m["att"] < len(atts) and "path" in atts[m["att"]]:
            p = fdir / atts[m["att"]]["path"]
            if p.exists():
                h = _wx_file_sha1(p)
                atts[m["att"]].setdefault("sha1", h)  # 缓存指纹，下次追加直接复用
        old_av_hashes.setdefault((m.get("sender"), m.get("time")), set()).add(h)
    kept, skipped = [], 0
    for m in chat["messages"]:
        if m.get("type") in ("image", "video"):
            h = None
            if "att" in m and m["att"] < len(new_atts) and "path" in new_atts[m["att"]]:
                p = fdir / new_atts[m["att"]]["path"]
                if p.exists():
                    h = _wx_file_sha1(p)
            if h is not None and h in old_av_hashes.get((m.get("sender"), m.get("time")), set()):
                skipped += 1
                continue
        elif (m.get("sender"), m.get("time"), m.get("text")) in old_text_keys:
            skipped += 1
            continue
        kept.append(m)
    # 判重后不再被引用的附件不入库，已落盘的孤儿文件一并删除（未引用媒体照旧保留展示）
    # 此处消息 att 仍是本次 zip 内的局部下标（0..len(new_atts)-1），去重完再平移到全局
    used = {m["att"] for m in kept if "att" in m}
    final_atts, remap = [], {}
    for i, a in enumerate(new_atts):
        if a.get("unreferenced") or i in used:
            remap[i] = base_idx + len(final_atts)
            final_atts.append(a)
        elif "path" in a:
            (fdir / a["path"]).unlink(missing_ok=True)
    for m in kept:
        if "att" in m:
            m["att"] = remap[m["att"]]

    # 稳定排序：同分钟消息保持两段各自原有的相对次序
    merged = old_chat["messages"] + kept
    merged.sort(key=lambda m: m.get("time") or "")
    times = [m["time"] for m in merged if m.get("time")]
    old_chat["messages"] = merged
    old_chat["message_count"] = len(merged)
    old_chat["time_start"] = times[0] if times else None
    old_chat["time_end"] = times[-1] if times else None
    for p in chat["participants"]:
        if p not in old_chat["participants"]:
            old_chat["participants"].append(p)
    names = load_wxnames(user)
    display = old_chat.get("name_display")
    if not isinstance(display, dict):
        display = {n: names.get(n, n) for n in old_chat["participants"]}
    for p in old_chat["participants"]:
        if p not in display:
            display[p] = names.get(p, p)
    old_chat["name_display"] = display
    sh["attachments"] = (sh.get("attachments") or []) + final_atts
    # 追加说明记录仍在使用：有效期从本次起重新计 30 天（链接与提取码不变）
    sh["expires_at"] = time.time() + WXCHAT_HOURS * 3600
    save_share(sh)
    added = len(kept)
    print("wxchat appended by", user, ":", sid, "+%d msgs" % added,
          "-%d dups" % skipped, "+%d attachments" % len(final_atts), flush=True)
    wxchat_kick_ocr(sid)
    return jsonify(share_id=sid, pwd=sh["pwd"], appended=True, added_messages=added,
                   skipped_duplicates=skipped, message_count=len(merged),
                   url="%s/%s?pwd=%s" % (SHARE_BASE, sid, sh["pwd"]),
                   validity_hours=WXCHAT_HOURS, expires_at=sh["expires_at"],
                   participants=old_chat["participants"])


def _wxchat_check(sid):
    """sid/分享/类型校验，返回 share 或 None。"""
    if not re.fullmatch(r"[0-9a-f]{6,32}", sid):
        return None
    sh = load_share(sid)
    if sh is None or sh.get("type") != "wxchat":
        return None
    return sh


def _wxchat_pwd_ok(sh, pwd):
    return str(pwd or "").strip().upper() == str(sh["pwd"]).upper()


# ---------------------------------------------------------------- 资料夹管理接口

@app.get("/api/asr/folders")
def folder_list():
    """我的文件夹列表（设置页两个管理页签顶部的资料夹入口）。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    items = []
    for p in DATA.glob("*.folder.json"):
        try:
            fd = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if fd.get("owner") != user:
            continue
        all_items = fd.get("items") or []
        alive = sum(1 for it in all_items if folder_entry_view(it)["alive"])
        items.append({"folder_id": fd["folder_id"], "name": fd.get("name") or "未命名文件夹",
                      "item_count": len(all_items), "alive_count": alive,
                      "created_at": fd.get("created_at"),
                      # 成员明细（轻量 kind+ref）：APP 据此把已归档条目从根目录列表隐藏
                      "items": [{"kind": it.get("kind"), "ref": str(it.get("ref") or "")}
                                for it in all_items]})
    items.sort(key=lambda x: x.get("created_at") or 0, reverse=True)
    return jsonify(folders=items)


@app.post("/api/asr/folders")
def folder_create():
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    j = request.get_json(silent=True) or {}
    name = str(j.get("name") or "").strip()[:100]
    if not name:
        return jsonify(error="文件夹名不能为空"), 400
    if _count_folders(user) >= FOLDER_MAX_COUNT:
        return jsonify(error="文件夹数量已达上限（%d个）" % FOLDER_MAX_COUNT), 400
    fid = uuid.uuid4().hex[:10]
    save_folder({"folder_id": fid, "name": name, "owner": user,
                 "created_at": time.time(), "items": []})
    print("folder created by", user, ":", fid, name, flush=True)
    return jsonify(folder_id=fid, name=name)


@app.get("/api/asr/folders/<fid>")
def folder_detail(fid):
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    fd = _folder_owned(fid, user)
    if fd is None:
        return jsonify(error="not found"), 404
    entries = []
    for i, it in enumerate(fd.get("items") or []):
        v = folder_entry_view(it)
        v["index"] = i
        v["added_at"] = it.get("added_at")
        entries.append(v)
    return jsonify(folder_id=fid, name=fd.get("name"),
                   created_at=fd.get("created_at"), entries=entries)


@app.post("/api/asr/folders/<fid>/rename")
def folder_rename(fid):
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    fd = _folder_owned(fid, user)
    if fd is None:
        return jsonify(error="not found"), 404
    j = request.get_json(silent=True) or {}
    name = str(j.get("name") or "").strip()[:100]
    if not name:
        return jsonify(error="文件夹名不能为空"), 400
    fd["name"] = name
    save_folder(fd)
    # 已生成的文件夹分享标题同步（share.filename 仅作展示兜底，目录页实时读文件夹名）
    print("folder renamed by", user, ":", fid, "->", name, flush=True)
    return jsonify(renamed=True, name=name)


@app.delete("/api/asr/folders/<fid>")
def folder_delete(fid):
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    fd = _folder_owned(fid, user)
    if fd is None:
        return jsonify(error="not found"), 404
    (DATA / (str(fid) + ".folder.json")).unlink(missing_ok=True)
    print("folder deleted by", user, ":", fid, flush=True)
    return jsonify(deleted=True)


@app.post("/api/asr/folders/<fid>/items")
def folder_add_item(fid):
    """把录音转写任务或聊天记录分享加入文件夹（去重）。body: {"kind","ref"}"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    fd = _folder_owned(fid, user)
    if fd is None:
        return jsonify(error="not found"), 404
    j = request.get_json(silent=True) or {}
    kind = str(j.get("kind") or "").strip()
    ref = str(j.get("ref") or "").strip()
    err = _member_owned(kind, ref, user)
    if err:
        return jsonify(error=err), 404
    items = fd.get("items") or []
    if any(it.get("kind") == kind and str(it.get("ref")) == ref for it in items):
        return jsonify(added=False, message="已在该文件夹中")
    if len(items) >= FOLDER_MAX_ITEMS:
        return jsonify(error="单个文件夹最多%d条" % FOLDER_MAX_ITEMS), 400
    # move 语义：一条记录只归属一个文件夹，先从其他夹移出
    moved_from = _detach_from_other_folders(user, fd["folder_id"], kind, ref)
    items.append({"kind": kind, "ref": ref, "added_at": time.time()})
    fd["items"] = items
    save_folder(fd)
    return jsonify(added=True, item_count=len(items), moved_from=moved_from)


@app.post("/api/asr/folders/<fid>/items/remove")
def folder_remove_item(fid):
    """把条目移出文件夹（不删除成员本体）。body: {"kind","ref"}"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    fd = _folder_owned(fid, user)
    if fd is None:
        return jsonify(error="not found"), 404
    j = request.get_json(silent=True) or {}
    kind = str(j.get("kind") or "").strip()
    ref = str(j.get("ref") or "").strip()
    items = fd.get("items") or []
    kept = [it for it in items
            if not (it.get("kind") == kind and str(it.get("ref")) == ref)]
    if len(kept) == len(items):
        return jsonify(error="条目不在该文件夹中"), 404
    fd["items"] = kept
    save_folder(fd)
    return jsonify(removed=True, item_count=len(kept))


@app.post("/api/asr/folders/<fid>/items/batch")
def folder_add_items_batch(fid):
    """批量加入条目（列表多选移入 / 文件夹内挑选添加共用）。
    body: {"items":[{"kind","ref"},...]} → {added, skipped, failed:[{kind,ref,error}]}"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    fd = _folder_owned(fid, user)
    if fd is None:
        return jsonify(error="not found"), 404
    j = request.get_json(silent=True) or {}
    raw = j.get("items")
    if not isinstance(raw, list) or not raw:
        return jsonify(error="items 不能为空"), 400
    if len(raw) > FOLDER_MAX_ITEMS:
        return jsonify(error="单次最多%d条" % FOLDER_MAX_ITEMS), 400
    items = fd.get("items") or []
    seen = {(it.get("kind"), str(it.get("ref"))) for it in items}
    added, skipped, moved_cnt = 0, 0, 0
    failed = []
    for it in raw:
        if not isinstance(it, dict):
            continue
        kind = str(it.get("kind") or "").strip()
        ref = str(it.get("ref") or "").strip()
        key = (kind, ref)
        if key in seen:
            skipped += 1
            continue
        err = _member_owned(kind, ref, user)
        if err:
            failed.append({"kind": kind, "ref": ref, "error": err})
            continue
        if len(items) >= FOLDER_MAX_ITEMS:
            failed.append({"kind": kind, "ref": ref, "error": "文件夹已满"})
            continue
        # move 语义：一条记录只归属一个文件夹，先从其他夹移出
        moved_cnt += len(_detach_from_other_folders(user, fd["folder_id"], kind, ref))
        items.append({"kind": kind, "ref": ref, "added_at": time.time()})
        seen.add(key)
        added += 1
    fd["items"] = items
    save_folder(fd)
    return jsonify(added=added, skipped=skipped, failed=failed,
                   moved_from_others=moved_cnt, item_count=len(items))


@app.post("/api/asr/folders/<fid>/share")
def folder_create_share(fid):
    """为文件夹生成聚合分享：一个链接一张提取码，接收方目录浏览、逐条查看。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    fd = _folder_owned(fid, user)
    if fd is None:
        return jsonify(error="not found"), 404
    if not (fd.get("items") or []):
        return jsonify(error="文件夹为空，先加入录音或聊天记录"), 400
    try:
        hours = int(request.form.get("validity_hours", SHARE_DEFAULT_HOURS))
        max_opens = int(request.form.get("max_opens", SHARE_DEFAULT_MAX_OPENS))
    except (TypeError, ValueError):
        return jsonify(error="validity_hours / max_opens 参数错误"), 400
    if not (1 <= hours <= 24 * 60):
        return jsonify(error="有效期需在1小时~60天之间"), 400
    if not (0 <= max_opens <= 100000):
        return jsonify(error="可打开次数参数错误"), 400
    sid = uuid.uuid4().hex[:10]
    pwd = "".join(secrets.choice(PWD_ALPHABET) for _ in range(PWD_LENGTH))
    share = {"share_id": sid, "pwd": pwd, "type": "folder", "folder_id": fid,
             "owner": user, "created_at": time.time(),
             "expires_at": time.time() + hours * 3600,
             "validity_hours": hours, "max_opens": max_opens, "opens": 0,
             "filename": fd.get("name") or "资料夹"}
    save_share(share)
    print("folder shared by", user, ":", fid, "->", sid, flush=True)
    return jsonify(share_id=sid, pwd=pwd,
                   url="%s/%s?pwd=%s" % (SHARE_BASE, sid, pwd),
                   validity_hours=hours, max_opens=max_opens,
                   expires_at=share["expires_at"])


@app.get("/api/asr/wxchat/list")
def wxchat_list():
    """我的聊天记录转存分享列表（设置页管理入口）。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    now = time.time()
    items = []
    for p in DATA.glob("*.share.json"):
        try:
            sh = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if sh.get("type") != "wxchat" or sh.get("owner") != user:
            continue
        if now > sh.get("expires_at", 0):
            continue
        chat = sh.get("chat") or {}
        participants = chat.get("participants") or []
        snapshot = chat.get("name_display")
        if isinstance(snapshot, dict):
            disp_list = [snapshot.get(n, n) for n in participants]
        else:
            names = load_wxnames(sh.get("owner", ""))
            disp_list = [names.get(n, n) for n in participants]
            chat["name_display"] = {n: disp_list[i] for i, n in enumerate(participants)}
            save_share(sh)
        # 附件解析状态聚合（none=无可解析附件；pending 时顺手兜底补跑）
        ocr_done = ocr_pending = ocr_failed = ocr_total = 0
        for a in sh.get("attachments") or []:
            st = a.get("ocr_status")
            if st not in ("done", "pending", "failed", "skipped"):
                continue
            ocr_total += 1
            if st == "done":
                ocr_done += 1
            elif st == "pending":
                ocr_pending += 1
            elif st == "failed":
                ocr_failed += 1
        if ocr_total == 0:
            ocr_state = "none"
        elif ocr_pending:
            ocr_state = "pending"
            wxchat_kick_ocr(sh["share_id"])  # 服务重启丢掉的解析，刷列表时复活
        elif ocr_failed == ocr_total:
            ocr_state = "failed"
        elif ocr_failed:
            ocr_state = "partial"
        else:
            ocr_state = "done"
        items.append({
            "share_id": sh["share_id"], "pwd": sh["pwd"],
            "title": sh.get("filename") or "聊天记录",
            "message_count": chat.get("message_count", 0),
            "participant_count": len(participants),
            "participants": participants,
            "participants_display": disp_list,
            "ocr_state": ocr_state, "ocr_done": ocr_done, "ocr_total": ocr_total,
            "attachment_count": len(sh.get("attachments") or []),
            "created_at": sh.get("created_at"),
            "expires_at": sh.get("expires_at"),
            "url": "%s/%s?pwd=%s" % (SHARE_BASE, sh["share_id"], sh["pwd"]),
        })
    items.sort(key=lambda x: x.get("created_at") or 0, reverse=True)
    return jsonify(shares=items)


@app.delete("/api/asr/wxchat/<sid>")
def wxchat_delete(sid):
    """删除我的聊天记录分享：删分享记录与附件目录。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    sh = _wxchat_check(sid)
    if sh is None or sh.get("owner") != user:
        return jsonify(error="not found"), 404
    (DATA / (sid + ".share.json")).unlink(missing_ok=True)
    shutil.rmtree(DATA / (sid + ".wxfiles"), ignore_errors=True)
    print("wxchat share deleted by", user, ":", sid, flush=True)
    return jsonify(deleted=True)


@app.post("/api/asr/wxchat/<sid>/rename")
def wxchat_rename(sid):
    """重命名我的聊天记录分享：分享页标题与 Agent JSON 的 title 同步生效。"""
    user = authenticate()
    if user is None:
        return jsonify(error="unauthorized"), 401
    sh = _wxchat_check(sid)
    if sh is None or sh.get("owner") != user:
        return jsonify(error="not found"), 404
    j = request.get_json(silent=True) or {}
    title = str(j.get("title") or "").strip()[:200]
    if not title:
        return jsonify(error="标题不能为空"), 400
    sh["filename"] = title
    sh["chat"]["title"] = title
    save_share(sh)
    print("wxchat renamed by", user, ":", sid, "->", title, flush=True)
    return jsonify(renamed=True, title=title)


@app.get("/s/<sid>/chat.json")
def wxchat_json(sid):
    """Agent 阅读端点：结构化聊天记录 JSON，链接自带提取码即可访问，不计打开次数。"""
    sh = _wxchat_check(sid)
    if sh is None:
        return jsonify(error="not found"), 404
    if not _wxchat_pwd_ok(sh, request.args.get("pwd")):
        return jsonify(error="forbidden"), 403
    # nginx 反代后 Flask 只见 http，但站点仅经 https 暴露，附件链接统一 https
    root = request.host_url.replace("http://", "https://").rstrip("/")
    chat = json.loads(json.dumps(sh["chat"], ensure_ascii=False))  # 深拷贝再补 URL
    wxchat_kick_ocr(sid)  # 服务重启丢掉的解析在这里兜底补跑
    names = load_wxnames(sh.get("owner", ""))
    realnames = chat.get("name_display")
    if not isinstance(realnames, dict):
        # 旧分享无快照：惰性固化当前映射
        realnames = {n: names.get(n, n) for n in chat.get("participants") or []}
        chat["name_display"] = realnames
        save_share(sh)
    self_nick = names.get("__self__")
    if self_nick and self_nick not in (chat.get("participants") or []):
        self_nick = None
    for m in chat["messages"]:
        m["realname"] = realnames.get(m["sender"], m["sender"])
        m["is_self"] = bool(self_nick and m["sender"] == self_nick)
        if "att" in m and m["att"] < len(sh["attachments"]):
            a = sh["attachments"][m["att"]]
            if "path" in a:
                m["file_url"] = "%s/s/%s/file/%d?pwd=%s" % (root, sid, m["att"], sh["pwd"])
            if "ocr" in a:
                m["ocr_text"] = a["ocr"]  # 消息层直带解析结果，agent 读一条即得
            elif a.get("ocr_status") == "pending":
                m["ocr_status"] = "pending"
    atts = []
    for i, a in enumerate(sh["attachments"]):
        item = {"name": a["name"], "size": a["size"],
                "ocr_status": a.get("ocr_status", "none")}
        if "path" in a:
            item["url"] = "%s/s/%s/file/%d?pwd=%s" % (root, sid, i, sh["pwd"])
        if "ocr" in a:
            item["ocr"] = a["ocr"]
        atts.append(item)
    chat.pop("attachments")  # 用补好 URL 的 atts 替代原始附件表
    return jsonify(title=chat.pop("title"), source=chat.pop("source"),
                   attachments=atts, realnames=realnames, self_nickname=self_nick,
                   expires_at=sh["expires_at"], **chat)


@app.get("/s/<sid>/file/<int:idx>")
def wxchat_file(sid, idx):
    """聊天记录附件下载：需提取码；不计打开次数。"""
    sh = _wxchat_check(sid)
    if sh is None:
        return jsonify(error="not found"), 404
    if not _wxchat_pwd_ok(sh, request.args.get("pwd")):
        return jsonify(error="forbidden"), 403
    if not (0 <= idx < len(sh["attachments"])):
        return jsonify(error="not found"), 404
    att = sh["attachments"][idx]
    if "path" not in att:
        return jsonify(error="附件不存在"), 404
    fdir = DATA / (sid + ".wxfiles")
    p = (fdir / att["path"]).resolve()
    if not str(p).startswith(str(fdir.resolve())) or not p.exists():
        return jsonify(error="附件不存在"), 404
    mime = mimetypes.guess_type(att["name"])[0] or "application/octet-stream"
    return send_file(p, mimetype=mime, as_attachment=False, download_name=att["name"])


CHAT_PAGE_CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{background:#ededed;font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue','PingFang SC','Microsoft YaHei',sans-serif}
.wrap{max-width:680px;margin:0 auto;padding:16px 12px 40px}
h1{font-size:19px;color:#111;margin-bottom:6px;word-break:break-all}
.meta{font-size:12px;color:#888;margin-bottom:12px}
.bar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}
.bar button,.bar a{font-size:13px;padding:7px 14px;border-radius:6px;border:1px solid #d0d0d0;background:#fff;color:#333;text-decoration:none;cursor:pointer}
.chat{background:#ededed}
.mrow{display:flex;gap:8px;margin-bottom:12px;align-items:flex-start}
.avatar{flex:none;width:38px;height:38px;border-radius:5px;color:#fff;font-size:17px;line-height:38px;text-align:center;font-weight:500}
.mcol{min-width:0;max-width:78%}
.mlabel{font-size:12px;color:#999;margin-bottom:3px}
.mlabel .mtime{margin-left:8px}
.bubble{position:relative;background:#fff;border-radius:4px;padding:9px 12px;font-size:15px;color:#111;line-height:1.5;white-space:pre-wrap;word-break:break-word;display:inline-block}
.bubble:before{content:'';position:absolute;left:-5px;top:12px;width:10px;height:10px;background:#fff;transform:rotate(45deg);border-radius:2px}
.bubble.tagonly{color:#666}
.fcard{display:flex;align-items:center;gap:10px;text-decoration:none;color:#111;min-width:200px}
.ficon{flex:none;width:38px;height:38px;border-radius:4px;background:#5b7bd5;color:#fff;font-size:18px;line-height:38px;text-align:center}
.fname{font-size:14px;word-break:break-all;max-width:230px}
.fsize{font-size:12px;color:#999;margin-top:2px}
.fdl{font-size:12px;color:#576b95;margin-top:4px;display:block}
.others{margin-top:22px;padding:12px;background:#f7f7f7;border-radius:8px}
.others h2{font-size:14px;color:#555;margin-bottom:8px}
.others li{font-size:13px;color:#333;margin:4px 0 0 18px;word-break:break-all}
.others a{color:#576b95;text-decoration:none}
.bubble.media{padding:4px}
.pic{max-width:100%;max-height:320px;border-radius:4px;display:block}
.piclink{display:block;cursor:zoom-in}
#viewer{position:fixed;left:0;top:0;width:100%;height:100%;background:rgba(0,0,0,.93);z-index:99;display:none;align-items:center;justify-content:center}
#viewimg{max-width:96vw;max-height:96vh;object-fit:contain}
.mrow.self{flex-direction:row-reverse}
.mcol.self{display:flex;flex-direction:column;align-items:flex-end}
.mcol.self .mlabel{align-self:flex-end}
.bubble.self{background:#95ec69}
.bubble.self:before{left:auto;right:-5px;background:#95ec69}
.ocrwrap{width:100%}
.ocrbtn{display:inline-block;font-size:12px;color:#576b95;margin-top:6px;cursor:pointer}
.ocr{background:#f7f7f7;border-radius:6px;padding:8px;font-size:12px;line-height:1.5;white-space:pre-wrap;word-break:break-word;max-height:320px;overflow-y:auto;margin-top:4px;color:#333}
#toast{display:none;position:fixed;left:50%;bottom:60px;transform:translateX(-50%);background:rgba(0,0,0,.75);color:#fff;font-size:13px;padding:8px 16px;border-radius:6px;z-index:9}
.bubble.fwd{background:transparent;padding:0;max-width:100%}
.bubble.fwd:before{display:none}
.fwdcard{background:#f7f6f2;border:1px solid #e6e3dc;border-radius:8px;padding:10px 12px 8px;max-width:430px}
.fwdhead{font-size:13px;font-weight:600;color:#444}
.fwdnames{font-size:12px;color:#999;margin-top:3px}
.fwdrow{margin-top:8px}
.fwdlabel{font-size:11px;color:#9a9a9a;margin-bottom:2px}
.fwdtime{margin-left:8px;font-size:11px}
.fwdbubble{background:#fff;border-radius:4px;padding:7px 10px;font-size:13px;line-height:1.55;color:#111;white-space:pre-wrap;word-break:break-word;display:inline-block;max-width:100%}
.fwdbubble .pic{max-height:220px}
"""

CHAT_RENDER_JS = r"""
var PALETTE=["#7cb342","#5c6bc0","#ef5350","#f4b400","#26a69a","#ab47bc","#42a5f5","#ff7043"];
function esc(s){var d=document.createElement("div");d.textContent=s==null?"":String(s);return d.innerHTML;}
function hsize(n){if(n>=1048576)return (n/1048576).toFixed(1)+" MB";if(n>=1024)return (n/1024).toFixed(0)+" KB";return n+" B";}
var TIME_RE=/^(?:\d{4}年\d{1,2}月\d{1,2}日\s+)?\d{1,2}:\d{2}$/;
function parseRecord(text){
  var lines=text.replace(/\r\n/g,"\n").split("\n");
  var subs=[],cur=null;
  for(var i=1;i<lines.length;i++){
    var t=lines[i].replace(/^\s+|\s+$/g,"");
    if(!t){if(cur)cur.b.push("");continue;}
    if(t.charAt(0)==="·"&&i+1<lines.length&&TIME_RE.test(lines[i+1].replace(/^\s+|\s+$/g,""))){
      cur={s:t.substring(1).replace(/^\s+|\s+$/g,""),t:lines[i+1].replace(/^\s+|\s+$/g,""),b:[]};
      subs.push(cur);i++;continue;
    }
    if(cur)cur.b.push(t);
  }
  return subs;
}
function recMedia(line){
  var mm=line.match(/^\[(图片|视频|文件)\]\s*(\S+)\s*$/);
  if(!mm)return null;
  var ai=NMEDIA[mm[2]];if(ai==null)return null;
  var url='/s/'+SID+'/file/'+ai+'?pwd='+encodeURIComponent(PWD);
  if(mm[1]==="文件")return '<div class="fcard"><div class="ficon">📄</div><div><div class="fname">'+esc(mm[2])+'</div><a class="fdl" href="'+url+'">下载</a></div></div>';
  if(mm[1]==="视频")return '<video class="pic" src="'+url+'" controls preload="metadata"></video>';
  return '<a class="piclink" onclick="showPic(this)" data-full="'+url+'"><img class="pic" src="'+url+'" loading="lazy"></a><a class="fdl" href="'+url+'">下载</a>';
}
function renderRecord(text){
  var subs=parseRecord(text);
  var names=[],seen={};
  for(var i=0;i<subs.length;i++){var dn=RNAMES[subs[i].s]||subs[i].s;if(!seen[dn]){seen[dn]=1;names.push(dn);}}
  var h='<div class="fwdcard"><div class="fwdhead">💬 转发的聊天记录</div>';
  if(names.length)h+='<div class="fwdnames">发送人：'+esc(names.join("、"))+'</div>';
  for(var i=0;i<subs.length;i++){
    var sb=subs[i],body="";
    for(var k=0;k<sb.b.length;k++){
      var med=recMedia(sb.b[k]);
      if(med)body+=(body?"<br>":"")+med;
      else if(sb.b[k]==="[聊天记录]")body+=(body?"<br>":"")+'<span class="tagonly">[内含更深层聊天记录，未导出]</span>';
      else body+=(body?"<br>":"")+esc(sb.b[k]);
    }
    if(!body)body="&nbsp;";
    h+='<div class="fwdrow"><div class="fwdlabel">'+esc(RNAMES[sb.s]||sb.s)+'<span class="fwdtime">'+esc(sb.t)+'</span></div><div class="fwdbubble">'+body+'</div></div>';
  }
  return h+'</div>';
}
var cmap={},ci=0;
for(var i=0;i<CHAT.messages.length;i++){var s=CHAT.messages[i].sender;if(!(s in cmap))cmap[s]=ci++;}
var h="";
for(var i=0;i<CHAT.messages.length;i++){
  var m=CHAT.messages[i];
  var color=PALETTE[cmap[m.sender]%PALETTE.length];
  var dn=RNAMES[m.sender]||m.sender;
  var isSelf=SELF_NICK&&m.sender===SELF_NICK;
  var isRec=m.type==="text"&&(m.text||"").replace(/^\s+/,"").indexOf("[聊天记录]")===0;
  var rowSelf=isSelf&&!isRec;
  var av=esc(dn.substring(0,1));
  var inner;
  if(m.type==="file"){
    var dl=m.att!=null?'<a class="fdl" href="/s/'+SID+'/file/'+m.att+'?pwd='+encodeURIComponent(PWD)+'">下载</a>':"";
    inner='<div class="fcard"><div class="ficon">📄</div><div><div class="fname">'+esc(m.text)+'</div><div class="fsize">'+(m.size?hsize(m.size):"文件")+'</div>'+dl+'</div></div>';
  }else if(m.type==="image"){
    var src=m.att!=null?'/s/'+SID+'/file/'+m.att+'?pwd='+encodeURIComponent(PWD):"";
    inner=src?'<a class="piclink" onclick="showPic(this)" data-full="'+src+'"><img class="pic" src="'+src+'" loading="lazy"></a><a class="fdl" href="'+src+'">下载</a>':'<span class="tagonly">[图片]</span>';
  }else if(m.type==="video"){
    var vsrc=m.att!=null?'/s/'+SID+'/file/'+m.att+'?pwd='+encodeURIComponent(PWD):"";
    inner=vsrc?'<video class="pic" src="'+vsrc+'" controls preload="metadata"></video>':'<span class="tagonly">[视频]</span>';
  }else if(m.type==="voice"){
    inner='<span class="tagonly">[语音]</span>';
  }else if(isRec){inner=renderRecord(m.text||"");}
  else{inner=esc(m.text);}
  var ocrExtra="";
  if(m.att!=null&&OCR[m.att]){
    var A=OCR[m.att];
    if(A.ocr){ocrExtra='<div class="ocrwrap"><a class="ocrbtn" onclick="toggleOcr(this)">查看解析结果</a><pre class="ocr" style="display:none">'+esc(A.ocr)+'</pre></div>';}
    else if(A.ocr_status==="pending"){ocrExtra='<div class="ocrwrap"><a class="ocrbtn" style="color:#999;pointer-events:none">解析中，稍后刷新可见</a></div>';}
  }
  h+='<div class="mrow'+(rowSelf?' self':'')+'"><div class="avatar" style="background:'+color+'">'+av+'</div>'
    +'<div class="mcol'+(rowSelf?' self':'')+'"><div class="mlabel"><span class="mname">'+esc(dn)+'</span>'
    +'<span class="mtime">'+esc(m.time||"")+'</span></div><div class="bubble'
    +(rowSelf?' self':"")+(isRec?' fwd':(((m.type==="image"||m.type==="video")&&m.att!=null)?" media":(m.type!=="text"&&m.type!=="file"?" tagonly":"")))+'">'+inner+'</div>'+ocrExtra+'</div></div>';
}
document.getElementById("chat").innerHTML=h;
var _tt=null;
function toast(m){var t=document.getElementById("toast");t.textContent=m;t.style.display="block";if(_tt)clearTimeout(_tt);_tt=setTimeout(function(){t.style.display="none";},1600);}
function legacyCopy(t){var ta=document.createElement("textarea");ta.value=t;ta.style.position="fixed";ta.style.opacity="0";document.body.appendChild(ta);ta.focus();ta.select();try{document.execCommand("copy");}catch(e){}document.body.removeChild(ta);}
function copyText(t,ok){if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t).then(ok,function(){legacyCopy(t);ok();});}else{legacyCopy(t);ok();}}
function copyAll(){var t="";for(var i=0;i<CHAT.messages.length;i++){var m=CHAT.messages[i];t+="["+m.time+"] "+m.sender+": "+m.text+"\n";}copyText(t,function(){toast("复制成功");});}
function toggleOcr(el){var p=el.nextElementSibling;var show=p.style.display==="none";p.style.display=show?"block":"none";el.textContent=show?"收起解析结果":"查看解析结果";}
var viewer=document.createElement("div");viewer.id="viewer";viewer.innerHTML='<img id="viewimg" alt="">';viewer.onclick=function(){viewer.style.display="none";};document.body.appendChild(viewer);
function showPic(a){document.getElementById("viewimg").src=a.getAttribute("data-full");viewer.style.display="flex";history.pushState({v:1},"");}
window.onpopstate=function(){if(viewer.style.display==="flex")viewer.style.display="none";};
"""


def wxchat_share_page(sh, sid):
    """微信聊天记录分享页：原生风格的左侧气泡流 + 附件卡片 + Agent JSON 入口。"""
    wxchat_kick_ocr(sid)  # 服务重启丢掉的解析在这里兜底补跑
    chat = sh["chat"]
    names = load_wxnames(sh.get("owner", ""))
    self_nick = names.get("__self__")
    if self_nick and self_nick not in chat["participants"]:
        self_nick = None
    display = chat.get("name_display")
    if not isinstance(display, dict):
        # 旧分享无快照：惰性固化当前映射，此后映射改动不再影响该分享
        display = {n: names.get(n, n) for n in chat["participants"]}
        chat["name_display"] = display
        save_share(sh)
    disp_parts = [display.get(n, n) for n in chat["participants"]]
    parts = "、".join(disp_parts[:8]) + ("等" if len(disp_parts) > 8 else "")
    meta = "%d 条消息 · %s · %s ~ %s · 轻舟浏览器转存" % (
        chat["message_count"], parts,
        chat.get("time_start") or "", chat.get("time_end") or "")
    # 嵌套转发记录正文里点名的媒体（微信对记录内图片写「[图片] 文件名」）按名对号：
    # 页面把命中项内嵌渲染进记录卡片，不再落入「其他附件」
    att_by_name = {a["name"]: i for i, a in enumerate(sh["attachments"])}
    nested_media = {}
    for m in chat["messages"]:
        txt = m.get("text") or ""
        if m.get("type") == "text" and txt.lstrip().startswith("[聊天记录]"):
            for mo in re.finditer(r"\[(?:图片|视频|文件)\]\s*([^\s\n]+)", txt):
                if mo.group(1) in att_by_name:
                    nested_media.setdefault(mo.group(1), att_by_name[mo.group(1)])
    nested_media_js = json.dumps(nested_media, ensure_ascii=False).replace("</", "<\\/")
    others = [(i, a) for i, a in enumerate(sh["attachments"])
              if a.get("unreferenced") and a["name"] not in nested_media]
    others_html = ""
    if others:
        items = "".join(
            '<li>%s（%s）<a href="/s/%s/file/%d?pwd=%s">下载</a></li>'
            % (html.escape(a["name"]), _human_size(a["size"]), sid, i, sh["pwd"])
            for i, a in others)
        others_html = ('<div class="others"><h2>其他附件（消息中未直接引用）</h2><ul>%s</ul></div>'
                       % items)
    # worker 只更新顶层 attachments，chat 内嵌副本不自动携带，页面从 OCRMAP 取
    ocr_map = {}
    for i, a in enumerate(sh["attachments"]):
        if a.get("ocr") or a.get("ocr_status"):
            ocr_map[i] = {"ocr": a.get("ocr"), "ocr_status": a.get("ocr_status", "none")}
    ocr_js = json.dumps(ocr_map, ensure_ascii=False).replace("</", "<\\/")
    chat_js = json.dumps(chat, ensure_ascii=False).replace("</", "<\\/")
    body = ('<div class="wrap"><h1>%s</h1><div class="meta">%s</div>'
            '<div class="bar">'
            '<button onclick="copyAll()">复制全文</button>'
            '<a href="/s/%s/chat.json?pwd=%s">Agent 数据（JSON）</a>'
            '</div><div class="chat" id="chat"></div>%s'
            '<div id="toast"></div>'
            '<style>%s</style>'
            '<script>var CHAT=%s;var OCR=%s;var RNAMES=%s;var SELF_NICK=%s;var SID="%s";var PWD=%s;var NMEDIA=%s;</script>'
            '<script>%s</script></body></html>'
            % (html.escape(chat["title"]), html.escape(meta), sid, sh["pwd"],
               others_html, CHAT_PAGE_CSS, chat_js, ocr_js,
               json.dumps(display, ensure_ascii=False).replace("</", "<\\/"),
               json.dumps(self_nick), sid,
               json.dumps(str(sh["pwd"])), nested_media_js, CHAT_RENDER_JS))
    return ("<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            "<title>%s</title></head><body>%s"
            % (html.escape(chat["title"]), body))


# ---------------------------------------------------------------- 资料夹分享页（目录+逐条查看）

FOLDER_CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{background:#f2f1ec;font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue','PingFang SC','Microsoft YaHei',sans-serif}
.fwrap{max-width:680px;margin:0 auto;padding:22px 14px 44px}
.fwrap h1{font-size:19px;color:#111;margin-bottom:6px;word-break:break-all}
.fmeta{font-size:12px;color:#888;margin-bottom:16px}
.flist{display:flex;flex-direction:column;gap:8px}
.fitem{display:flex;align-items:center;gap:12px;background:#fff;border-radius:10px;padding:13px 14px;text-decoration:none;color:#111;box-shadow:0 1px 2px rgba(0,0,0,.04)}
.fitem.dead{opacity:.62}
.fico{flex:none;width:38px;height:38px;border-radius:8px;background:#eef3fb;display:flex;align-items:center;justify-content:center;font-size:19px}
.fmain{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px}
.ftitle{font-size:15px;color:#111;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.fsub{font-size:12px;color:#8a8a8a;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.farrow{flex:none;color:#c0c0c0;font-size:20px}
.fbar{margin-top:18px;display:flex;gap:8px;flex-wrap:wrap}
.fbar a,.fbar button{font-size:13px;padding:7px 14px;border-radius:6px;border:1px solid #d0d0d0;background:#fff;color:#333;text-decoration:none;cursor:pointer}
.fback{display:inline-block;font-size:13px;color:#576b95;background:#fff;border:1px solid #d0d0d0;border-radius:6px;padding:6px 12px;text-decoration:none;margin-bottom:12px}
"""

_FOLDER_BACK = ('<a class="fback" href="/s/%s?pwd=%s">‹ 返回目录</a>')


def _folder_share_checked(sid, sh):
    """条目页公共校验：返回 (folder, error_html, code)。error_html 非 None 时直接返回。"""
    if not re.fullmatch(r"[0-9a-f]{6,32}", sid):
        return None, (render_page("错误", "", "<div class=\"gate\">链接不存在或已过期</div>"), 404)
    if int(sh.get("max_opens") or 0) and int(sh.get("opens") or 0) >= int(sh.get("max_opens")):
        return None, (render_page("错误", "",
                                  "<div class=\"gate\">打开次数已用完（上限 %d 次）</div>"
                                  % int(sh.get("max_opens"))), 200)
    fd = load_folder(sh.get("folder_id") or "")
    if fd is None:
        return None, (render_page("错误", "",
                                  "<div class=\"gate\">文件夹已被删除，链接失效</div>"), 200)
    return fd, None


def folder_share_page(sh, sid):
    """资料夹聚合分享目录页：条目清单，点开逐条查看；打开计数在本页完成。"""
    fd = load_folder(sh.get("folder_id") or "")
    if fd is None:
        return render_page(sh.get("filename") or "资料夹", "",
                           "<div class=\"gate\">文件夹已被删除，链接失效</div>"), 200
    max_opens = int(sh.get("max_opens") or 0)
    opens = int(sh.get("opens") or 0)
    if max_opens and opens >= max_opens:
        return render_page("错误", "",
                           "<div class=\"gate\">打开次数已用完（上限 %d 次）</div>" % max_opens), 200
    sh["opens"] = opens + 1
    save_share(sh)
    rows, alive = [], 0
    for i, it in enumerate(fd.get("items") or []):
        v = folder_entry_view(it)
        if v["alive"]:
            alive += 1
            rows.append(
                '<a class="fitem" href="/s/%s/item/%d?pwd=%s">'
                '<span class="fico">%s</span>'
                '<span class="fmain"><span class="ftitle">%s</span>'
                '<span class="fsub">%s</span></span>'
                '<span class="farrow">›</span></a>'
                % (sid, i, sh["pwd"],
                   "🎙" if v["kind"] == "task" else "💬",
                   html.escape(v["title"]), html.escape(v["meta"])))
        else:
            rows.append(
                '<div class="fitem dead"><span class="fico">⚠️</span>'
                '<span class="fmain"><span class="ftitle">%s</span>'
                '<span class="fsub">%s</span></span></div>'
                % (html.escape(v.get("title") or "条目已失效"),
                   html.escape(v.get("error") or "")))
    name = fd.get("name") or "资料夹"
    meta = "%d 个条目（%d 个有效） · 有效至 %s · 轻舟浏览器转存" % (
        len(rows), alive,
        time.strftime("%Y-%m-%d %H:%M", time.localtime(sh.get("expires_at") or 0)))
    body = ('<style>%s</style><div class="fwrap"><h1>📁 %s</h1><div class="fmeta">%s</div>'
            '<div class="flist">%s</div>'
            '<div class="fbar"><a href="/s/%s/folder.json?pwd=%s">Agent 数据（JSON）</a></div>'
            '</div>' % (FOLDER_CSS, html.escape(name), html.escape(meta),
                        "".join(rows), sid, sh["pwd"]))
    return render_page(name, "", body)


@app.get("/s/<sid>/item/<int:idx>")
def folder_item_route(sid, idx):
    """资料夹分享的单条目页：kind=task 渲染转写视图，kind=wxchat 渲染气泡流。"""
    if not re.fullmatch(r"[0-9a-f]{6,32}", sid):
        return render_page("错误", "", "<div class=\"gate\">链接不存在或已过期</div>"), 404
    sh = load_share(sid)
    if sh is None or sh.get("type") != "folder":
        return render_page("错误", "", "<div class=\"gate\">链接不存在或已过期</div>"), 404
    if request.args.get("pwd", "").strip().upper() != str(sh["pwd"]).upper():
        return render_gate(sid, request.args.get("pwd", "") != ""), 200
    fd, err = _folder_share_checked(sid, sh)
    if err is not None:
        return err
    items = fd.get("items") or []
    if not (0 <= idx < len(items)):
        return render_page("错误", "", "<div class=\"gate\">条目不存在</div>"), 404
    item = items[idx]
    if item.get("kind") == "task":
        return folder_item_task_page(sh, sid, idx, item)
    if item.get("kind") == "wxchat":
        return folder_item_wxchat_page(sh, sid, idx, item)
    return render_page("错误", "", "<div class=\"gate\">未知条目类型</div>"), 404


def folder_item_wxchat_page(sh, sid, idx, item):
    """聊天记录条目：直接复用原分享的气泡流渲染（附件/图片走原分享端点，不重复鉴权逻辑）。
    Agent JSON 入口改指文件夹的 folder.json——整夹内容一个提取码贯通。"""
    wsh = load_share(str(item.get("ref") or ""))
    if wsh is None or wsh.get("type") != "wxchat":
        body = ('<style>%s</style><div class="gate">该聊天记录分享已过期或已删除'
                '<div style="margin-top:14px">%s</div></div>'
                % (FOLDER_CSS, _FOLDER_BACK % (sid, sh["pwd"])))
        return render_page("条目已失效", "", body), 200
    page = wxchat_share_page(wsh, str(item["ref"]))
    # 页内 Agent 入口改为文件夹级 folder.json（原为原分享的 chat.json）
    page = page.replace('/s/%s/chat.json?pwd=%s' % (item["ref"], wsh["pwd"]),
                        '/s/%s/folder.json?pwd=%s' % (sid, sh["pwd"]))
    return page.replace('<div class="wrap">',
                        '<div class="wrap">' + _FOLDER_BACK % (sid, sh["pwd"]), 1)


def folder_item_task_page(sh, sid, idx, item):
    """录音/视频转写条目：与单条分享页同构（双标签/回听/译文切换），音频切片走文件夹端点。"""
    tid = str(item.get("ref") or "")
    st = load_task(tid)
    if st is None or "result" not in st:
        body = ('<style>%s</style><div class="gate">该转写结果已失效'
                '<div style="margin-top:14px">%s</div></div>'
                % (FOLDER_CSS, _FOLDER_BACK % (sid, sh["pwd"])))
        return render_page("条目已失效", "", body), 200
    result = st["result"]
    opts = st.get("options", {})
    allow_play = bool(sh.get("allow_segment_play", True))
    show_names = bool(sh.get("show_speaker_names", True))

    def segs_js(key):
        arr = result.get(key) or []
        return json.dumps(arr, ensure_ascii=False).replace("</", "<\\/")

    names_public = {}
    if show_names:
        for k, v in (result.get("speaker_names") or {}).items():
            names_public[k] = {kk: vv for kk, vv in v.items() if kk != "embedding"}
    names_js = json.dumps(names_public, ensure_ascii=False).replace("</", "<\\/")

    total_ms = result.get("duration_ms") or 0
    total_sec = int(total_ms / 1000)
    dur_text = ("%d分%02d秒" % (total_sec // 60, total_sec % 60)) if total_sec else ""
    meta = []
    if dur_text:
        meta.append("时长 " + dur_text)
    if opts.get("diarize"):
        meta.append("区分说话人")
    if result.get("mandarin_segments"):
        meta.append("方言已译普通话")
    if not (DATA / (tid + ".audio")).exists():
        meta.append("原音已过期，不可回听")
    meta_html = " · ".join(meta)
    has_mand = 1 if result.get("mandarin_segments") else 0

    summary = str(result.get("summary") or "")
    todos = result.get("todos") or []
    ai_inner = ""
    if summary or todos:
        parts = []
        if summary:
            parts.append('<div class="aih">AI 摘要</div><div class="aitxt">%s</div>'
                         % html.escape(summary))
        if todos:
            titems = ""
            for t in todos:
                extra = ""
                if t.get("owner") or t.get("due"):
                    extra = '<span class="meta">　%s · %s</span>' % (
                        html.escape(t.get("owner") or "未指定"),
                        html.escape(t.get("due") or "未指定"))
                titems += '<li><label><input type="checkbox">%s%s</label></li>' % (
                    html.escape(t.get("task", "")), extra)
            parts.append('<div class="aih">待办事项</div><ul class="todos">%s</ul>' % titems)
        ai_inner = ('<div class="ai"><button onclick="copyAI()">复制摘要与待办</button>'
                    + "".join(parts) + "</div>")
    elif not result.get("summary_done"):
        ai_inner = '<div class="meta" style="margin:6px 0 10px">AI 摘要与待办生成中，请稍后刷新页面查看…</div>'
        threading.Thread(target=generate_summary_todos, args=(st,), daemon=True).start()
    has_ai = 1 if ai_inner else 0
    ai_tab = '<div class="tab" onclick="switchTab(1,this)">AI 摘要 · 待办</div>' if has_ai else ""
    ai_page = ('<div id="page1" style="display:none">' + ai_inner + "</div>") if has_ai else ""
    summary_js = json.dumps(summary, ensure_ascii=False).replace("</", "<\\/")
    todos_js = json.dumps(todos, ensure_ascii=False).replace("</", "<\\/")

    title = st.get("display_name") or st.get("filename") or "转写结果"
    body = ("<div class=\"wrap\">%s<h1>%s</h1><div class=\"meta\">%s · 轻舟浏览器转写</div>"
            "<div class=\"tabs\">"
            "<div class=\"tab active\" onclick=\"switchTab(0,this)\">识别结果</div>"
            "%s"
            "</div>"
            "<div id=\"page0\">"
            "<button id=\"tg\" style=\"display:%s\">查看原文</button>"
            "<button onclick=\"copyAll()\">复制全文</button>"
            "%s"
            "<div id=\"content\"></div>"
            "</div>"
            "%s"
            "</div>"
            "<div id=\"toast\"></div>"
            "<script>"
            "var PWD=%s;var SID='%s';var ITEM=%d;var ORIG=%s;var MAND=%s;var NAMES=%s;var HAS_TS=%d;var HAS_SPK=%d;var CAN_PLAY=%d;var SHOW_NAME=%d;var SUMMARY=%s;var TODOS=%s;var mand=false;"
            "var colors=['s0','s1','s2','s3'];"
            "function spkInfo(id){return NAMES[String(id)]||null;}"
            "function spkLabel(id){if(!HAS_SPK)return '';"
            "var n=SHOW_NAME?spkInfo(id):null;"
            "if(n&&n.name){return n.gender?n.name+'（'+n.gender+'）':n.name;}"
            "var cmap=window.__cmap||{};var idx=(cmap[String(id)]!==undefined)?cmap[String(id)]:Number(id);"
            "return '说话人'+(idx+1);}"
            "function spkColor(id){var cmap=window.__cmap||{};var i=cmap[String(id)];"
            "return (i===undefined)?colors[0]:colors[i %% colors.length];}"
            "function esc(s){var d=document.createElement('div');d.textContent=s;return d.innerHTML;}"
            "var _tt=null;function toast(m){var t=document.getElementById('toast');t.textContent=m;"
            "t.style.display='block';if(_tt)clearTimeout(_tt);_tt=setTimeout(function(){t.style.display='none';},1600);}"
            "function legacyCopy(t){var ta=document.createElement('textarea');ta.value=t;"
            "ta.style.position='fixed';ta.style.opacity='0';document.body.appendChild(ta);ta.focus();ta.select();"
            "try{document.execCommand('copy');}catch(e){}document.body.removeChild(ta);}"
            "function copyText(t,ok){"
            "if(navigator.clipboard&&navigator.clipboard.writeText){"
            "navigator.clipboard.writeText(t).then(ok,function(){legacyCopy(t);ok();});}else{legacyCopy(t);ok();}}"
            "function switchTab(n,el){var tabs=document.querySelectorAll('.tab');"
            "for(var i=0;i<tabs.length;i++){tabs[i].classList.remove('active');}"
            "el.classList.add('active');"
            "document.getElementById('page0').style.display=(n===0)?'block':'none';"
            "var p1=document.getElementById('page1');if(p1)p1.style.display=(n===1)?'block':'none';}"
            "function render(){var arr=mand&&MAND?MAND:ORIG;var h='';"
            "var cmap={};var ci=0;"
            "for(var i=0;i<arr.length;i++){var sid=String(arr[i].speaker);"
            "if(!(sid in cmap)){cmap[sid]=ci++;}}"
            "window.__cmap=cmap;"
            "for(var i=0;i<arr.length;i++){var s=arr[i];var lab=[];"
            "var sl=spkLabel(s.speaker);if(sl)lab.push('<span class=\"'+spkColor(s.speaker)+'\">'+esc(sl)+'</span>');"
            "if(HAS_TS)lab.push(esc(s.begin+' - '+s.end));"
            "h+='<div class=\"seg\"'+(CAN_PLAY?' onclick=\"segClick(this)\"':'')+' data-a=\"'+s.begin_ms+'\" data-b=\"'+s.end_ms+'\">'+(lab.length?'<div class=\"label\">'+lab.join('   ')+'</div>':'')+'<div class=\"txt\">'+esc(s.text)+'</div></div>';}"
            "document.getElementById('content').innerHTML=h;"
            "document.getElementById('tg').textContent=mand?'查看原文':'查看普通话译文';"
            "window.__cur=arr;}"
            "function copyAll(){var arr=window.__cur||ORIG;var t='';"
            "for(var i=0;i<arr.length;i++){var s=arr[i];var sl=spkLabel(s.speaker);"
            "t+=(sl?'['+sl+']':'')+(HAS_TS?'['+s.begin+'-'+s.end+']':'')+s.text+'\\n';}"
            "copyText(t,function(){toast('复制成功');});}"
            "function copyAI(){var t='【AI 摘要】'+SUMMARY+'\\n';"
            "if(TODOS&&TODOS.length){t+='\\n【待办事项】\\n';"
            "for(var i=0;i<TODOS.length;i++){var d=TODOS[i];t+=(i+1)+'. '+d.task;"
            "if(d.owner&&d.owner!=='未指定')t+='（'+d.owner+'）';"
            "if(d.due&&d.due!=='未指定')t+=' ['+d.due+']';t+='\\n';}}"
            "copyText(t,function(){toast('复制成功');});}"
            "var audioEl=null,curSeg=null;"
            "function segClick(el){var a=el.getAttribute('data-a'),b=el.getAttribute('data-b');"
            "if(!audioEl){audioEl=new Audio();}"
            "var A=audioEl;"
            "if(curSeg===el){if(A.paused){A.play();}else{A.pause();}return;}"
            "if(curSeg){curSeg.classList.remove('play');}"
            "curSeg=el;el.classList.add('play');"
            "A.src='/s/'+SID+'/item/'+ITEM+'/audio?a='+a+'&b='+b+'&pwd='+encodeURIComponent(PWD);"
            "A.onended=function(){if(curSeg)curSeg.classList.remove('play');};"
            "A.onerror=function(){var s=document.getElementById('audstat');if(s)s.textContent='本段音频无法回放（音频已过期或不存在）';};"
            "A.play();}"
            "document.getElementById('tg').onclick=function(){mand=!mand;render();};"
            "render();</script>"
            % (_FOLDER_BACK % (sid, sh["pwd"]),
               html.escape(title), html.escape(meta_html),
               ai_tab,
               "inline-block" if has_mand else "none",
               ('<div id="audstat" class="meta" style="margin-bottom:12px">'
                '点击任意段落可回听对应原音</div>') if allow_play else "",
               ai_page,
               json.dumps(str(sh["pwd"])), sid, idx,
               segs_js("segments"), segs_js("mandarin_segments"), names_js,
               1 if opts.get("timestamps") else 0, 1 if opts.get("diarize") else 0,
               1 if allow_play else 0, 1 if show_names else 0,
               summary_js, todos_js))
    return render_page(title, meta_html, body)


@app.get("/s/<sid>/item/<int:idx>/audio")
def folder_item_audio(sid, idx):
    """资料夹条目的段落回听：从文件夹定位到对应任务音频后切片，鉴权走文件夹提取码。"""
    if not re.fullmatch(r"[0-9a-f]{6,32}", sid):
        return jsonify(error="not found"), 404
    sh = load_share(sid)
    if sh is None or sh.get("type") != "folder":
        return jsonify(error="not found"), 404
    if request.args.get("pwd", "").strip().upper() != str(sh["pwd"]).upper():
        return jsonify(error="forbidden"), 403
    if int(sh.get("max_opens") or 0) and int(sh.get("opens") or 0) > int(sh.get("max_opens")):
        return jsonify(error="打开次数已用完"), 403
    fd = load_folder(sh.get("folder_id") or "")
    items = (fd or {}).get("items") or []
    if not (0 <= idx < len(items)) or items[idx].get("kind") != "task":
        return jsonify(error="not found"), 404
    tid = str(items[idx]["ref"])
    st = load_task(tid)
    audio = DATA / (tid + ".audio")
    if st is None or "result" not in st or not audio.exists():
        return jsonify(error="音频已过期或不存在"), 404
    try:
        a = max(0, int(request.args.get("a", "0")))
        b = int(request.args.get("b", "0"))
    except ValueError:
        return jsonify(error="参数错误"), 400
    if b <= a:
        return jsonify(error="参数错误"), 400
    if b - a > MAX_SLICE_MS:
        b = a + MAX_SLICE_MS
    total_ms = int((st.get("result") or {}).get("duration_ms") or 0)
    if total_ms and b > total_ms:
        b = total_ms
    if b <= a:
        return jsonify(error="参数错误"), 400
    try:
        p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error",
                            "-ss", "%.3f" % (a / 1000.0), "-i", str(audio),
                            "-t", "%.3f" % ((b - a) / 1000.0),
                            "-vn", "-acodec", "libmp3lame", "-b:a", "48k",
                            "-f", "mp3", "pipe:1"],
                           capture_output=True, timeout=120)
    except subprocess.TimeoutExpired:
        return jsonify(error="音频切片超时"), 504
    if p.returncode != 0 or not p.stdout:
        return jsonify(error="音频切片失败"), 500
    return Response(p.stdout, mimetype="audio/mpeg",
                    headers={"Cache-Control": "private, max-age=86400",
                             "Accept-Ranges": "none"})


@app.get("/s/<sid>/folder.json")
def folder_json(sid):
    """Agent 阅读端点：文件夹内全部条目的结构化 JSON（转写全文/分段/摘要/待办、
    聊天消息/真名/附件直链），一个提取码贯通全部内容。"""
    if not re.fullmatch(r"[0-9a-f]{6,32}", sid):
        return jsonify(error="not found"), 404
    sh = load_share(sid)
    if sh is None or sh.get("type") != "folder":
        return jsonify(error="not found"), 404
    if request.args.get("pwd", "").strip().upper() != str(sh["pwd"]).upper():
        return jsonify(error="forbidden"), 403
    fd = load_folder(sh.get("folder_id") or "")
    if fd is None:
        return jsonify(error="文件夹已被删除"), 404
    root = request.host_url.replace("http://", "https://").rstrip("/")
    out = []
    for i, item in enumerate(fd.get("items") or []):
        v = folder_entry_view(item)
        if not v["alive"]:
            out.append({"index": i, "kind": v.get("kind"), "ref": v.get("ref"),
                        "error": v.get("error")})
            continue
        if v["kind"] == "task":
            st = load_task(str(item["ref"]))
            r = st["result"]
            entry = {"index": i, "kind": "task", "ref": item["ref"],
                     "title": v["title"],
                     "created_at": st.get("created_at"),
                     "duration_ms": r.get("duration_ms"),
                     "options": st.get("options"),
                     "text": r.get("text"),
                     "segments": r.get("segments"),
                     "summary": r.get("summary") or None,
                     "todos": r.get("todos") or None,
                     "audio_available": (DATA / (str(item["ref"]) + ".audio")).exists(),
                     "item_audio": "%s/s/%s/item/%d/audio?pwd=%s" % (root, sid, i, sh["pwd"])}
            if r.get("mandarin_segments"):
                entry["mandarin_segments"] = r["mandarin_segments"]
            names = {}
            for k, nm in (r.get("speaker_names") or {}).items():
                names[k] = {kk: vv for kk, vv in nm.items() if kk != "embedding"}
            if names:
                entry["speaker_names"] = names
            out.append(entry)
        else:
            wsh = load_share(str(item["ref"]))
            chat = json.loads(json.dumps(wsh["chat"], ensure_ascii=False))
            wxchat_kick_ocr(str(item["ref"]))
            names = load_wxnames(wsh.get("owner", ""))
            realnames = chat.get("name_display")
            if not isinstance(realnames, dict):
                realnames = {n: names.get(n, n) for n in chat.get("participants") or []}
            self_nick = names.get("__self__")
            if self_nick and self_nick not in (chat.get("participants") or []):
                self_nick = None
            atts = wsh.get("attachments") or []
            for m in chat.get("messages") or []:
                m["realname"] = realnames.get(m.get("sender"), m.get("sender"))
                m["is_self"] = bool(self_nick and m.get("sender") == self_nick)
                att_i = m.get("att")
                if isinstance(att_i, int) and 0 <= att_i < len(atts):
                    a = atts[att_i]
                    if "path" in a:
                        m["file_url"] = "%s/s/%s/file/%d?pwd=%s" % (
                            root, item["ref"], att_i, wsh["pwd"])
                    if "ocr" in a:
                        m["ocr_text"] = a["ocr"]
            att_out = []
            for ai2, a in enumerate(atts):
                d = {"name": a.get("name"), "size": a.get("size"),
                     "ocr_status": a.get("ocr_status", "none")}
                if "path" in a:
                    d["url"] = "%s/s/%s/file/%d?pwd=%s" % (root, item["ref"], ai2, wsh["pwd"])
                if "ocr" in a:
                    d["ocr"] = a["ocr"]
                att_out.append(d)
            entry = {"index": i, "kind": "wxchat", "ref": item["ref"],
                     "title": chat.get("title") or v["title"],
                     "source": chat.get("source", "wechat"),
                     "participants": chat.get("participants"),
                     "realnames": realnames, "self_nickname": self_nick,
                     "message_count": chat.get("message_count"),
                     "time_start": chat.get("time_start"),
                     "time_end": chat.get("time_end"),
                     "messages": chat.get("messages"),
                     "attachments": att_out,
                     "expires_at": wsh.get("expires_at")}
            out.append(entry)
    return jsonify(type="folder", title=fd.get("name"), share_id=sid,
                   created_at=sh.get("created_at"), expires_at=sh.get("expires_at"),
                   item_count=len(out), items=out)


def _human_size(n):
    if n >= 1048576:
        return "%.1f MB" % (n / 1048576)
    if n >= 1024:
        return "%d KB" % (n // 1024)
    return "%d B" % n


def wxchat_purge_loop():
    """聊天记录附件目录清理：分享过期 7 天后整目录删除（与分享记录清理节奏一致）。"""
    while True:
        try:
            now = time.time()
            for p in DATA.glob("*.wxfiles"):
                sid = p.name[:-len(".wxfiles")]
                sh = load_share(sid)
                if sh is None or now > sh.get("expires_at", 0) + 7 * 86400:
                    shutil.rmtree(p, ignore_errors=True)
                    print("purged expired wxfiles:", p.name, flush=True)
        except Exception as e:
            print("wxchat purge error:", e, flush=True)
        time.sleep(3600)


if __name__ == "__main__":
    users = load_users()
    default_owner = sorted(set(users.values()))[0] if users else "用户A"
    for p in DATA.glob("*.json"):
        if p.name.endswith(".share.json"):
            continue
        try:
            st = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(st, dict) or "task_id" not in st:
            continue  # 非任务文件（如映射库）不参与迁移
        changed = False
        if st.get("status") in ("PENDING", "RUNNING"):
            st["status"] = "FAILED"
            st["error"] = "服务重启中断"
            changed = True
        if not st.get("owner"):
            st["owner"] = default_owner  # 历史任务归入首位用户
            changed = True
        if changed:
            save_task(st)
    threading.Thread(target=audio_purge_loop, daemon=True).start()
    threading.Thread(target=wxchat_purge_loop, daemon=True).start()
    app.run(host="127.0.0.1", port=CFG.get("port", 8720), threaded=True)
