# deepseek-harness-android

Android 客户端（`app/`）+ 自研 Go 通信壳（`bridge/`，组件名 **dshbridge**）。
「dsh 服务端」与「dshbridge 服务端」的职责边界见 [server-roles.md](docs/architecture/server-roles.md)；
dshbridge 的命令与部署限制见 [bridge/README.md](bridge/README.md)。

## 提交

- Conventional Commits，英文 subject，单行祈使句并带范围，例：`refactor(mobile)!: ...`。
- 按主题拆提交，`docs/`、`bridge/`、`app/` 三类互不依赖源码，可以分别提交且各自可构建。
- 包名与 applicationId 是 `app.deepseek.harness.mobile`（2026-10-10 从 `cool.rin.deepseekremote` 改名，
  属 breaking change：对老安装是另一个应用身份）。
- 节奏：一个能独立回滚、且通过验证的改动 = 一个 commit，不要攒一整天。长任务每 30–60 分钟，
  或每过一道验证（单测 / 构建 / 真机截图）就落一次；台账和 devlog 跟同一节奏更新。
- message 的 body 写清楚：改了哪几处（按路径）、为什么改、怎么验证的（命令或截图路径）、关联的 OI 编号。
  半年后只看 commit 就能重建上下文，这才算达标。
- 本地 commit 随时可落；`git push` 到公开仓库由 Yuri 决定，不要自己推。

## 验证（提交前跑，别只跑 UP-TO-DATE）

- Android 单元测试：`./gradlew :app:testDebugUnitTest --rerun`（测试数量以当轮 XML 验证记录为准）。
  Gradle 要写 `~/.gradle`，在 DSH 沙箱里需要放宽文件权限；不加 `--rerun` 会直接 UP-TO-DATE 跳过。
- `bridge/` 的 Go 测试：`cd bridge && go test -race ./...`，再运行 `go vet ./...`。
  本机 Go 在 `~/.local/opt/go1.27.3/bin/go`，默认 PATH 里没有，先
  `export PATH="$HOME/.local/opt/go1.27.3/bin:$PATH"`。跑之前确认 `go version`；带缓存的结果不算通过，
  必要时加 `-count=1`。
- 真实设备验证走 `mobile-use` skill + ADB；Pixel 相关证据在 `docs/qa/`。

## 设计与 UI 技能（`.skill/`）

仓库里 `.skill/` 存了一批第三方 agent skills（本地副本，已 gitignore）。做 Android 界面、视觉、
交互相关工作前，**先读对应的 SKILL.md 再动手**——尤其是 M3 的尺寸、动效、配色，不要凭印象写数值。

| 任务 | 先读 |
| --- | --- |
| 定风格 / 配色 / 字体 / 版式，或评审整屏视觉 | `.skill/ui-ux-pro-max/SKILL.md`；可跑 `python3 .skill/ui-ux-pro-max/scripts/search.py "<query>" --domain style` |
| 写或改 Compose 界面、主题、Material 3 组件 | `.skill/compose-expert/SKILL.md` + `.skill/material-3/SKILL.md` |
| 平板 / 折叠屏 / 多窗口 / 横竖屏切换 | `.skill/adaptive/SKILL.md` |
| 状态栏、导航栏遮挡，输入法与 insets | `.skill/edge-to-edge/SKILL.md` |
| 从零设计一个新界面（用户旅程 → 布局 → 导航 → 无障碍） | `.skill/android-ui-design/SKILL.md`（会路由到同族 `android-ui-*`） |
| 需要精确 M3 数值（dp 几何、spring token、官方图） | `.skill/m3-expressive/references/` |
| 老 XML 页面迁 Compose | `.skill/migrate-xml-views-to-jetpack-compose/SKILL.md` |
| 源码解释不了的：已发布 APK 的行为、与 DSH 客户端逐版本对比、被依赖库内部实现 | `.skill/reverse-engineer-anything/SKILL.md`（REA，运行时在 `~/.local/share/rea-runtime`） |

- `.skill/` 是第三方内容（Apache-2.0 / MIT 等，许可见各目录），**不入库**；不要用 `git add -f` 绕过。
  别人克隆的机器上没有这个目录，缺失时按 `.skill/README.md` 里的仓库地址自行拉取，不要因此阻塞任务。
- skill 提供的是设计约束和平台规则；落地时以本仓库现有设计系统（`design/`、`app/` 的主题与资源）
  和真机验收为准，两者冲突时以真机为准，并把差异记进验收证据。
- 界面改动交付前：按「验证」小节跑测试，再用 mobile-use + ADB 截图看实际效果，别只信代码。
- 立项前先借力：新功能先用上面的 skill 出一版「界面草图 + 功能点清单 + 风险」再决定做不做，别直接开工。
- REA 只在源码说不了话时用（发布包行为、版本差异、第三方库内部）；读本仓库源码用普通工具，别拿它代替读代码。
  REA 的证据落 `docs/qa/<topic>/`，结论要能指到具体证据文件。

## 前端实现分工

- UI/前端代码（Compose 界面、主题、动效、无障碍、视觉细节）**优先交给 agy**：终端通过 agy 走
  Antigravity。模型用 `agy models` 现查（默认 `gemini-3.8-flash-high`；旧文档里的 Gemini 3.1/4 Pro 已不在列表）。
  调用方式见 `agy-subagent` 技能，统一走 `~/.local/bin/agy-run`，不要手拼 `agy` 命令行。
- 交给 agy 必须一次给全：目标界面截图或 DSH Web 对应页、相关 theme/资源与文件路径、要复刻的交互状态
  （空 / 加载 / 错误 / 深色）、验收标准（截图对比或测试）。它写完由父代理跑真机验收。
  run 证据（prompt、diff、agy 会话 id）在 `~/.dsh/tmp/agy-runs/`，出错先看那里。
- 后端、`bridge/`、协议与构建脚本不走 agy，父代理自己改并负责整合；agy 跑完先审 `changes.diff`，越界文件拒绝。

## 设计基准：DSH Web

移动端不是另做一个 App，是 DSH Web 的移动形态：信息架构、措辞、状态与视觉语言以 Web 为准。

- 功能差距看 [harness-feature-parity.md](harness-feature-parity.md)；视觉证据和「有意不同」清单在
  [docs/qa/web-visual-fidelity/](docs/qa/web-visual-fidelity/)。
- 改界面前先对照本机跑着的 DSH Web（默认 `http://127.0.0.1:62807`）的对应页面，逐项记「复刻 / 有意不同 / 待定」。
- 复刻不等于照搬：触屏手势、返回栈、系统栏、输入法必须按 Android 习惯，差异写进验收记录。

## 仓库约定

- 不入库（见 `.gitignore`）：`artifacts/`、`runs/`、`design-qa.md`、`chatgpt-interaction-audit.md`、
  `test-fixtures/`、`signing/`、`.env`、`.skill/`、`bridge/dist/`、`bridge/state/`。这些是本地证据、私钥、第三方技能包和部署状态，
  不要用 `git add -f` 绕过。
- 文档分层：规格 `docs/superpowers/specs/`、计划 `plans/`、设计 `designs/`，验收证据 `docs/qa/<topic>/`，
  架构说明 `docs/architecture/`。
- 提交前 `git status` 必须干净。**这个目录可能同时有别的会话在写文档**，所以按路径显式 `git add`，
  不要 `git add -A`；提交后立刻复查 `git status`，出现意料外的改动说明有并发写入者。

## Codex 子代理规则

- Yuri 的长期规则：仅使用非 Astra GPT-6 / GPT-6.1：`gpt-6.1-sol`（默认）、`gpt-6-sol`、`gpt-6-luna`。
  禁止 Astra 和 GPT-5.x，包括审查代理。显式指定允许的模型；未指定时必须验证实际模型。
- 本任务范围内可按需要派子代理调查、实现、测试、审查，无需再次询问。职责必须有界，父代理负责整合验收。
- 前端设计按 Yuri 的要求通过 `agy-subagent` 技能交给 agy；协议以原生 dsh 源码为准。
