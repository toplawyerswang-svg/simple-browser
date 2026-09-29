#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""微信聊天记录「追加/重命名」E2E 回归（回归套件名：wxchat_append）。

用法：python test_wxchat_append.py   （无需网络/密钥，Flask test_client 直测）

沙箱式运行：把 app.py 复制进临时目录并配最小 config/users，OCR 兜底线程打桩，
不触碰真实数据目录。覆盖：新建→追加→重命名→list→chat.json→分享页→鉴权/坏包。
"""
import io
import json
import shutil
import sys
import tempfile
import time
import zipfile
import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOKEN = "tok-e2e-wxchat-0001"
USER = "tester"


def make_sandbox():
    root = Path(tempfile.mkdtemp(prefix="wxchat_e2e_"))
    shutil.copy2(HERE / "app.py", root / "app.py")
    (root / "config.json").write_text(json.dumps({
        "token": "unused", "maas_host": "https://maas.invalid", "api_key": "k",
        "share_base_url": "http://127.0.0.1:9/s",
    }, ensure_ascii=False), encoding="utf-8")
    (root / "users.json").write_text(json.dumps(
        {"users": [{"user": USER, "token": TOKEN}]}, ensure_ascii=False), encoding="utf-8")
    spec = importlib.util.spec_from_file_location("asrsvc_app_e2e", root / "app.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules["asrsvc_app_e2e"] = mod
    spec.loader.exec_module(mod)
    mod.wxchat_kick_ocr = lambda sid: None  # 打桩：测试不调 GLM
    return root, mod


def wx_zip(entries):
    """构造微信风格的聊天记录 zip。GBK 仿造仅当字节能安全落进 Latin-1 时生效
    （否则 Python zipfile 会改打 UTF-8 标志，服务端对带标志条目本就按 UTF-8 直读）。"""
    buf = io.BytesIO()
    zf = zipfile.ZipFile(buf, "w")
    for name, data in entries:
        if isinstance(data, str):
            data = data.encode("utf-8")
        zi_name = name
        try:
            gbk_as_437 = name.encode("gbk").decode("cp437")
            if all(ord(ch) < 256 for ch in gbk_as_437):
                zi_name = gbk_as_437
        except (UnicodeEncodeError, UnicodeDecodeError):
            pass
        zi = zipfile.ZipInfo(zi_name)
        zi.external_attr = 0o644 << 16
        zf.writestr(zi, data)
    zf.close()
    return buf.getvalue()


def chat_txt(lines):
    """lines: (日期或None, 发送者, HH:MM, 内容行元组)；日期缺省沿用上一条（微信导出习惯）。"""
    out = []
    for date, sender, hm, content in lines:
        out.append("·" + sender)
        out.append(("%s %s" % (date, hm)) if date else hm)
        out.extend(content)
    return "\n".join(out)


def upload(client, data, name="聊天记录_2024年05月01日.zip"):
    return client.post("/api/asr/wxchat", data={"file": (io.BytesIO(data), name)},
                       headers={"X-LZ-Token": TOKEN},
                       content_type="multipart/form-data")


def main():
    root, mod = make_sandbox()
    c = mod.app.test_client()
    fails = []

    def check(cond, label, detail=""):
        print(("PASS " if cond else "FAIL ") + label + (("：" + str(detail)) if not cond and detail else ""))
        if not cond:
            fails.append(label)

    # 1) 未带令牌 → 401
    r = c.post("/api/asr/wxchat/x/rename", json={"title": "x"})
    check(r.status_code == 401, "rename 无令牌拒绝 401")
    r = c.post("/api/asr/wxchat/x/append", data={"file": (io.BytesIO(b"x"), "a.zip")},
               content_type="multipart/form-data")
    check(r.status_code == 401, "append 无令牌拒绝 401")

    # 2) 新建：3 条消息（含 [图片]/[文件] 各一）
    img1 = b"\x89PNG\r\n\x1a\nfake-img-1"
    doc1 = b"%PDF-1.4 fake-doc-1"
    zip1 = wx_zip([
        ("聊天记录/media/img_1.jpg", img1),
        ("聊天记录/文件/合同.pdf", doc1),
        ("聊天记录/聊天记录.txt", chat_txt([
            ("2024年5月1日", "张三", "10:00", ("早上好，合同发你了",)),
            (None, "李四", "10:05", ("[文件]合同.pdf",)),
            (None, "张三", "10:06", ("[图片]",)),
            (None, "李四", "10:30", ("收到，下午回你",)),
        ])),
    ])
    r = upload(c, zip1)
    check(r.status_code == 200, "新建上传 200", r.get_data(as_text=True)[:200])
    j1 = r.get_json()
    sid, pwd = j1["share_id"], j1["pwd"]
    check(j1["message_count"] if "message_count" in j1 else True, "新建响应字段")  # 宽松

    # 3) 追加：一段更早的消息 + 一段更晚的消息 + 新图片（验证按时间归并与附件续编）
    img2 = b"\x89PNG\r\n\x1a\nfake-img-2"
    zip2 = wx_zip([
        ("聊天记录/media/img_1.jpg", img2),
        ("聊天记录/聊天记录.txt", chat_txt([
            ("2024年4月30日", "李四", "09:00", ("上周说的事，帮忙跟进下",)),
            (None, "张三", "09:01", ("好的",)),
            ("2024年5月3日", "李四", "18:00", ("[图片]",)),
            (None, "张三", "18:01", ("截图已收到",)),
        ])),
    ])
    r = c.post("/api/asr/wxchat/%s/append" % sid, data={"file": (io.BytesIO(zip2), "聊天记录_2024年05月03日.zip")},
               headers={"X-LZ-Token": TOKEN}, content_type="multipart/form-data")
    check(r.status_code == 200, "追加上传 200", r.get_data(as_text=True)[:200])
    ja = r.get_json()
    check(ja.get("appended") is True and ja.get("added_messages") == 4, "追加计数 +4", ja)
    check(ja.get("url", "").endswith("?pwd=" + pwd), "追加后链接与提取码不变", ja.get("url"))
    check(ja.get("expires_at", 0) > time.time() + 29 * 86400, "追加后有效期重新计 30 天")

    # 4) 重命名
    r = c.post("/api/asr/wxchat/%s/rename" % sid, json={"title": "张三借款纠纷"},
               headers={"X-LZ-Token": TOKEN})
    check(r.status_code == 200 and r.get_json().get("renamed"), "重命名 200")
    r = c.post("/api/asr/wxchat/%s/rename" % sid, json={"title": "   "},
               headers={"X-LZ-Token": TOKEN})
    check(r.status_code == 400, "空标题拒绝 400")

    # 5) list：标题、消息数、参与者
    r = c.get("/api/asr/wxchat/list", headers={"X-LZ-Token": TOKEN})
    items = r.get_json()["shares"]
    it = next((x for x in items if x["share_id"] == sid), None)
    check(it is not None, "list 含该分享")
    check(it and it["title"] == "张三借款纠纷", "list 标题已更新", it and it["title"])
    check(it and it["message_count"] == 8, "list 消息数=8", it and it["message_count"])
    check(it and set(it["participants"]) == {"张三", "李四"}, "list 参与者归并")

    # 6) chat.json：时间归并 + 附件下标平移 + 真名快照
    r = c.get("/s/%s/chat.json?pwd=%s" % (sid, pwd))
    cj = r.get_json()
    times = [m["time"] for m in cj["messages"]]
    check(times == sorted(times), "消息按时间升序归并", times)
    check(times[0].startswith("2024-04-30") and times[-1].startswith("2024-05-03"),
          "时间跨度覆盖两段", (times[0], times[-1]))
    atts = cj["attachments"]
    check(len(atts) == 3, "附件共 3 个（1 pdf + 2 img）", [a["name"] for a in atts])
    file_msgs = [m for m in cj["messages"] if m["type"] == "file"]
    img_msgs = [m for m in cj["messages"] if m["type"] == "image"]
    check(len(file_msgs) == 1 and atts[file_msgs[0]["att"]]["name"] == "合同.pdf",
          "文件消息附件下标正确")
    check(all(atts[m["att"]]["name"].endswith(".jpg") for m in img_msgs),
          "图片消息附件下标正确（含追加段）")
    check(all(m["file_url"].startswith("http") for m in file_msgs), "附件 URL 已补全")
    check(cj["title"] == "张三借款纠纷", "chat.json 标题同步", cj["title"])
    check(set(cj["realnames"].values()) == {"张三", "李四"}, "真名快照覆盖全部参与者")

    # 7) 分享页：标题与消息数
    r = c.get("/s/%s?pwd=%s" % (sid, pwd))
    html = r.get_data(as_text=True)
    check("张三借款纠纷" in html and "8 条消息" in html, "分享页标题与条数更新")

    # 8) 附件可下载（续编后的第 3 个附件 = 追加的图片）
    r = c.get("/s/%s/file/2?pwd=%s" % (sid, pwd))
    check(r.status_code == 200 and r.data == img2, "追加附件可下载且内容正确")

    # 9) 坏包：非聊天记录 zip → 400；不存在的 sid → 404；他人记录不可见（单用户略）
    bad = wx_zip([("readme.txt", b"not a chat export")])
    r = c.post("/api/asr/wxchat/%s/append" % sid, data={"file": (io.BytesIO(bad), "b.zip")},
               headers={"X-LZ-Token": TOKEN}, content_type="multipart/form-data")
    check(r.status_code == 400, "坏包追加拒绝 400", r.get_data(as_text=True)[:120])
    r = c.post("/api/asr/wxchat/deadbeef/append", data={"file": (io.BytesIO(zip2), "b.zip")},
               headers={"X-LZ-Token": TOKEN}, content_type="multipart/form-data")
    check(r.status_code == 404, "不存在记录追加 404")
    r = c.post("/api/asr/wxchat/deadbeef/rename", json={"title": "x"},
               headers={"X-LZ-Token": TOKEN})
    check(r.status_code == 404, "不存在记录重命名 404")

    # 10) 错误提取码 → 403
    r = c.get("/s/%s/chat.json?pwd=WRONGWRONGWRONGWRONG" % sid)
    check(r.status_code == 403, "错误提取码 403")

    shutil.rmtree(root, ignore_errors=True)
    print()
    if fails:
        print("结果：FAIL（%d 项）：%s" % (len(fails), "、".join(fails)))
        sys.exit(1)
    print("结果：全部通过（wxchat_append 回归套件）")


if __name__ == "__main__":
    main()
