# 遗留事项台账

2026-11-01 首次按当前源码与历史 QA 汇总。`open` 表示尚需工作，不等于已确认缺陷。现有历史计划中的未勾选框不作为未实现的唯一依据。

| 编号 | 状态 | 现象 / 影响 | 下一步 | 证据 |
| --- | --- | --- | --- | --- |
| OI-001 | open | SDK/Gradle/Go/ADB 已可用：Android 131 项单测、Debug 构建和前一轮 Go race/vet 已实跑通过；真机通道已打通：adb reverse tcp:3090 加同证书的 .qa 覆盖安装（保留配对数据），归档全流程已在 Pixel 上验收；lint 已复验：0 errors / 84 warnings，全部为历史建议类（UseKtx/SetTextI18n 等），无 error。 | 按同样的 adb reverse + .qa 覆盖安装流程继续验剩余 UI（见 [归档验收](../qa/session-archive/validation.md)）；本机 dshbridge 需用当前源码重建才支持新 RPC。 | [Q1c 验证](2026-11-01-4.md)、[数据层验证](2026-11-01-2.md)、[构建配置](../../app/build.gradle.kts) |
| OI-002 | open | 计划审阅、多选、timed → continued 有源码/单测/手机注入验证，真实 Host 发出和调度尚未逐项端到端复测。 | 在隔离 QA 会话补真实 Host 三类交互与重连验收，保持任务与回答身份关联。 | [交互验收](../qa/interaction-parity/validation.md) |
| OI-003 | open | 源码为 1.1.2/code 8，公开 APK 仍为 v1.1.0；新包名迁移后的正式签名发行尚未交付。 | Release 构建已验证可产出 unsigned APK（1.7MB，R8/lintVital 通过）；下一步只剩签名、应用身份与数据迁移边界。 | [构建配置](../../app/build.gradle.kts)、[PR #4](https://github.com/Venompool888/deepseek-harness-mobile/pull/4)、[公开发行](https://github.com/Venompool888/deepseek-harness-mobile/releases/tag/v1.1.0) |
| OI-004 | open | 公网部署、开机托管及非 macOS arm64 目标运行未验收；固定 Desktop Cookie 不自动续期，历史 LAN 运行状态不能代表当前可用。 | 选定部署目标后核对真实 Host、认证更新与服务托管，再做实际 HTTPS 外部链路验收。 | [bridge 说明](../../bridge/README.md)、[私有传输验收](../qa/private-transport/validation.md) |
| OI-005 | open | 会话归档/恢复入口和工作区长按改名/删除已实现，Q9 Android 131 项与 Go race/vet 通过；相关 UI 真机未验收，活动详情确认、排序、队列编辑/删除、附件上传/重试、workspace 排序和 preset 管理仍缺原生入口。 | 等总监派下一步；在匹配构建/设备环境补工作区改名/删除且保留会话、归档/恢复及仅剩归档项的真机验收，排序等另行安排。 | [Q9 验证](2026-11-01-8.md)、[Q1c 验证](2026-11-01-4.md)、[更新后功能清单](../../harness-feature-parity.md) |
| OI-006 | open | 完整目标编辑、子代理树、jobs、schedules、workflow、交互终端、交付物、skills 和通用设置仍缺原生面板；私有模式仅能到服务端电脑本地 Web 操作。 | 按实际移动工作流分批选择功能域；保留私有桥接不代理原生 Web 的边界。 | [功能清单](../../harness-feature-parity.md)、[Web 入口限制](../../app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt#L4974) |
| OI-007 | open | 完整 TalkBack、少数小于 48dp 的触控区、更广设备验收、本地化和长历史性能仍有遗留。 | 无障碍：先按 [触控区静态审计](../qa/a11y-touch-targets/audit.md) 修 34dp 的活动折叠行（建议 TouchDelegate），再补 TalkBack 与设备验收；长会话性能需真机测量。 | [核心验收限制](../qa/dsh-rc2-core/validation.md)、[视觉验收边界](../qa/web-visual-fidelity/validation.md) |
| OI-008 | open | 未确认发送的去重 ID 仅存内存，进程销毁后的持久恢复尚未实现或验证。 | 明确进程死亡后的发送恢复需求，再设计持久化与服务端确认机制，避免重复接纳。 | [发送身份实现](../../app/src/main/java/app/deepseek/harness/mobile/HarnessPromptSubmission.kt)、[合并前验证边界](../qa/merge-readiness/2026-10-12.md) |
| OI-009 | open | Q10 session/delete 的接口、历史/目录副作用与恢复语义待确认；固定 rc.2 session-controller 的 service/commands/types 和本机 0.2.1-rc.5 安装包方法表均未发现该端点。尚未实现会话删除、未开放白名单。 | 总监提供实际方法表条目或实现路径后，确认合同并继续原 Q10 范围；不假设删除不可恢复。 | [合同核验与 REA 证据](../qa/session-delete-contract/validation.md)、[本轮记录](2026-11-01-9.md) |

## 已解决

| 编号 | 状态 | 怎么解决 | 记录 | 怎么确认 |
| --- | --- | --- | --- | --- |
| OI-011 | resolved | 暗色界面下 AlertDialog（菜单、确认、重命名输入框）仍是白底黑字，与 App 深色调色板和 DSH Web 不一致。 | 已由 Q13 修复：基主题指定 android:alertDialogTheme 深色弹窗，浅色偏好时用 overlay 切回；输入框改用 Dialog 上下文。 | [Q13 开发记录](2026-11-01-11.md)、[真机截图](../qa/session-archive/device/08-dialog-after-fix.png) |
| OI-010 | resolved | 会话地址未解析时长按菜单静默无反应：新连接后大多数会话处于该状态，用户会以为长按失效。 | 已由 Q12 修复：未解析先异步解析并提示，成功后弹菜单；subagent 仍不给菜单。 | [Q12 开发记录](2026-11-01-10.md)、[真机验收](../qa/session-menu-resolution/validation.md) |
