# 功能完整性对照 — tailscale 链路（security-completeness-review）

以 `.strict-spec-driven/specs/` 全量规格 × tailscale 链路核对；结论三类：
**已验证**（证据指针）/ **未覆盖**（原因与计划）/ **有意不同**（理由）。基线对照
[harness-feature-parity.md](../../../harness-feature-parity.md)。评审日期 2026-11-03/04。

## 逐规格核对

| 规格 | 要求 | tailscale 链路下的状态 | 结论 |
|---|---|---|---|
| server-connection · two-connection-modes | direct + private 两模式 | 两模式保留；新增隧道作为可选承载（embedded-tailnet-tunnel）；配对/RPC 经隧道有分层证据 | ✅ 已验证：docs/qa/ts-embed-spike（pair+RPC 经隧道）、ts-tunnel-integration（tunnel.connected + WS 404 往返）、282 项单测 |
| server-connection · transport-security | 公网 HTTPS 约束 | 未变化（改写只发生在校验后的传输层）；隧道地址本身 https | ✅ 已验证：TransportTunnelRouteTest 恒等分支 + ServerConfig 校验未动 |
| server-connection · state-persistence | 强杀重开保留状态 | 未变化；隧道节点状态持久化（免 key 重连）为新增面 | ✅ 已验证（既有规格回归）；隧道侧：TunnelManagerTest.persistedStateAllowsKeylessReconnect |
| server-connection · dsh-version-lock | 两路径版本拒绝/提示 | serve 形态同样生效（bridge 启动拒绝） | ✅ 已验证：docs/qa/dsh-version-lock（真机 0.2.1-rc.2 双路径拒绝 + bridge 启动拒绝） |
| server-connection · embedded-tailnet-tunnel | 四态/回环承载/恒等不改写 | 实现如规格 | ✅ 已验证：ts-tunnel-integration QA（Connected 截图/logcat）+ TunnelManagerTest 九项 |
| server-connection · tailscale-token-profile | 资料带 token 参数非交互入网；key 不回显 | 解析/写入/卫生单测全绿；App 内导入全程未取证 | ◐ 部分：单测 + 分层证据（docs/qa/ts-token-profile）；App 内全程列未覆盖（见下） |
| bridge-private-transport · allowlist-forwarding | 白名单转发/空 404 | serve 转发不改变 | ✅ 已验证：tailnet-serve QA + 既有 spec 回归 |
| bridge-private-transport · pairing-lifecycle | 一次性配对码/撤销即时 | serve 形态下凭据校验与来源无关，语义不变 | ✅ 已验证：安全边界清单 B5/B6 |
| bridge-private-transport · no-plaintext-fallback | 不回落原生路由 | 未变化 | ✅ 既有（本里程碑零改动） |
| bridge-private-transport · upstream-version-gate | serve 拒绝不兼容上游 | 实现如规格 | ✅ 已验证：docs/qa/dsh-version-lock §2 |
| host-interactions（审批与前台问答） | 待审批/问答实时呈现 | 依赖 stream WS；Upgrade 透传有 JVM 双向帧测试与 404 往返实证，隧道上的真实审批会话未取证 | ◐ 部分：机制证据（LoopbackProxyTest 双向帧）；会话级列未覆盖 |
| live-agent-session（实时呈现） | 思考/写入/工具调用实时流 | 同上（同一条 stream 通道） | ◐ 同上 |
| session-management（会话与模型管理） | 列表/切换/模型选择 | RPC 面经隧道已证（spike session/list ok:true）；UI 级真机会话操作未取证 | ◐ 部分：spike 证据 + 未覆盖项 |

## 未覆盖项（显式列出）

1. **真机（Pixel）全链路**：导入带 tailscale 字段连接资料 → 隧道自动 up → 配对 → 聊天/审批/导出
   可用，强杀重开免 key 重连（milestone done_criteria 原文）。本轮全部模拟器级/单测级证据，
   真机验收未做（设备未连接；用户指示模拟器调试）。建议后续变更一次性收口（含 tag:dsh-mobile
   专用 key 与 DERP/直连两条路径）。
2. **隧道上的完整 stream 会话**（open/item/end 帧流 + 审批/问答交互）：机制层已证，会话层未取证。
3. **导出（export）经隧道**：RPC 与 stream 已证；export 路径走同一 transportRequest 改写点
   （代码同构），未单独取证。
4. **后台通知（TaskMonitorService）经隧道**：同样走 transportFor 改写点，未单独取证。

## 有意不同（vs DSH Web / 既有移动形态）

1. 隧道是**新增选项而非替换**：direct 与 bridge 公网 HTTPS 两条既有路径行为不变（规格
   tunnel-idle-keeps-existing-paths）。
2. 隧道形态下 App 的传输基地址为本机回环代理（平台 HTTP 栈不能走隧道/解析 MagicDNS 的硬约束），
   对用户不可见；Origin/Host 语义保持真实 origin。
3. 设置页隧道段为程序化 Views 内嵌区块（本 App UI 体系），不引入 Compose 新页面。
4. 版本锁为精确锁（0.2.0-rc.2）不做最低兼容放行（用户 2026-11-03 决策）。

## 与 harness-feature-parity.md 的关系

本里程碑不扩展 DSH Web 功能面（子代理树、后台任务、计划任务等仍为既有待办，见 milestone
scope.out）；tailscale 链路是承载层变化，功能对照以既有 parity 文档为准，未新增功能差距项。
