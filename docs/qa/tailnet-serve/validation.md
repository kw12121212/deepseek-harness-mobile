# tailnet serve 暴露验证（bridge-tailnet-serve）

验证目标（planned change `bridge-tailnet-serve`）：

1. serve 脚本在真机 host 上把回环 dshbridge 经 `tailscale serve --https` 暴露给个人 tailnet；
2. 经 `https://<节点域名>:<端口>` 完成一次配对与一次 RPC 调用；
3. stream 路由的 WSS 长连接经 serve 转发稳定（保持 ≥30 秒）；
4. 禁止形态（`serve --tcp` / `--tls-terminated-tcp` / Funnel / 他人配置占用端口）被脚本拒绝。

环境（2026-11-03）：

| 组件 | 版本 / 取值 |
| --- | --- |
| host | Windows（Git Bash），tailnet 节点 `gamepc-1.tail89569c.ts.net` |
| tailscale | 1.102.3（CLI + tailscaled 服务，已登录） |
| 原生 dsh 上游 | Desktop 自带 CLI `dsh web --host 127.0.0.1 --port 3080 --no-open`（dsh 0.2.1-rc.2，Web Host） |
| dshbridge | 本仓库 `bridge/` 现源码构建（`go build ./cmd/dsh-bridge`） |
| 部署形态 | `dsh-bridge serve --state <dir> --behind-tls-proxy --dsh-token-file <私有文件>`，监听 `127.0.0.1:3091`，`--public-origin https://gamepc-1.tail89569c.ts.net:3091` |
| 状态目录 / 密钥 | `runs/tailnet-serve-qa/`（gitignore，不入库） |

脚本确定性回归：`bash bridge/scripts/test-tailscale-serve.sh` → 21 项全部通过（假 tailscale/curl/sudo，
覆盖端口状态机各分支——空配置、本服务反代、TCP 转发、明文 HTTP serve、Funnel、前台会话、他人配置，
以及 status 读取/解析失败、后端不健康、写入被拒、提权、reset 守护、回环集合含 [::1] 与 localhost 拒绝）。

## 真机验证记录

### 1. serve 暴露配置（脚本 serve 子命令）

`bash bridge/scripts/tailscale-serve.sh --state <dir> serve` 输出：

```
==> 配置 https://<node>:3091 -> http://127.0.0.1:3091 ...
Available within your tailnet:
https://gamepc-1.tail89569c.ts.net:3091/
|-- proxy http://127.0.0.1:3091
Serve started and running in the background.
ok - tailnet 访问地址: https://gamepc-1.tail89569c.ts.net:3091
```

再次执行（幂等）：`端口 3091 已反向代理到 http://127.0.0.1:3091，无需修改。`

### 2. 配对（经 serve 的 HTTPS）

`dsh-bridge pair` 生成一次性配对码后，经 tailnet HTTPS POST pair 路由：

```
curl -s -X POST https://gamepc-1.tail89569c.ts.net:3091/rrFl-FcFltk/VWIIM8N0FnY \
  -d '{"deviceName":"tailnet-serve-qa","pairingCode":"..."}'
→ {"protocolVersion":1,"deviceId":"bOEz5gSI...","deviceToken":"<43字符>","routes":{...rpc/stream/export...}}
```

配对成功，设备凭据与随机路由按 protocolVersion 1 返回（与公网 HTTPS 形态一致）。

### 3. RPC（经 serve 的 HTTPS，真实 dsh 响应）

设备 Bearer token 调 `session/list`（真实会话目录返回，节选）：

```
{"type":"server-response","rpcId":"tailnet-serve-qa-1","result":{"ok":true,"value":{"items":[
  {"sessionId":"session-9cec2952-...","cwd":"C:\\zw\\Code\\private-content-assistant",
   "projections":{"values":{"title":"Strict Roadmap Recommendation",...}}},...]}}}
```

### 4. stream WSS 长连接（经 serve 的 TLS 1.3 + WebSocket 升级）

stdlib Python 客户端（`runs/tailnet-serve-qa/wss_hold.py`）直连 `wss://gamepc-1.tail89569c.ts.net:3091/<stream 路由>`：

```
TLS established: TLSv1.3 cipher=TLS_AES_128_GCM_SHA256
upgrade: HTTP/1.1 101 Switching Protocols
sent open session/follow; holding connection...
  frame +  0.1s {'type': 'item', 'streamId': 'qa-wss-hold-1'}
PASS: WSS held 36.2s through tailscale serve, 1 frames (1 data)
```

结论：serve 对 WSS 升级与双向帧转发正常——`open`（session/follow，真实会话）上行送达上游、真实
`item` 帧下行回传，空闲长连接保持 36 秒未被 serve 断开（超过任务要求的 30 秒）。milestone 风险项
「tailscale serve 对 WSS 长连接的转发行为」在本机环境成立。

首轮曾用错误 args（`sessionId` 直传）收到上游 `gateway/arguments-invalid` 错误帧——错误帧同样完整
经 serve 回传，双向转发行为一致。

### 5. 禁止形态拒绝（真机）

在独立端口 3999 预置 `tailscale serve --bg --tls-terminated-tcp=3999`（不写 X-Forwarded-For 的转发），
再以 `DSHBRIDGE_SERVE_PORT=3999` 运行脚本：

```
tailscale-serve: 端口 3999 已被其他 Serve 配置占用：TCP 转发到 127.0.0.1:1（不写 X-Forwarded-For，被当作本机请求，禁止）
不会覆盖它。请用 DSHBRIDGE_SERVE_PORT 指定其他端口，或先确认后手工移除占用者。  （exit=1）
```

拒绝后零写入；测试配置已手工移除（`3999 remaining: cleared`）。TCP 转发（含 --tls-terminated-tcp）/
Funnel / 前台会话 / 明文 serve / 他人配置的完整分支矩阵由 21 项确定性脚本测试覆盖（明文 serve 分支
在评审后补充了专项测试）。

### 6. 清理

- `tailscale-serve.sh ... reset`：`已移除端口 3091 的 Serve 配置，其他端口未受影响`；复核
  `serve status --json` 中 3091 的 TCP 与 Web 条目均已清除（该节点既有的 443/19387/8765 配置未动）。
- dshbridge 与 dsh Web Host 进程已停止，3080/3091 端口已释放。

## serve 形态差异核对（对照 milestone 风险清单）

| 核对项 | 结论 |
| --- | --- |
| 凭据校验 | 按设备 Bearer token，与来源地址无关；经 serve（对端恒 127.0.0.1）后配对/RPC/stream 全部照常工作，实测通过 |
| 日志取址 | bridge 全程不记录地址日志，不存在取址错误问题 |
| pair 限流 | 按 RemoteAddr 分桶 → 经 serve 收敛为 127.0.0.1 单桶（全局 10 次/分钟），更严格而非削弱；已在 bridge/README.md 说明 |
| WSS stream 转发 | 真机验证稳定（见上第 4 节） |

未覆盖 / 留待后续变更：跨设备的 tailnet 侧验证（Android tsnet 节点入网后经 tailnet 访问）属于
`ts-embed-spike` 及后续集成变更；公网入口回归（既有公网 HTTPS 部署）不在本变更范围。
