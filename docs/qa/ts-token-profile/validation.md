# ts-token-profile 验证记录（tailscale-private-transport）

验证目标（planned change `ts-token-profile`）：连接资料携带 token 参数（tailscale 字段），导入即
非交互入网；key 卫生（不日志/不错误信息/不回显/不备份）。

## 1. JVM 单测（281 项全量 0 失败，新增 8 项）

`TailscalePairingParamsTest`（app/src/test/.../TailscalePairingParamsTest.kt）覆盖：

- 合法 tailscale 字段解析（origin/authKey/hostname 三参数齐）；
- 无 tailscale 字段 → null（既有资料解析回归不变）；
- hostname 可缺省；origin 非 https 拒绝；authKey 空/超长（>256）拒绝；hostname 非法拒绝；
- `applyTo(store)` 写入 serverBase/authKey/hostname（假 store 断言）；
- `toString()` 不泄露 authKey。

## 2. 分层证据（沿已归档变更）

本变更的「导入 → 隧道 up → 配对经隧道」链路由三层已验证设施叠加而成：

| 层 | 证据 |
| --- | --- |
| 资料解析与写入 | 本变更 JVM 单测（上） |
| applyTo 后 ensureUp → Connected | ts-tunnel-integration：模拟器实测 logcat `tunnel.connected node=dsh-emu-app` + 设置页截图（docs/qa/ts-tunnel-integration/） |
| 配对 POST 经隧道（SOCKS5 回环 → serve → bridge） | ts-embed-spike 仪器测试（同 AAR 同路径实测 pair + session/list 成功，docs/qa/ts-embed-spike/） |

配对 worker 的调用序（applyTo → ensureUpFor → pair）为纯代码路径（BridgeSetupDialog.kt），
无新分支；ensureUp 未达 Connected 时继续 pair，失败沿用既有文案（均不含 key）。

## 3. key 卫生落实点

- 日志：TunnelManager 仅打 `present/空`（既有）；本变更未新增任何 key 相关日志；
- 错误信息：parseConfig 校验异常文本不含 key 内容；pair 失败文案集不变；
- UI 回显：设置页 key 输入 inputType=PASSWORD（密文）；导入资料后无需手输；
- 备份：manifest `allowBackup=false` + dataExtractionRules（全局，key 与设备凭据同覆盖）；
- 入网成功即清 key（TunnelManager 既有，单测锁定）。

## 4. 模拟器端到端（如实记录：未完成自动化取证）

与 ts-tunnel-integration 相同的限制：该模拟器会话的 uiautomator 树不稳定
（仅能 dump 出 IME 窗口），带参输入未能可靠核验，**不声称在 App 内完成导入→配对的全程取证**。
列为真机补验清单第二项（第一项为 integration 的对话框配对）。

## 5. 评审修复记录

独立评审（fresh-context）发现并已修复：`TailscalePairingParams.toString` 默认实现携带
authKey（当前无调用点，属潜伏泄漏面——已覆写为只含 origin/hostname 并补测试断言）；跨
server 重导入时 ensureUp 幂等短路导致旧映射在役、新 key 滞留、pair 直连出站（已加
installedFor 判定：serverBase 变更且在役即 restart）；authKey 显式 null 拒收（防 org.json
两套实现行为分裂）；设置页 keyInput 对齐既有 secret 字段的 isSaveEnabled=false +
autofill 排除。评审另确认无 MUST 级问题、类型混淆无走私路径。

## 结论

token 参数化连接资料（用户硬性要求的非交互入网）在解析、写入、卫生约束上完成并有单测；
链路其余层有既有实测证据。安全评审（security-completeness-review）将把 key 卫生纳入边界清单复核。
