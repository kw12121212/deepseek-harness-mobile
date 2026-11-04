# dshbridge server

**dshbridge** is our transport server. **dsh server** means the native DeepSeek Harness server. The current design runs these two separate components on the same host: Android connects to dshbridge over HTTPS/WSS; dshbridge forwards supported dsh messages to the loopback dsh server. dsh executes tasks, calls models and owns sessions/workspaces and the native Web interface. dshbridge owns device pairing, authentication, revocation and random route mappings.

The component name is **dshbridge**; the existing CLI/executable name is `dsh-bridge`, with sources under `bridge/cmd/dsh-bridge/`. Command examples below use that existing name. See [server roles and terminology](../docs/architecture/server-roles.md).

dshbridge preserves dsh 0.2.0-rc.2 message formats, and enforces that contract: `serve` probes the upstream landing page (`__DSH_BOOT__` → `release.version`) before opening any pairing or forwarding surface and refuses to start unless the version equals the pinned `CompatibleDSHVersion` constant (`0.2.0-rc.2`); an undetectable version is rejected too (fail closed). Moving to another dsh version — including rc→rc — means an explicit change, updating the constant, and re-validation, the same policy as the RPC allowlist. Random routes are stable dshbridge deployment configuration, not an authentication substitute. Its pairing codes/device tokens are separate from the native dsh launch token/Cookie. The dshbridge outer protocol version (`protocolVersion: 1`) is separate from the native dsh software version.

## Addresses and credentials

| Setting | Component |
| --- | --- |
| `--upstream` | Native dsh server's loopback origin |
| `--public-origin`, phone configuration `origin` | dshbridge server's client-facing origin; public deployment requires HTTPS |
| `--dsh-token-file` | Native dsh launch token used internally by dshbridge |
| `--dsh-cookie-file` | Existing native dsh Cookie for the selected loopback Host; mutually exclusive with token file |
| `pair`, `devices`, `revoke`, device token, `routes.json` | dshbridge device access and random routes |

Start the loopback dsh server, then configure/start dshbridge and pair the phone. The two processes are maintained separately; dshbridge does not manage the dsh process. Private server mode in Android means dshbridge; Direct connection means native dsh. Native Web management is accessed locally on the dsh server host.

## Build and initialize dshbridge

Build with Go 1.26 or newer: `go build ./cmd/dsh-bridge`.

Initialize a private state directory (outside the repository):

```sh
dsh-bridge init --state /private/bridge-state --listen 127.0.0.1:3090 --upstream http://127.0.0.1:3080 --public-origin http://127.0.0.1:3090
dsh-bridge routes --state /private/bridge-state
```

`routes.json` is the one authoritative mapping from pair/rpc/stream/export to deployment paths; `config.json` contains nonsecret deployment settings. Keep both in backups. Missing or corrupt configuration stops startup. Never publish either mapping or deployment credentials in a public document. On Windows restrict state directory access using the owner's account ACL; POSIX private modes alone do not establish Windows ACLs.

HTTP development is for loopback only. Public clients require HTTPS/WSS. A reverse proxy must point only to this service, never to dsh or its static resources. dsh itself must not have another publicly forwarded listener. This is not NaïveProxy and does not guarantee resistance to traffic analysis.

Generate a one-use, ten-minute pairing code locally with `dsh-bridge pair --state DIR`. Import the `connection` JSON in the phone and enter the separately printed `pairingCode`. This local command output contains a secret; keep it private. List device IDs with `devices --state DIR`; revoke using `revoke --state DIR --device ID`. Server operations reload credential state, so separate admin processes take effect without restart. Back up the private state directory, including `auth.json`. Tokens and pairing codes are stored only as SHA-256 hashes. If a process dies while holding `.auth.lock`, stop all bridge processes before removing that stale lock; contention fails closed instead of automatically breaking another process's lock.

Serve locally with `dsh-bridge serve --state DIR --dsh-token-file PRIVATE_FILE`. The file contains only the local dsh launch token, not a full URL. With public TLS use `--tls-cert CERT --tls-key KEY`. Alternatively use `--behind-tls-proxy` with a loopback listener and an HTTPS public origin; only your TLS proxy may forward to that listener. Plaintext public listeners are rejected. The RPC allowlist is explicit and covers current native operations; it is not a general-purpose HTTP proxy.

Active streams check device validity before forwarding each data frame. The server also checks idle streams every 250 ms and closes revoked connections; new requests observe revocation immediately. Pairing and revoke commands serialize credential transactions across processes. An interrupted pairing response needs a freshly generated pairing code; the service does not replay the exchange.

## Connection protocol examples

All paths below are synthetic documentation values. Every deployment creates its own independent 128-bit random value for each route; each route is split into two URL-safe segments. Keep the generated `routes.json` as the authoritative local maintenance record. Initialization never silently rotates it.

Pairing configuration given to the phone:

```json
{"protocolVersion":1,"origin":"https://gateway.example.test","pairPath":"/Q9w2aX8mN4b/T6pR1yK3cH0"}
```

The phone sends a POST to that deployment's pairing path. The one-use code goes in the JSON body, together with `deviceName`. A successful response supplies `protocolVersion`, `deviceId`, `deviceToken` and the `rpc`, `stream`, `export` relative paths. The token is an independent 256-bit random device credential. The Android client encrypts it with Android Keystore and saves it outside backups; it does not claim that every phone has hardware-backed keys.

For RPC, the device sends `Authorization: Bearer <device-token>` to the generated RPC path. The body preserves dsh's envelope:

```json
{"type":"client-request","rpcId":"example-request-1","method":"session/list","payload":{"args":{"_request":{}}}}
```

WebSocket uses the generated stream path and the same device Authorization header; dsh open/item/cancel/end/error frames retain their structure. Export POST accepts `{"sessionId":"example-session","includeDescendants":true}` and streams a ZIP. None of these public requests needs the internal dsh launch token or cookie.

Known and unknown unauthenticated endpoints return an empty 404 and never reach dsh. The bridge does not serve HTML, JavaScript bundles, native `/api/*`, or a discovery/health page. A valid device credential intentionally unlocks the preserved dsh protocol; randomized paths alone provide no authorization.

## Builds and operation limits

Run `bash bridge/scripts/build.sh` from the repository root, or `bash scripts/build.sh` from this module, with Go on PATH. Outputs are Windows/macOS/Linux, each in amd64 and arm64, under `bridge/dist/`. macOS arm64 was run locally; the other targets are cross-built artifacts.

The HTTP server configures a 16 KiB MaxHeaderBytes setting and a ten-second header deadline (Go may reserve additional parsing space). Bodies have a fifteen-second read deadline, including rejected requests that the HTTP server may drain. RPC bodies are capped at 8 MiB; pair/export bodies at 8 KiB. A device may hold eight HTTP requests and four physical WebSocket connections. RPC upstream forwarding is bounded at sixty seconds; ordinary HTTP responses at seventy-five seconds. WebSocket establishment and writes are bounded at fifteen seconds. Export transfers have a five-minute deadline and a 512 MiB ceiling. A truncated or oversized transfer is aborted, and Android removes its partial destination.

Each request/frame validates its credential. Idle WS revocation checks run every 250 ms. An operation already accepted by dsh cannot be rolled back by deleting the external credential. Device revocation is local administration; the public transport cannot create arbitrary upstream destinations or administrative endpoints.

Android's Private server mode is the default pairing flow; Direct connection remains available for explicit local/native connections. Forgetting a private profile invalidates its work and credentials and requires fresh pairing. Export uses the system file picker on every supported Android version. Native dsh Web management stays on the dsh server host in private mode. A failed private connection never falls back to native dsh routes.

No public domain, certificate, reverse proxy, firewall rule, installer, or process supervisor is provisioned by this repository. Deploy dshbridge with your own trusted TLS certificate or an isolated TLS reverse proxy, and keep the native dsh server listener on loopback. External exposure still needs to be verified on that actual host.

## Connect to the same native Host as Desktop

Pending approvals and foreground questions belong to a running native dsh Host. A separately started Web Host does not share another Host's live interaction queue, even when both installations use the same dsh version. Set `config.json`'s `upstream` to the loopback origin of the Host that owns the task. The Desktop rc.2 Host observed in this validation listens on `127.0.0.1:19387`; this is an observed profile configuration, not a universal discovery rule. A separately started Web Host had used `127.0.0.1:3080`.

For an already authenticated Desktop Host, use `dsh-bridge serve --state DIR --dsh-cookie-file PRIVATE_COOKIE_FILE` with the same TLS flags as the deployment. The private file contains one native `name=value` Cookie, at most 4096 bytes, for that exact upstream authority. dshbridge rejects a mismatched Cookie name, extra cookies, header separators and simultaneous token/cookie options. Keep the file outside the repository with access limited to its owner. The Cookie stays inside dshbridge; Android continues to use its separate device credential.

A fixed Cookie does not refresh itself. If Desktop invalidates it, requests fail and keep presenting that Cookie until the operator replaces it and restarts dshbridge. There is no anonymous fallback. Keep the native dsh listener on loopback and restart only dshbridge when changing its upstream authentication.

The interaction allowlist includes `$events/result`, `userQuestions/answer`, `$events` and `userQuestions/attachWait` alongside the existing session streams. It does not expose native administration or a pending-events discovery endpoint.

## Tailnet 私有暴露（tailscale serve）

公网域名、证书与反代之外，dshbridge 还可以经用户个人 tailnet 私有直达：dshbridge 继续只监听回环，
由同主机的 `tailscale serve --https` 反向代理承担 TLS 与 tailnet 准入。serve 形态运行：

```sh
dsh-bridge serve --state DIR --behind-tls-proxy \
  --public-origin https://<节点 tailnet 域名>:<serve端口> --dsh-token-file PRIVATE_FILE
```

配置 Serve 用仓库脚本（serve/status/reset 三子命令；`--state` 或 `DSHBRIDGE_STATE` 指向状态目录，
`DSHBRIDGE_SERVE_PORT` 可覆盖 tailnet 侧端口，默认与 `config.json` 的 `listen` 端口一致）：

```sh
bash bridge/scripts/tailscale-serve.sh --state DIR serve
bash bridge/scripts/tailscale-serve.sh --state DIR status
bash bridge/scripts/tailscale-serve.sh --state DIR reset
```

脚本要求 python3、curl 与已登录的 tailscale CLI；Windows host 在 Git Bash 或 WSL 下运行。后端存活检查
接受任意 HTTP 状态码（含 404）——dshbridge 没有健康发现页，任何 HTTP 应答都证明监听进程存在。

**安全铁律：只能经 `tailscale serve --https` 暴露。** `serve --tcp`、`--tls-terminated-tcp`、Funnel、
`ssh -L` 等转发不写 `X-Forwarded-For`，远端请求的对端会呈现为 127.0.0.1，被当作本机请求，等于绕过
传输边界假设。脚本在写入前解析 `tailscale serve status --json`：目标端口被这些形态或任何其他服务占用时
拒绝继续，只有空闲端口或确属本服务的配置才会写入；reset 同样只移除确属本服务的配置。谁能连上端口由
tailnet ACL 决定，脚本不修改 ACL；设备 token 仍是唯一应用层凭据（不叠加 WhoIs 白名单层）。

经 serve 转发后连接对端恒为 127.0.0.1、真实 tailnet 地址在 `X-Forwarded-For`。对该形态的核对结论：
RPC/stream/export 的凭据校验按设备 Bearer token 进行，与来源地址无关，行为不变；bridge 不记录地址
日志；唯一的差异是配对限流——`pair` 按 RemoteAddr 主机分桶，经 serve 后所有来源共享 127.0.0.1 一个桶，
即全局 10 次配对请求/分钟。该限制比公网形态更严格而非更宽松，多台设备同分钟密集配对可能触发 429
`rate-limited`，稍候重试即可。

脚本行为的确定性回归（假命令，不触碰真实 tailnet）：`bash bridge/scripts/test-tailscale-serve.sh`。
真机 serve 暴露与 WSS stream 转发的验证证据见 [docs/qa/tailnet-serve/](../docs/qa/tailnet-serve/)。

## 改动允许列表后必须重新部署

`internal/gateway/allowed.go` 决定 bridge 放行哪些 RPC。只要这张表变了（新增或移除端点），
**所有正在运行的 bridge 进程都要用新源码重新构建并重启**，否则客户端会看到 `Private server HTTP 400`，
而看上去像是客户端功能坏了。

排查顺序：先 `curl` 或日志确认请求确实到了 bridge、再确认 bridge 二进制的构建时间晚于允许列表改动、
最后才怀疑客户端。2026-11-02 的队列删除失败就是旧二进制造成的（见 [队列条验收](../docs/qa/q5b-queue/validation.md)）。
