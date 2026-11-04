# 安全边界清单 — tailscale-private-transport 里程碑收口（security-completeness-review）

范式对齐 private-content-assistant 的 security-boundary：每条边界 = 威胁 / 机制 / 证据 / 结论。
证据指针全部为本仓库内文件（docs/qa/<topic>/ 或源码/测试 file 位置）。评审日期 2026-11-03/04。

## A. 传输与暴露边界

| # | 边界 | 威胁 | 机制 | 证据 | 结论 |
|---|---|---|---|---|---|
| A1 | dshbridge 仅回环监听 | 公网直连 bridge | 明文监听强制回环 IP（PlainListenerAllowed）；serve 形态以 --behind-tls-proxy 运行 | bridge/internal/config/config.go:187；bridge/cmd/dsh-bridge/main.go validateServe；单测 TestRefusesPlainPublicListener | ✅ 成立 |
| A2 | tailnet 暴露仅经 serve --https | 不写 X-Forwarded-For 的转发让远端被当作本机请求 | tailscale-serve.sh 端口状态机拒绝 --tcp/--tls-terminated-tcp/Funnel/前台会话/他人配置；reset 守护；标准 bridge-tailnet-exposure MUST 规则 | bridge/scripts/tailscale-serve.sh；test-tailscale-serve.sh 21 项；docs/qa/tailnet-serve/validation.md §5 真机拒绝记录；.strict-spec-driven/tech/bridge-tailnet-exposure.yaml | ✅ 成立（脚本门禁 + 真机负向证据） |
| A3 | 无公网直接暴露 | Funnel/公网端口 | milestone 范围排除；脚本对 Funnel 显式拒绝（A2 同证据）；App 隧道形态无公网监听 | 同 A2；app 无任何监听公网代码 | ✅ 成立 |
| A4 | LoopbackProxy 令牌防本机他 App | 同机恶意 App 扫描 127.0.0.1 端口以本机 tailnet 身份发请求 | 32 字节随机令牌路径前缀，恒时比较，令牌不符 403 且不拨上游 | app/.../loopback/LoopbackProxy.kt stripToken；LoopbackProxyTest.rejectsWrongTokenWithoutDialingUpstream | ✅ 成立 |
| A5 | dsh 版本门禁 fail-closed | 不兼容上游造成未定义协议交互或降级 | serve 启动探测 __DSH_BOOT__ 精确匹配 0.2.0-rc.2；探测不到拒绝启动；app 直连未认证放行走既有认证流、认证后版本不符拒绝 | bridge/internal/upstream/version.go + cmd requireCompatibleUpstream；app DshVersionContract 三态门禁；docs/qa/dsh-version-lock/validation.md（真机 0.2.1-rc.2 双路径拒绝） | ✅ 成立 |

## B. 凭据与 token 边界

| # | 边界 | 威胁 | 机制 | 证据 | 结论 |
|---|---|---|---|---|---|
| B1 | 设备 token 存储 | 凭据泄露 | SHA-256 哈希落盘（bridge）；App 侧 Keystore 加密、不进备份 | bridge/internal/auth/store.go；bridge/README「Back up」段；ConnectionStore KeystoreCodec | ✅ 成立（既有，本里程碑未改动） |
| B2 | auth key 不入日志/错误/回显 | key 经日志或 UI 泄露 | TunnelManager 只打 present/空；解析异常文案不含 key；params toString 覆写不含 key；设置页密文+用后即清+不保存+autofill 排除 | app/.../tunnel/TunnelManager.kt:60；BridgePairingClient.kt toString；MainActivity keyInput；TailscalePairingParamsTest.toStringNeverLeaksAuthKey | ✅ 成立 |
| B3 | auth key 不进自动备份 | 备份外泄 | manifest allowBackup=false + dataExtractionRules（全局覆盖 prefs 与 files） | app/src/main/AndroidManifest.xml:12-14 | ✅ 成立 |
| B4 | auth key 入网成功即清 | key 长期滞留增加泄露面 | Connected 后 clearAuthKey；凭 filesDir 持久状态免 key 重连 | TunnelManager.kt clearAuthKey；TunnelManagerTest.connectWithKeyJoinsClearsKeyAndConnectsIdempotently | ✅ 成立 |
| B5 | 每 RFC/每帧凭据校验在 serve 形态下成立 | serve 转发后对端恒 127.0.0.1，若按来源地址信任会被绕过 | 凭据校验一律按 Bearer 设备 token（与来源无关）；stream 每帧 Authenticate；250ms 空闲撤销复查 | bridge/internal/gateway/http.go:184-224；stream.go relay validate；grep 全库无按 RemoteAddr 的信任逻辑（bridge-tailnet-serve 变更核对确认，见 docs/qa/tailnet-serve/validation.md 差异核对表） | ✅ 成立 |
| B6 | 撤销即时性不变 | 撤销后仍可访问 | 新请求立即拒 + 空闲连接周期关闭；serve 转发不改变该语义（设备 token 校验与连接来源无关） | bridge/README「Active streams」段；spec bridge-private-transport pairing-lifecycle；本里程碑零改动 | ✅ 成立（既有） |
| B7 | Host/X-Forwarded-For 取址正确性 | serve 形态下取对端 127.0.0.1 导致限流/日志错乱 | bridge 不记地址日志（无取址面）；pair 限流按 RemoteAddr 分桶经 serve 收敛为全局 10 次/分（更严格非削弱，已文档化）；App 侧 LoopbackProxy 按 upstreamBase 改写 Host | bridge/README Tailnet 段说明；docs/qa/tailnet-serve/validation.md「serve 形态差异核对」表；LoopbackProxy.kt Host 改写 | ✅ 成立（差异已文档化） |
| B8 | 未认证端点空 404 | 端点枚举/信息泄露 | 已知与未知路径一律空 404；serve 转发不改变（404 由 bridge 生成） | spec bridge-private-transport allowlist-forwarding unknown-endpoint-404；tailnet-serve QA 匿名根路径 404 记录（lan-validation 既有） | ✅ 成立 |

## C. 隧道与网络栈边界

| # | 边界 | 威胁 | 机制 | 证据 | 结论 |
|---|---|---|---|---|---|
| C1 | 隧道流量不出本机回环面 | App 其他流量误走隧道 | 单一 TunnelRoutes 前缀改写（仅 serveBase 命中，含边界判定防 :30910 碰撞）；未连接/不命中恒等 | TunnelIntegration.kt rewrite；TransportTunnelRouteTest.rewriteIsIdentityWithoutMappingOrForOtherHosts | ✅ 成立 |
| C2 | SOCKS5 回环认证 | 本机他 App 使用节点代理 | RFC1929 用户名 tsnet+随机口令；平台栈不做真实认证（实证）故 App 自实现 | loopback/Socks5.kt 注释（抓包实证）；spike 仪器测试经该路径 | ✅ 成立 |
| C3 | TLS 端点校验 | 中间人冒充 serve | SSLSocket 显式 endpointIdentificationAlgorithm=HTTPS（含主机名） | loopback/Socks5.kt layerTls（endpointIdentificationAlgorithm="HTTPS"，Socks5.kt layerTls 处） | ✅ 成立 |
| C4 | SELinux/netlink 与缓存目录 | tsnet 在 app 沙箱启动失败或状态外泄 | 接口快照注入 + XDG_CACHE_HOME 指向 App 私有缓存 | tstunnel/interfaces.go、tstunnel.go NewNode；单测随模块 | ✅ 成立 |
| C5 | 隧道未连接时行为与无隧道一致 | 集成引入回归 | 恒等改写 + 传输路径语义未变 + 全量单测回归 | ts-tunnel-integration delta tunnel-idle-keeps-existing-paths；282 项单测 | ✅ 成立 |

## D. 未覆盖与后续变更建议（本变更不修）

1. **App 内对话框配对/导入全程真机取证未完成**（模拟器 uiautomator 不稳定）：见
   docs/qa/ts-tunnel-integration/validation.md §4 与 docs/qa/ts-token-profile/validation.md §4——
   建议后续变更：真机（Pixel）全链路验收（导入带 tailscale 字段资料 → 隧道 up → 配对 → 会话/审批/导出）。
2. **tag 专用化**：实测入网 key 挂 tag:pca-client（用户现有 key），milestone 计划的 tag:dsh-mobile
   专用 tag（与其他项目分开、可单独吊销）未落地——建议随真机验收变更一并处理（管理台生成专用 key）。
3. **WS 经隧道的 101 会话级取证**：Upgrade 透传有 JVM 双向帧测试 + 真机 404 往返证据，但完整
   stream 会话（open/item/end 帧流）在隧道上的真机取证未做（同第 1 项真机验收覆盖）。
4. **DERP-only**：spike 流量经 DERP 中继，节点直连路径未验证（同真机验收）。
5. **dsh-bridge 代发 key**（管理台 API 集成）明确不在范围，如需要另立变更评审凭据面扩大。
