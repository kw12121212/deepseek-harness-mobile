# 工作队列（项目总监维护）

最后更新：2026-11-01 23:50 · 维护者：DSH 总监会话，Codex 与 agy 执行。

一个条目 = 一个能独立提交、独立回滚的交付物。编号与 `docs/devlog/OPEN-ISSUES.md` 的 OI 对应。

## 角色分工

| 角色 | 负责 | 不负责 |
| --- | --- | --- |
| 总监（DSH 会话） | 拆任务、写验收标准、跑测试、审 diff、提交、维护台账 | 大规模前端实现 |
| Codex（默认 `gpt-6-sol`） | 按 brief 实现并自测，交 diff | 改 AGENTS.md、push、动签名 |
| agy（Antigravity） | 设计评审、看图；**目前不适合大文件落地**（12 分钟零落盘，已实测） | 大仓库代码改动 |
| Yuri | 定优先级、决定 push | — |

## 环境基线（2026-11-01 实测）

- JDK 21.0.12 ✓，Android SDK ✓，adb ✓，Gradle 9.4.1 ✓，Go 在 `~/.local/opt/go1.27.3/bin/go`（不在 PATH）。
- `./gradlew :app:testDebugUnitTest --rerun` → **139 tests, 0 failures**。
- `cd bridge && go test -count=1 -race ./...` → 5/5 包 ok，`go vet` 干净。
- `./gradlew :app:assembleRelease` → 1.7MB unsigned APK，R8 + lintVital 通过。
- **真机验收通道（关键）**：
  1. `adb reverse tcp:3090 tcp:3090`，让手机 127.0.0.1:3090 指向本机 dshbridge；
  2. 本机 dshbridge 必须用当前源码重建（旧二进制不认新 RPC，会回 `Private server HTTP 400`）；
  3. 临时把 debug 的 `applicationIdSuffix` 改成 `.qa`（与设备上既有包同证书 658DE1B6…），`install -r` 覆盖，**配对数据保留**；
  4. 验完立刻 `git checkout -- app/build.gradle.kts`；换 suffix 后必须 `--rerun-tasks`，否则 Gradle 会装旧包。

## 已交付

| # | 交付物 | commit |
| --- | --- | --- |
| Q1a | 归档数据层 + bridge 精确白名单 | `3e5cd5d` |
| Q1b | 长按菜单「归档」+ 归档后选下一个会话 | `6c55e41` |
| Q1c | 「已归档」折叠区 + 取消归档 | `85a33e9` `931bd75` |
| Q6 | 抽屉单次 `session/list`（`sessionSnapshot()`） | `478a816` |
| Q7 | 冷启动前加载归档基线 | `cc7ef1d` |
| Q8 | workspace 改名（长按分组标题） | `3aaba7d` |
| Q9 | workspace 删除（只移除注册） | `96b544c` |
| Q11 | 归档基线不再在锁内做网络 I/O（总监亲手） | `607e422` |
| Q12 | 未解析地址先解析再弹菜单（OI-010） | `b595026` |
| Q13 | 弹窗跟随 App 深色调色板（OI-011） | `8775377` |
| Q2 | Go 工具链实跑 `-race`/`vet` | — |
| Q3 | `scripts/visual_diff.py` 视觉回归 | `6add7ef` |
| Q4 | PR 模板 | `69947fe` |

真机验收证据：`docs/qa/session-archive/device/`（8 张截图），归档全流程 6 步全过。

## 待办（需要 Yuri 定优先级）

| # | 内容 | 规模 | 备注 |
| --- | --- | --- | --- |
| Q5a | 手动排序会话（`workspace/insertSessionBefore`） | 中 | 拖拽或"上移/下移"菜单，需真机手感验收 |
| Q5b | 队列编辑/删除（`session/updateQueue`） | 大 | 目前 App 没有队列 UI，要从零做一个列表 |
| Q5c | 附件上传/重试（`session/attachment`） | 大 | 同上，涉及权限与进度态 |
| Q5d | workspace 排序（`workspace/insertBefore`） | 中 | 与 Q5a 同一套交互 |
| Q14 | 本地 QA 变体：用 Gradle 属性代替临时改 suffix | 小 | 省掉每次验机的手工改动 |
| Q15 | a11y：活动折叠行 34dp → TouchDelegate 扩到 48dp | 小 | 审计见 `docs/qa/a11y-touch-targets/audit.md` |

## 暂停

- **会话删除（Q10）**：rc.2 与本机 0.2.1-rc.5 都没有 `session/delete` 的 Remote 注册，合同不成立。证据 `docs/qa/session-delete-contract/`。拿到真实方法表再启动。

## 每个条目的固定流程

1. 总监写 brief：范围、涉及文件、验收命令、明确不做什么。
2. 执行者实现并自测，只交 diff；不 push、不动 AGENTS.md。
3. 总监复跑验收命令 → 看 diff → 提交（message 写清路径/原因/验证/OI 编号）。
4. 台账 `OPEN-ISSUES.md` 与当日 devlog 同步更新。
5. 能在真机上看的，一律装机截图；看不见的不算完成。

## 额度与节奏

- Codex 只做实现与方案；探索性读代码由总监先做，brief 里给文件路径和行号范围。
- 单次任务控制在一次能审完的 diff（约 ≤300 行）；超了拆。
- 截至 2026-11-01 23:50，本线程累计约 2500 万 input token（绝大多数命中缓存）、14.5 万 output。
