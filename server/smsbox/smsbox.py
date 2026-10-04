#!/usr/bin/env python3
"""smsbox — 轻舟浏览器 12368 短信自动备份接收端（SER8）。

手机端轻舟 APP 监听到来自 12368 的短信后 POST 到本服务；本服务去重后落 SQLite。
数据盘位 /opt/smsbox/data/sms.db（系统盘，自动进 restic 每 5 分钟快照）。

接口（除 /healthz 外一律要求 X-LZ-Token 头，与 asrsvc 同风格）：
  GET  /healthz                 健康探针（看门狗用，无需令牌）
  GET  /ping                    令牌核验，返回库内总条数
  POST /ingest                  批量上存短信 {"messages":[{sender,body,sent_at}]}
                                → {"added":n,"duplicates":m,"total":t}
  GET  /list?limit=200&q=关键词  最近短信（新→旧），供查看页/调试
  GET  /                        网页查看页（令牌填在页面里，存 localStorage）

去重键 = sha1(sender|sent_at|body)，重复上存幂等；APP 端另有按服务地址隔离的
已传记录，两边去重互为兜底。判重规则（监听哪些号码）在 APP 端做，服务端全收。
"""
import argparse
import hashlib
import hmac
import json
import os
import sqlite3
import sys
import threading
from contextlib import contextmanager
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = "sms.db"
TOKEN_FILE = "token"
MAX_BODY = 2 * 1024 * 1024  # 单次上存上限 2MB，正常一批 100 条远达不到

_db_lock = threading.Lock()
_token_cache = {"value": None, "mtime": 0}


def db_connect():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=8000")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS sms ("
        " id INTEGER PRIMARY KEY AUTOINCREMENT,"
        " sender TEXT NOT NULL,"
        " body TEXT NOT NULL,"
        " sent_at INTEGER NOT NULL,"        # 短信自身时间戳，毫秒
        " received_at TEXT NOT NULL,"        # 服务器落库时间
        " hash TEXT NOT NULL UNIQUE"         # sha1(sender|sent_at|body)
        ")"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_sms_sent_at ON sms(sent_at)")
    return conn


@contextmanager
def db():
    """sqlite3 连接的 with 只管事务不关连接，这里包一层确保用完即关。"""
    conn = db_connect()
    try:
        with conn:
            yield conn
    finally:
        conn.close()


def load_token():
    """令牌文件每次读盘太频，按 mtime 缓存；文件换掉立即生效（轮换令牌无需重启）。"""
    try:
        st = os.stat(TOKEN_FILE)
        if _token_cache["mtime"] != st.st_mtime:
            with open(TOKEN_FILE, "r", encoding="utf-8") as f:
                _token_cache["value"] = f.read().strip()
            _token_cache["mtime"] = st.st_mtime
        return _token_cache["value"] or ""
    except OSError:
        return ""


def check_token(handler):
    got = handler.headers.get("X-LZ-Token", "")
    want = load_token()
    if not want:
        return False, "服务端未配置令牌文件"
    if not got or not hmac.compare_digest(got, want):
        return False, ""
    return True, ""


def normalize_ms(value):
    try:
        v = int(value)
    except (TypeError, ValueError):
        return 0
    if 0 < v < 10**12:  # 秒级时间戳兜底
        v *= 1000
    return v if v > 0 else 0


def ingest_messages(items):
    """逐条落库，返回 (added, duplicates)。单条畸形数据跳过不阻断整批。"""
    added = dup = 0
    now_iso = datetime.now().astimezone().isoformat(timespec="seconds")
    with _db_lock, db() as conn:
        for it in items:
            if not isinstance(it, dict):
                continue
            sender = str(it.get("sender", "")).strip()
            body = str(it.get("body", ""))
            sent_at = normalize_ms(it.get("sent_at", 0))
            if not body:
                continue
            digest = hashlib.sha1(
                f"{sender}|{sent_at}|{body}".encode("utf-8")).hexdigest()
            try:
                conn.execute(
                    "INSERT INTO sms(sender, body, sent_at, received_at, hash)"
                    " VALUES(?,?,?,?,?)",
                    (sender, body, sent_at, now_iso, digest))
                added += 1
            except sqlite3.IntegrityError:
                dup += 1
    return added, dup


PAGE_HTML = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>12368 短信备份</title>
<style>
 body{font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;margin:0;background:#f5f4ef;color:#222}
 header{position:sticky;top:0;background:#fff;border-bottom:1px solid #ddd;padding:10px 14px;z-index:2}
 h1{font-size:16px;margin:0 0 8px}
 .bar{display:flex;gap:6px;flex-wrap:wrap}
 input{padding:6px 8px;border:1px solid #ccc;border-radius:6px;font-size:14px}
 #token{flex:1;min-width:120px}
 button{padding:6px 12px;border:1px solid #1565C0;background:#1565C0;color:#fff;border-radius:6px;font-size:14px;cursor:pointer}
 button.ghost{background:#fff;color:#1565C0}
 #stat{font-size:12px;color:#777;margin-top:6px}
 main{padding:10px 14px 40px}
 .card{background:#fff;border:1px solid #e3e1da;border-radius:8px;padding:10px 12px;margin-bottom:8px}
 .t{font-size:12px;color:#1565C0;margin-bottom:4px}
 .b{font-size:14px;white-space:pre-wrap;word-break:break-word;line-height:1.5}
 .err{color:#b00020}
</style>
</head>
<body>
<header>
 <h1>12368 短信备份（SER8）</h1>
 <div class="bar">
  <input id="token" type="password" placeholder="访问令牌">
  <button onclick="load()">查看</button>
  <button class="ghost" onclick="search()">搜索</button>
 </div>
 <div id="stat"></div>
</header>
<main id="list"></main>
<script>
const $=s=>document.querySelector(s);
$('#token').value=localStorage.getItem('smsbox_token')||'';
function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML;}
function fmt(ms){if(!ms)return '时间未知';const d=new Date(ms);return d.getFullYear()+'-'+
 String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')+' '+
 String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0')+':'+String(d.getSeconds()).padStart(2,'0');}
async function load(){
 const t=$('#token').value.trim();localStorage.setItem('smsbox_token',t);
 const q=$('#kw')?$('#kw').value.trim():'';
 $('#stat').textContent='加载中…';
 try{
  const r=await fetch('/list?limit=500&q='+encodeURIComponent(q),{headers:{'X-LZ-Token':t}});
  if(r.status===401){$('#stat').innerHTML='<span class="err">令牌无效，请重新输入</span>';return;}
  if(!r.ok)throw new Error('HTTP '+r.status);
  const j=await r.json();
  $('#stat').textContent='库内共 '+j.total+' 条'+(q?'，匹配 '+j.messages.length+' 条':'');
  $('#list').innerHTML=j.messages.map(m=>
   '<div class="card"><div class="t">'+esc(m.sender||'12368')+' · '+fmt(m.sent_at)+'</div>'+
   '<div class="b">'+esc(m.body)+'</div></div>').join('')||'<p>没有记录</p>';
 }catch(e){$('#stat').innerHTML='<span class="err">加载失败：'+esc(String(e))+'</span>';}
}
function search(){
 if(!document.querySelector('#kw')){
  const i=document.createElement('input');i.id='kw';i.placeholder='按内容搜索';i.style.flex='1';i.minWidth='120px';
  i.onkeydown=e=>{if(e.key==='Enter')load();};document.querySelector('.bar').prepend(i);
 }
 load();
}
$('#token').addEventListener('keydown',e=>{if(e.key==='Enter')load();});
</script>
</body>
</html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "smsbox/1.0"

    def log_message(self, fmt, *args):  # journald 接管
        sys.stderr.write("[%s] %s\n" % (self.log_date_time_string(), fmt % args))

    # ------------------------------------------------------------ 基础
    def _send_json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length <= 0:
            raise ValueError("空请求体")
        if length > MAX_BODY:
            raise OverflowError("请求体过大")
        raw = self.rfile.read(length)
        return json.loads(raw.decode("utf-8"))

    # ------------------------------------------------------------ 路由
    def do_GET(self):
        path, _, query = self.path.partition("?")
        params = {}
        for pair in query.split("&"):
            if "=" in pair:
                k, _, v = pair.partition("=")
                params[k] = v
        from urllib.parse import unquote
        params = {k: unquote(v) for k, v in params.items()}

        if path == "/healthz":
            with db() as conn:
                total = conn.execute("SELECT COUNT(*) FROM sms").fetchone()[0]
            self._send_json(200, {"ok": True, "total": total})
            return

        if path == "/":
            # 页面壳不设防（不含任何数据）；数据接口 /list /ingest /ping 均需令牌
            data = PAGE_HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        ok, err = check_token(self)
        if not ok:
            self._send_json(401, {"error": err or "令牌无效"})
            return

        if path == "/ping":
            with db() as conn:
                total = conn.execute("SELECT COUNT(*) FROM sms").fetchone()[0]
            self._send_json(200, {"ok": True, "total": total, "user": "默认"})
        elif path == "/list":
            try:
                limit = min(max(int(params.get("limit", "200")), 1), 500)
            except ValueError:
                limit = 200
            q = params.get("q", "").strip()
            sql = "SELECT sender, body, sent_at, received_at FROM sms"
            args = []
            if q:
                sql += " WHERE body LIKE ? OR sender LIKE ?"
                args += ["%" + q + "%", "%" + q + "%"]
            sql += " ORDER BY sent_at DESC LIMIT ?"
            args.append(limit)
            with db() as conn:
                rows = conn.execute(sql, args).fetchall()
                total = conn.execute("SELECT COUNT(*) FROM sms").fetchone()[0]
            msgs = [{"sender": r[0], "body": r[1], "sent_at": r[2],
                     "received_at": r[3]} for r in rows]
            self._send_json(200, {"total": total, "messages": msgs})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        path, _, _ = self.path.partition("?")
        if path != "/ingest":
            self._send_json(404, {"error": "not found"})
            return
        ok, err = check_token(self)
        if not ok:
            self._send_json(401, {"error": err or "令牌无效"})
            return
        try:
            payload = self._read_json()
        except OverflowError:
            self._send_json(413, {"error": "请求体过大"})
            return
        except (ValueError, json.JSONDecodeError) as e:
            self._send_json(400, {"error": "JSON 解析失败: %s" % e})
            return
        items = payload.get("messages") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            self._send_json(400, {"error": "缺少 messages 数组"})
            return
        added, dup = ingest_messages(items)
        with db() as conn:
            total = conn.execute("SELECT COUNT(*) FROM sms").fetchone()[0]
        self._send_json(200, {"added": added, "duplicates": dup, "total": total})


def main():
    global DB_PATH, TOKEN_FILE
    ap = argparse.ArgumentParser(description="12368 短信备份接收端")
    ap.add_argument("--port", type=int, default=8730)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--db", default=None, help="SQLite 路径（默认 ./sms.db）")
    ap.add_argument("--token-file", default=None, help="令牌文件（默认 ./token）")
    args = ap.parse_args()
    if args.db:
        DB_PATH = args.db
    if args.token_file:
        TOKEN_FILE = args.token_file
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)), exist_ok=True)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print("smsbox listening on %s:%d db=%s" % (args.host, args.port, DB_PATH), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
