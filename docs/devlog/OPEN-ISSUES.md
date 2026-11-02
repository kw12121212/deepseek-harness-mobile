# 遗留事项台账

2026-11-01 首次按当前源码与历史 QA 汇总。`open` 表示尚需工作，不等于已确认缺陷。现有历史计划中的未勾选框不作为未实现的唯一依据。

| 编号 | 状态 | 现象 / 影响 | 下一步 | 证据 |
| --- | --- | --- | --- | --- |
| OI-001 | open | SDK/Gradle/Go/ADB 已可用：Android 131 项单测、Debug 构建和前一轮 Go race/vet 已实跑通过；真机通道已打通：adb reverse tcp:3090 加同证书的 .qa 覆盖安装（保留配对数据），归档全流程已在 Pixel 上验收；lint 已复验：0 errors / 84 warnings，全部为历史建议类（UseKtx/SetTextI18n 等），无 error。 | 按同样的 adb reverse + .qa 覆盖安装流程继续验剩余 UI（见 [归档验收](../qa/session-archive/validation.md)）；本机 dshbridge 需用当前源码重建才支持新 RPC。 | [Q1c 验证](2026-11-01-4.md)、[数据层验证](2026-11-01-2.md)、[构建配置](../../app/build.gradle.kts) |
| OI-002 | open | 计划审阅、多选、timed → continued 有源码/单测/手机注入验证，真实 Host 发出和调度尚未逐项端到端复测。 | 在隔离 QA 会话补真实 Host 三类交互与重连验收，保持任务与回答身份关联。 | [交互验收](../qa/interaction-parity/validation.md) |
| OI-003 | open | 源码为 1.1.2/code 8，公开 APK 仍为 v1.1.0；正式签名发行尚未交付。2026-11-02 Yuri 定：**不做老应用数据迁移**——新版是独立身份，用户重新配对即可。 | 只差签名与发布流程：生成 keystore、配 `signing/`、打 signed release、更新 README 与 release notes（说明需重新配对）。 | [构建配置](../../app/build.gradle.kts)、[PR #4](https://github.com/Venompool888/deepseek-harness-mobile/pull/4)、[公开发行](https://github.com/Venompool888/deepseek-harness-mobile/releases/tag/v1.1.0) |
| OI-004 | open | 公网部署、开机托管及非 macOS arm64 目标运行未验收；固定 Desktop Cookie 不自动续期，历史 LAN 运行状态不能代表当前可用。 | 选定部署目标后核对真实 Host、认证更新与服务托管，再做实际 HTTPS 外部链路验收。 | [bridge 说明](../../bridge/README.md)、[私有传输验收](../qa/private-transport/validation.md) |
| OI-005 | open | 会话归档/恢复、工作区长按改名/删除已实现并在 Pixel 验收。2026-11-02 加了工作区/会话排序 RPC 与菜单，随后按 Yuri 决定把「上移/下移」入口撤下（能力保留，等长按拖动交互复用）；抽屉回到「最近更新」排序；Yuri 2026-11-02 定：排序就用 Host 的 `updatedAt`（方案 A），不再等 assistant 时间字段。队列条已完成（折叠列表 + 编辑/插话/删除，2026-11-02 真机验收）；活动详情确认、附件上传/重试、preset 管理仍缺原生入口。 | 等拖动排序设计定稿；其余按队列推进。 | [Q9 验证](2026-11-01-8.md)、[排序验收](../qa/q5-reorder/validation.md)、[功能清单](../../harness-feature-parity.md) |
| OI-006 | open | 完整目标编辑、子代理树、jobs、schedules、workflow、交互终端、交付物、skills 和通用设置仍缺原生面板；私有模式仅能到服务端电脑本地 Web 操作。 | 按实际移动工作流分批选择功能域；保留私有桥接不代理原生 Web 的边界。 | [功能清单](../../harness-feature-parity.md)、[Web 入口限制](../../app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt#L4974) |
| OI-007 | open | 2026-11-02 已把活动折叠行、顶部标题、抽屉各行（New Session / 工具栏 / 工作区 / Settings）、待办 header、设置弹窗关闭改成真正的 48dp 并在 Pixel 上复测通过（点两行缝隙不再误触上一行）；同日又把弹层里 44dp 的 `drillMenuRow` / `choiceMenuRow` / 完整访问弹窗复选框与按钮改到 48dp（Pixel 上菜单行 126px，`merge(oi007)`）。仍未达标：目录弹窗 42dp 行与 40dp 按钮、语言/外观弹窗 40dp 关闭键、模型凭据弹窗 36dp 按钮行、完整访问弹窗 40dp 关闭键；完整访问弹窗本身还没有真机截图。另加 TalkBack 全量走查、更广设备验收、本地化和长历史性能。 | 下一轮：收 40/42/36dp 的弹层目标并补完整访问弹窗真机截图；再做 TalkBack 走查与长会话性能测量。详见 [触控区审计](../qa/a11y-touch-targets/audit.md) 的 2026-11-02 各节。 | [核心验收限制](../qa/dsh-rc2-core/validation.md)、[视觉验收边界](../qa/web-visual-fidelity/validation.md) |
| OI-008 | open | 未确认发送的去重 ID 仅存内存，进程销毁后的持久恢复尚未实现或验证。 | 明确进程死亡后的发送恢复需求，再设计持久化与服务端确认机制，避免重复接纳。 | [发送身份实现](../../app/src/main/java/app/deepseek/harness/mobile/HarnessPromptSubmission.kt)、[合并前验证边界](../qa/merge-readiness/2026-10-12.md) |
| OI-009 | open | Q10 session/delete 的接口、历史/目录副作用与恢复语义待确认；固定 rc.2 session-controller 的 service/commands/types 和本机 0.2.1-rc.5 安装包方法表均未发现该端点。尚未实现会话删除、未开放白名单。 | 总监提供实际方法表条目或实现路径后，确认合同并继续原 Q10 范围；不假设删除不可恢复。 | [合同核验与 REA 证据](../qa/session-delete-contract/validation.md)、[本轮记录](2026-11-01-9.md) |




| OI-015 | open | bridge 拒绝 RPC 时只回 HTTP 400，响应体里的错误码没有传到客户端，排查时只能看到 `Private server HTTP 400`。 | 让客户端读取 4xx 响应体的 `error` 字段，或让 bridge 在回包中带上原因；同时把日志里的 code 打全。 | [队列条验收](../qa/q5b-queue/validation.md) |


## 已解决

| 编号 | 状态 | 怎么解决 | 记录 | 怎么确认 |
| --- | --- | --- | --- | --- |
| OI-011 | resolved | 暗色界面下 AlertDialog（菜单、确认、重命名输入框）仍是白底黑字，与 App 深色调色板和 DSH Web 不一致。 | 已由 Q13 修复：基主题指定 android:alertDialogTheme 深色弹窗，浅色偏好时用 overlay 切回；输入框改用 Dialog 上下文。 | [Q13 开发记录](2026-11-01-11.md)、[真机截图](../qa/session-archive/device/08-dialog-after-fix.png) |
| OI-010 | resolved | 会话地址未解析时长按菜单静默无反应：新连接后大多数会话处于该状态，用户会以为长按失效。 | 已由 Q12 修复：未解析先异步解析并提示，成功后弹菜单；subagent 仍不给菜单。 | [Q12 开发记录](2026-11-01-10.md)、[真机验收](../qa/session-menu-resolution/validation.md) |
| OI-012 | resolved | 流式输出时长按抽屉行弹不出菜单：逐字渲染把主线程排满，且抽屉每 ~250ms 重建行、换掉按住的那版 View。已由 `7e28669` 改成 Choreographer 单帧调度 + 稳定前缀缓存，并加触摸闸门在按住期间推迟重建、抬手后补一次；`f4ecfd6` 把追平预算调到 3.5s 保住逐字观感。 | [流式长按验收](../qa/streaming-long-press/validation.md)、[Issue #5](https://github.com/Venompool888/deepseek-harness-mobile/issues/5) | 170 项单测全绿；真机空闲 / running / 流式探测三种场景长按都能弹出菜单 |
| OI-013 | resolved | 运行中手机只能停止、无法排队：按钮只认 running，有草稿也照旧取消当前任务。 | 已由 e50bd8b 修复：composerAction(running, hasDraft) 决定图标/描述/点击，有草稿时发送走 queue 模式，长按按钮仍可停止。 | [队列条验收](../qa/q5b-queue/validation.md) | 真机：空草稿=停止图标、打字=发送图标、发送后本轮继续且出现「1 queued messages」；4 项单测 |
| OI-014 | resolved | 队列条删除排队消息失败，Toast 显示“可能已开始发送”。真实错误是 bridge 返回 HTTP 400：本机 3090 跑的 bridge 还是加入 `session/updateQueue` 白名单之前的旧二进制，该 RPC 被桥直接拒绝。 | 用当前源码重建 bridge 并重启后，真机走通“排队 → 删除 → 队列条消失”；客户端在收到 `queue-item-not-found` 时会丢弃旧 follow 重开快照，其他失败不再套用“已开始发送”的措辞。 | [队列条验收](../qa/q5b-queue/validation.md) | 真机删除成功、队列条消失；207 项单测全绿 |
| OI-016 | resolved | 历史图片加载失败：运行中的桌面 Host 比开源 master 旧，要求 `{request:{sessionId, attachmentId}}`，而 master 要求完整引用；按 master 发请求被 `gateway/input-invalid` 拒绝。 | 已由 `d332457` 修复：先按新契约发（引用只保留 strict codec 允许的字段），遇到 `gateway/input-invalid` 再退回旧的 id 形式；图片字节按 attachmentId 缓存，切会话/切服清空。 | [附件验收](../qa/q5b-attachments/validation.md) | 216 项单测全绿；真机发送图片后 force-stop 重开仍显示，滚动返回仍显示 |