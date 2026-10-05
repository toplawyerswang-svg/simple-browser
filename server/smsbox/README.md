# smsbox — 12368 短信自动备份接收端（SER8）

轻舟 APP 监听来自 12368 的短信（实时广播 + 历史回扫），经公网门面 POST 到本服务，
去重后落 SQLite。数据盘位 /opt/smsbox/data/sms.db（系统盘，自动进 restic 每 5 分钟快照）。

> **✅ 已部署（2026-10-05）**：SER8 `/opt/smsbox/`（systemd `smsbox.service`，0.0.0.0:8730，
> UFW 仅内网+tailnet）；ECS nginx `api_orientlaw_cn` 已加 `/api/sms/` 反代（改前备份
> `/root/nginx-backups/api_orientlaw_cn.bak-20261005-smsbox`）；看门狗探针
> `smsbox:8730:/healthz` 已加（脚本备份 `/root/ser8-watchdog.sh.bak-smsbox-20261005`，本机留档
> `D:\zcodexiangmu\养虾\archive\ser8-watchdog.sh` 已同步）。公网冒烟：healthz 200、ping 无令牌 401、
> asrsvc 回归正常。真机 v1.13.0 已装、双权限已授、令牌已配置、自动备份已开启；
> **首次历史回扫待用户解锁手机后在 设置→短信备份 点「回扫」执行**。下方部署步骤保留作重建/迁移参考。

## 架构

```
手机轻舟APP（READ_SMS+RECEIVE_SMS，过滤 sender==12368）
   │  POST /ingest  JSON + X-LZ-Token
   ▼
ECS nginx  api.orientlaw.cn/api/sms/  （公网 HTTPS 门面，人在任何地方可达）
   │  proxy_pass http://100.100.19.0:8730/   （tailnet）
   ▼
SER8 smsbox.service  0.0.0.0:8730（UFW 仅放行办公内网；tailscale0 由 tailscaled 放行）
   └─ /opt/smsbox/data/sms.db（系统盘，restic 每 5 分钟自动快照，无需改备份脚本）
```

- 判重规则（监听号码）在 APP 端做，服务端对持令牌者全收；以后扩号码只改 APP。
- 去重：服务端 sha1(sender|sent_at|body) 唯一索引，APP 端按服务地址隔离记录已传清单，
  两边幂等，重复上存无害。

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | /healthz | 无 | 看门狗探针，`{"ok":true,"total":N}` |
| GET | /ping | 令牌 | 核验令牌 + 返回总条数（APP 里填令牌时核验用） |
| POST | /ingest | 令牌 | `{"messages":[{sender,body,sent_at(ms)}]}` → `{added,duplicates,total}` |
| GET | /list?limit=&q= | 令牌 | 最近短信（新→旧），limit≤500，q 按内容/号码模糊 |
| GET | / | 无（页面壳） | 网页查看页，令牌填在页面里存 localStorage |

本地验证（Windows Git Bash，注意 curl 中文须走 UTF-8 文件体，不能命令行内联）已全部通过：
healthz / ping 鉴权 / ingest 去重幂等 / 秒级时间戳兜底 / list 中文搜索 / 页面壳 200。

## 部署步骤（等用户拍板后执行）

```bash
# 0. 预检：确认 8730 未占用
ssh ser8 'ss -tlnp | grep 8730 || echo free'

# 1. 代码与目录（本仓库 server/smsbox/smsbox.py → /opt/smsbox/）
ssh ser8 'sudo mkdir -p /opt/smsbox/data && sudo chown deploy:deploy /opt/smsbox /opt/smsbox/data'
scp "server/smsbox/smsbox.py" ser8:/tmp/smsbox.py && ssh ser8 'mv /tmp/smsbox.py /opt/smsbox/smsbox.py && chmod 755 /opt/smsbox/smsbox.py'

# 2. 令牌（真源在服务器文件，APP 里填的时候从这读，不进 git 不进聊天记录）
ssh ser8 'openssl rand -hex 24 | sudo tee /opt/smsbox/token >/dev/null && sudo chmod 600 /opt/smsbox/token && sudo chown deploy:deploy /opt/smsbox/token'

# 3. systemd 单元（纯标准库，无需 venv；绑 0.0.0.0 靠 UFW 限内网，无需 wait-ips）
ssh ser8 'sudo tee /etc/systemd/system/smsbox.service >/dev/null <<EOF
[Unit]
Description=smsbox 12368 SMS backup receiver
After=network-online.target

[Service]
User=deploy
ExecStart=/usr/bin/python3 /opt/smsbox/smsbox.py --port 8730 --db /opt/smsbox/data/sms.db --token-file /opt/smsbox/token
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now smsbox'

# 4. UFW：办公内网可直连查看（tailscale0 入站由 tailscaled 放行，不占 UFW 规则）
ssh ser8 'sudo ufw allow from 192.168.1.0/24 to any port 8730 proto tcp'

# 5. 冒烟（tailnet 与 LAN 双通道）
ssh ser8 'curl -s http://127.0.0.1:8730/healthz && echo && curl -s http://100.100.19.0:8730/healthz'
# 本机（办公网）：curl http://192.168.1.73:8730/healthz → {"ok":true,"total":0}

# 6. ECS nginx 门面：api.orientlaw.cn 站点配置内加 location（改前备份，nginx -t 过了再 reload）
#    location /api/sms/ { proxy_pass http://100.100.19.0:8730/; proxy_set_header Host $host; }
#    （目标是 tailnet 静态 IP，不涉及外部域名解析，静态 proxy_pass 合规）
#    验证：curl -s https://api.orientlaw.cn/api/sms/healthz

# 7. 看门狗加探针（必须等服务起好再加，防误报）：ECS /usr/local/sbin/ser8-watchdog.sh
#    探针列表加 smsbox:8730:/healthz；备份 ECS /root/ser8-watchdog.sh.bak-smsbox-<日期>，
#    本地留档 D:\trae\迁移到零刻\.migration\ser8-watchdog.sh 同步更新
# 8. APP 端配置：设置 → 短信备份 → 上传令牌，从 /opt/smsbox/token 读出填入并核验

# 回滚：sudo systemctl disable --now smsbox + 撤 ECS nginx location + 看门狗撤探针
# 数据：/opt/smsbox/data/sms.db，删除即清空（先确认不再要）
```

## 已知边界

- 换机/重装 APP 不丢数据：服务端按内容去重，APP 回扫会把全部 12368 短信重传一遍，服务器自动幂等。
- APP 离线时短信先进本机待上存队列（SQLite），恢复后自动补传；队列永不丢弃。
- 服务端 / 页面仅页面壳无鉴权（无数据），数据接口全部要令牌；SER8 端口 UFW 限办公内网。
