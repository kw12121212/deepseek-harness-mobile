# 工作队列（项目总监维护）

最后更新：2026-11-01 · 维护者：DSH 总监会话，Codex 与 agy 执行。

一个条目 = 一个能独立提交、独立回滚的交付物。状态用 queued / in-progress / review / done。
编号与 `docs/devlog/OPEN-ISSUES.md` 的 OI 对应，不另起一套。

## 角色分工

| 角色 | 负责 | 不负责 |
| --- | --- | --- |
| 总监（DSH 会话） | 拆任务、写验收标准、跑测试、审 diff、提交、维护台账 | 大规模前端实现 |
| Codex（默认 `gpt-6-sol`，难点 `gpt-6.1-sol`） | 按 brief 实现并自测，交 diff | 改 AGENTS.md、push、动签名 |
| agy（Gemini 4 Pro） | Compose 界面、视觉、动效 | 后端、`bridge/`、构建脚本 |
| Yuri | 定优先级、决定 push | — |

## 环境基线（2026-11-01 实测）

- JDK 21.0.12 ✓，Android SDK（`~/Library/Android/sdk`，platforms + build-tools）✓，adb ✓，Gradle 9.4.1 已缓存 ✓。
- `./gradlew :app:testDebugUnitTest --rerun` → **98 tests, 0 failures**（XML 计数）。
- Go 未在 PATH，`cd bridge && go test -race ./...` 目前跑不了 → 归 OI-001。

## 队列

| # | 交付物 | 关联 | 执行 | 验收 |
| --- | --- | --- | --- | --- |
| Q1a | 会话归档数据层 + bridge 白名单｜**done** `3e5cd5d` | OI-005 | Codex 实现，总监复核 | 107 项单测 0 失败（总监复跑）+ go race/vet 通过 |
| Q1b | 归档入口（长按菜单 + 确认 + 归档后选下一个会话）｜**done** `6c55e41` | OI-005 | agy 12 分钟未落盘 → 改派 Codex | 114 项单测 0 失败（总监复跑）+ assembleDebug 通过；真机装机受签名冲突阻塞 |
| Q1c | 已归档折叠分区 + 取消归档｜**done** `85a33e9` + `931bd75` | OI-005 | Codex | 117 项单测 0 失败（总监复跑）；真机验收仍被签名冲突挡住 |
| Q6 | 抽屉单次 `session/list`（`sessionSnapshot()`）｜**done** `478a816` | OI-005 | Codex | 121 项单测 0 失败（总监复跑）；抽屉只发一次请求 |
| Q7 | 冷启动归档基线：`ensureArchiveBaseline()` 在列表渲染前就绪｜**done** `cc7ef1d` | OI-005 | Codex | 127 项单测 0 失败（总监复跑） |
| Q8 | workspace 改名（长按分组标题）｜**done** `3aaba7d` | OI-005 | Codex | 129 项单测 + gateway Go 测试通过（总监复跑）；RPC 形状已对 host 源码确认 |
| Q9 | workspace 删除（长按菜单：重命名/删除）｜**done** `96b544c` | OI-005 | Codex | 131 项单测 + gateway Go 通过（总监复跑）；host 语义已确认只移除注册 |
| Q2 | Go 工具链：`~/.local/opt/go1.27.3/bin/go`，`go test -race`/`vet` 已实跑｜**done** | OI-001 | 总监 | 5 个包全 ok，vet 无输出 |
| Q3 | 视觉回归脚本 `scripts/visual_diff.py`｜**done** | — | 总监 | 同图 0.000%、异图报差异百分比、尺寸不一致直接失败（已实测） |
| Q4 | PR 模板 + 一 OI 一分支约定落地｜**done** `69947fe` | — | 总监 | `.github/pull_request_template.md` 含 OI 关联、验证命令、回滚、no-push 检查 |
| Q5 | 按 OI-005 继续：队列编辑/删除、附件上传/重试 | OI-005 | Codex / agy | 同 Q1 |

## 每个条目的固定流程

1. 总监写 brief：范围、涉及文件、验收命令、明确不做什么。
2. 执行者实现并自测，只交 diff；不 push、不动 AGENTS.md。
3. 总监复跑验收命令 → 看 diff → 提交（message 写清路径/原因/验证/OI 编号）。
4. 台账 `OPEN-ISSUES.md` 与当日 devlog 同步更新，和代码同一个 commit 或紧随其后。

## 额度与节奏

- Codex 只做实现与方案，探索性读代码尽量由总监先做，brief 里直接给文件路径。
- 例行任务用 `gpt-6-sol` + `model_reasoning_effort=medium`；设计取舍或审查才升 `gpt-6.1-sol`。
- 单次任务控制在「一次能审完的 diff」（约 ≤300 行）；超了就拆。