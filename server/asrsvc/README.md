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

## 长音频分流 FunASR（2026-09-29 已部署上线；>3h 说话人标签阈值校准中）

- **规则**：单文件时长 > `config.json` 的 `asr_local_threshold_sec`（默认 10800 秒 = 3 小时）
  → 走 SER8 本地 FunASR（`funasr_url`，零成本）；其余照旧百炼 qwen。
  FunASR 调用失败自动回落 qwen（任务不失败，`engine=funasr-fallback-qwen`）。
  **不配置 `funasr_url` = 分流关闭，行为与旧版完全一致**（灰度开关）。
- **SER8 服务**：`funasr.service`（`/opt/funasr-svc`，venv 自带 torch-cpu+funasr，
  绑 `100.100.19.0:8740` 仅 tailnet 可达 + wait-ips drop-in；paraformer-zh + fsmn-vad
  + ct-punc + cam++，CPU 推理 MODEL_LOCK 串行）。源码本仓 `funasr_svc.py`。
  实测 17.7 分钟音频 75 秒（RTF≈0.069）；3 小时音频预估 ~15 分钟。
- **结果格式**：服务端按与 `build_segments` 同规则合并（同说话人间隔≤1.5s），
  返回 segments 字段与百炼通道完全同构（speaker/begin_ms/end_ms/text/begin/end），
  方言改写/声纹/分享全链路复用，**APP 零改动**。任务新增 `engine` 字段
  （funasr-local / funasr-fallback-qwen；qwen 路径无此字段，向后兼容）。
- **双分支实测**（ECS 测试实例 8721，生产未动）：
  A：1064s 样本（阈值600）→ FunASR 75s SUCCEEDED，116 段 2 说话人，无 dash_task_id；
  B：30s 片段（阈值99999）→ qwen 50s SUCCEEDED，有 dash_task_id，段格式一致。
- **部署状态（2026-09-29 12:44 已上线）**：①~⑤ 全部完成——app.py 已部署（备份
  `/root/asrsvc-app-backup-20260929.py`）、config 已加 `funasr_url`（备份
  `/root/asrsvc-config-backup-20260929.json`）、生产双分支真实验证通过（30s→qwen
  正常带 dash 任务号；3.55h→FunASR 分流触发）、看门狗探针 `funasr:8740:/healthz`
  已加（脚本备份 `/root/ser8-watchdog.sh.bak-20260929`）+ 手动跑一轮全绿。
- **已知缺陷（修复进行中）**：>3h 文件的说话人标签——funasr 按批次聚类且标签逐批
  累加偏移，3.55h 实测 v1 裂出 **417 个标签**（真值 2 人）；v3 质心归并（每标签取
  句子 ERes2NetV2 嵌入的时长加权质心，质心层面阈值 0.45 归并）收敛到 **52 个**，
  大幅改善但仍不可交付。句级全局聚类已被校准否决（同/异人嵌入相似度分布重叠，
  ARI≤0.571）。SER8 上 `/opt/asrtest/calib_cent.py`（nohup）正在 3.55h 文件上扫描
  质心归并阈值 0.45~0.75，结果落 `/opt/asrtest/calib_cent_result.json`——出阈值后
  写入 funasr-svc config 的 `spk_centroid_dist` 并重启服务，再跑一轮生产验证收口。
- **回滚**：备份的 app.py 覆盖回 + restart 即可；config 删掉 `funasr_url` 亦可单独关闭分流。

## 资料夹（文件夹分组 + 聚合分享，v1.15.0+ 已部署上线）

- **存储**：`data/<fid>.folder.json`（`{"folder_id","name","owner","created_at","items":[{"kind","ref","added_at"}]}`）。
  成员只存引用：`kind=task` 指向转写任务、`kind=wxchat` 指向聊天分享；本体被删除/过期后条目
  自动显示「已失效」（`folder_entry_view` 统一产出视图）。每用户 100 夹、单夹 50 条上限。
- **管理接口**（X-LZ-Token）：
  - `GET/POST /api/asr/folders`（列表 / 新建 `{"name":...}`）
  - `GET/DELETE /api/asr/folders/<fid>`（详情带 entries / 删除，不删成员本体）
  - `POST /api/asr/folders/<fid>/rename`（改名）、`POST .../items`（加入 `{"kind","ref"}`，幂等去重）、
    `POST .../items/remove`（移出）、`POST .../share`（生成聚合分享，form: validity_hours/max_opens）
- **分享页**（`type:"folder"` 的 share）：
  - `/s/<sid>` 目录页（条目卡片清单，打开计数在此完成）
  - `/s/<sid>/item/<idx>` 条目页：task → 转写视图（双标签/回听/译文，与单条分享页同构）；
    wxchat → 复用原分享的气泡流渲染（页内 Agent 入口改指 folder.json，附件走原分享端点）
  - `/s/<sid>/item/<idx>/audio` 录音段落回听切片（文件夹提取码鉴权）
  - `/s/<sid>/folder.json?pwd=` **Agent 端点**：整夹结构化 JSON（录音：text/segments/speaker_names/
    summary/todos/音频直链；聊天：messages 逐条 realname/is_self/file_url/ocr_text + attachments 直链）
- **约束**：文件夹分享链接与录音条目受分享寿命（1h~60天）约束；聊天条目受原分享 30 天寿命约束；
  过期分享清理沿用 `purge_expired_audio`（`*.folder.json` 不走该清理，永久保留直至手动删除）。
- **部署留痕**：本地 `server/asrsvc/app.py` 与服务器一致（改动前备份 `/opt/asrsvc/app.py.bak-folder-20261011`）；
  APP 侧 v1.15.0/47 已装机（设置页两页签入口行 + FolderActivity）。

## 部署状态（2026-09-29 已上线）

- **已部署**：生产 asrsvc 已切 v3 分流版（备份 `/root/asrsvc-app-backup-20260929.py`；
  config 加 `funasr_url`，阈值用默认 10800s）；生产真实任务双分支验证通过
  （30s→qwen 正常有 dash_task_id；3.55h→FunASR 触发「超长音频转本地FunASR」阶段，
  SUCCEEDED engine=funasr-local）；ECS 看门狗已加 `funasr:8740:/healthz` 探针（备份
  `/root/ser8-watchdog.sh.bak-20260929`），手动跑一轮全绿。
- **已知缺陷与修复轨迹（>3h 说话人标签）**：funasr 按批次聚类（batch_size_s=300），
  标签逐批累加偏移——3.55h 实测 v1 裂出 **417 个标签**（真值 2 人）。v3 质心归并
  （每标签取句子 ERes2NetV2 嵌入的时长加权质心，质心层面阈值 0.45 归并）→ **52 个**，
  大幅收敛但仍不可交付。句级全局聚类不可行（校准：同/异人相似度分布重叠，ARI≤0.571）；
  定长窗聚类不可行（对话窗几乎都含两人，嵌入为混合体，65/5 分裂）。
- **进行中**：SER8 `/opt/asrtest/calib_cent.py`（nohup）在 3.55h 文件上扫质心归并阈值
  0.45~0.75，结果落 `/opt/asrtest/calib_cent_result.json`——出阈值后写入
  `/opt/funasr-svc/config.json` 的 `spk_centroid_dist` 并 `systemctl restart funasr`，
  再跑一轮生产 3.55h 验证（预期 ≤4 个标签）后收口。注意循环测试文件是病态样本
  （完全相同音频×12），真实长会议的表现需拿真实长录音再校一轮。
