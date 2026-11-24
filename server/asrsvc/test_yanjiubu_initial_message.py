#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""轻舟「研究部一键绑定」开场文字转发回归（回归套件名：yanjiubu_initial_message）。

用法：python test_yanjiubu_initial_message.py   （无需外网/真实密钥）

沙箱式运行（同 test_wxchat_append.py 惯例）：把 app.py 复制进临时目录、配最小
config/users 与一个**本地桩平台**（http.server 记录请求体并回预置 JSON），再用
Flask test_client 直测 `/api/asr/folders/<fid>/yanjiubu/bind` 的：
  · 不填文字 → 不向研究部发 initial_message 字段；
  · 填了文字 → 原样（去首尾空白）转发；
  · 纯空白 → 等同不填；
  · 非字符串 / 超 6000 字 → 本地 400，且**不请求研究部**（不产生副作用）；
  · 研究部返回 initial_message 状态 → 原样回给 APP。
"""
import importlib.util
import json
import shutil
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOKEN = "tok-e2e-yj-kickoff-0001"
USER = "tester"
FID = "d269027467"
MAX_CHARS = 6000

pass_n = 0
fail_n = 0


def check(name, ok, detail=""):
    global pass_n, fail_n
    if ok:
        pass_n += 1
        print("✅ %s" % name)
    else:
        fail_n += 1
        print("❌ %s %s" % (name, detail))


class StubPlatform:
    """本地桩：记录每次请求（path/头/体），按预设回 200 + success。"""

    def __init__(self):
        self.requests = []
        self.reply = {"success": True, "data": {"ok": True, "already_bound": False,
                                                "folder": {"id": "f-1", "name": "创新le源自动化诉讼案"},
                                                "agent": {"state": "creating"},
                                                "initial_message": {"scheduled": True, "chars": 4}}}
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length).decode("utf-8") if length else ""
                try:
                    body = json.loads(raw) if raw else None
                except ValueError:
                    body = raw
                outer.requests.append({
                    "path": self.path,
                    "bridge_token": self.headers.get("X-Bridge-Token"),
                    "body": body,
                })
                payload = json.dumps(outer.reply, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


def make_sandbox(port):
    root = Path(tempfile.mkdtemp(prefix="yj_kickoff_e2e_"))
    shutil.copy2(HERE / "app.py", root / "app.py")
    (root / "config.json").write_text(json.dumps({
        "token": "unused",
        "maas_host": "https://maas.invalid",
        "api_key": "k",
        "share_base_url": "http://127.0.0.1:9/s",
        "yanjiubu_base_url": "http://127.0.0.1:%d" % port,
        "yanjiubu_bridge_token": "bridge-token-e2e",
    }, ensure_ascii=False), encoding="utf-8")
    (root / "users.json").write_text(json.dumps(
        {"users": [{"user": USER, "token": TOKEN}]}, ensure_ascii=False), encoding="utf-8")
    (root / "data").mkdir()
    (root / "data" / (FID + ".folder.json")).write_text(json.dumps({
        "folder_id": FID, "name": "创新le源自动化诉讼案", "owner": USER,
        "created_at": 0, "items": [],
    }, ensure_ascii=False), encoding="utf-8")
    spec = importlib.util.spec_from_file_location("asrsvc_app_yj_e2e", root / "app.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules["asrsvc_app_yj_e2e"] = mod
    spec.loader.exec_module(mod)
    return root, mod


def main():
    stub = StubPlatform().start()
    root, mod = make_sandbox(stub.port)
    client = mod.app.test_client()
    url = "/api/asr/folders/%s/yanjiubu/bind" % FID
    headers = {"X-LZ-Token": TOKEN}

    # ① 不填文字（空 body）：仍绑定，但不带 initial_message
    stub.requests.clear()
    r = client.post(url, headers=headers, json={})
    check("空 body → 200", r.status_code == 200, str(r.status_code))
    check("空 body → 请求恰好一次", len(stub.requests) == 1, str(len(stub.requests)))
    sent = stub.requests[0]["body"] or {}
    check("空 body → 不带 initial_message 字段", "initial_message" not in sent, json.dumps(sent, ensure_ascii=False))
    check("转发轻舟夹 id 为小写 hex", sent.get("lightboat_folder_id") == FID, str(sent.get("lightboat_folder_id")))
    check("携带桥接令牌头", stub.requests[0]["bridge_token"] == "bridge-token-e2e",
          str(stub.requests[0]["bridge_token"]))
    body = r.get_json() or {}
    check("响应透传研究的 initial_message 状态",
          (body.get("initial_message") or {}).get("scheduled") is True, json.dumps(body, ensure_ascii=False))

    # ② 填了文字：去首尾空白后原样转发
    stub.requests.clear()
    text = "  先把这夹里的合同逐份梳理，输出要点清单  "
    r = client.post(url, headers=headers, json={"initial_message": text})
    check("带文字 → 200", r.status_code == 200, str(r.status_code))
    sent = stub.requests[0]["body"] or {}
    check("带文字 → 转发去空白后的正文",
          sent.get("initial_message") == text.strip(), repr(sent.get("initial_message")))

    # ③ 纯空白：等同不填（不产生 initial_message）
    stub.requests.clear()
    r = client.post(url, headers=headers, json={"initial_message": "   \n\t "})
    check("纯空白 → 200", r.status_code == 200, str(r.status_code))
    sent = stub.requests[0]["body"] or {}
    check("纯空白 → 不带 initial_message", "initial_message" not in sent, json.dumps(sent, ensure_ascii=False))

    # ④ 非字符串：本地 400，且不请求研究部
    stub.requests.clear()
    r = client.post(url, headers=headers, json={"initial_message": 12345})
    check("非字符串 → 400", r.status_code == 400, str(r.status_code))
    check("非字符串 → 不请求研究部", len(stub.requests) == 0, str(len(stub.requests)))
    check("非字符串 → 错误文案说明类型", "字符串" in (r.get_json() or {}).get("error", ""),
          json.dumps(r.get_json() or {}, ensure_ascii=False))

    # ⑤ 超长：本地 400，且不请求研究部
    stub.requests.clear()
    r = client.post(url, headers=headers, json={"initial_message": "字" * (MAX_CHARS + 1)})
    check("超 6000 字 → 400", r.status_code == 400, str(r.status_code))
    check("超长 → 不请求研究部", len(stub.requests) == 0, str(len(stub.requests)))
    err = (r.get_json() or {}).get("error", "")
    check("超长 → 错误文案带字数", "6000" in err and "6001" in err, err)

    # ⑥ 边界：恰好 6000 字放行
    stub.requests.clear()
    r = client.post(url, headers=headers, json={"initial_message": "字" * MAX_CHARS})
    check("恰好 6000 字 → 200", r.status_code == 200, str(r.status_code))
    sent = stub.requests[0]["body"] or {}
    check("恰好 6000 字 → 正文长度一致", len(sent.get("initial_message") or "") == MAX_CHARS,
          str(len(sent.get("initial_message") or "")))

    # ⑦ 鉴权：错令牌 401，且不请求研究部
    stub.requests.clear()
    r = client.post(url, headers={"X-LZ-Token": "wrong"}, json={"initial_message": "hi"})
    check("错令牌 → 401", r.status_code == 401, str(r.status_code))
    check("错令牌 → 不请求研究部", len(stub.requests) == 0, str(len(stub.requests)))

    # ⑧ 非本人资料夹 → 404，且不请求研究部
    stub.requests.clear()
    r = client.post("/api/asr/folders/aaaaaaaaaa/yanjiubu/bind", headers=headers, json={"initial_message": "hi"})
    check("非本人夹 → 404", r.status_code == 404, str(r.status_code))
    check("非本人夹 → 不请求研究部", len(stub.requests) == 0, str(len(stub.requests)))

    stub.stop()
    shutil.rmtree(root, ignore_errors=True)
    print("\n%d passed, %d failed" % (pass_n, fail_n))
    return 0 if fail_n == 0 else 1


if __name__ == "__main__":
    sys.exit(main())