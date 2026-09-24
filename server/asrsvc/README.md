# asrsvc 服务端代码留档

权威运行版本在法信 ECS：`/opt/asrsvc/app.py`（8.133.194.29，SSH 端口 45678）。
本目录是随版本提交的副本，便于追溯服务端逻辑；修改流程：

1. 改 `build/wxchat/wxchat_block.py`（功能块）或直接改副本
2. `python build/wxchat/patch_app.py` / `patch_ocr.py`（幂等补丁）→ 生成 app.py
3. scp 覆盖服务器 → `systemctl restart asrsvc` → `grep` 验证路由

## ASR 模型（2026-09-24 切换）

- `config.json` 的 `asr_model` 已从 `fun-asr` 切到 **`qwen-audio-3.1-asr-flash-filetrans`**（纯配置切换，app.py 零改动）
- 同属百炼 FunAudio-ASR RESTful 文件识别通道；A/B 实测（同段 4 说话人录音）输出结构完全兼容
  （transcripts/sentences 的 begin_time/end_time/speaker_id/text 一致），断句更细、标点更好，
  顶层多一个 `usage` 字段（无影响）
- 回滚：`/root/asrsvc-config-backup-20260924.json` 覆盖回 `/opt/asrsvc/config.json` 后
  `systemctl restart asrsvc`

## 微信聊天记录转存（v1.9.0+）

- `POST /api/asr/wxchat` 上传 `聊天记录_*.zip`（GBK 条目名），解析建 30 天分享
- `GET /s/<sid>` 聊天样式分享页（图片/PDF 带「查看解析结果」）
- `GET /s/<sid>/chat.json?pwd=` agent 结构化端点（消息带 ocr_text）
- `GET /s/<sid>/file/<idx>` 附件下载；`DELETE /api/asr/wxchat/<sid>` 删除
- 附件视觉解析：GLM-4V-Flash（config.json `glm_vision_api_key`），
  PDF 经 pdftoppm 拆页上限 30 页，异步 worker 幂等补跑
