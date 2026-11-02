# Q1 会话归档 + 排序：实现方案（草案）

- 状态：**待批准**（总监已抽查引用，见文末）
- 起草：Codex `gpt-6-sol`，2026-11-01，thread `01a196d0-c315-7ee1-aadf-edb3c8129dad`
- 关联：OI-005 · 队列 [queue.md](queue.md)
- 下一步：批准后按方案拆 2 个 commit 实现

---

## 实施方案（OI-005）

**现有入口与缺口**

- 抽屉已有搜索、分组和“手动／最近更新”选项；“手动”目前只保留 `session/list` 返回顺序，没有移动操作。[MainActivity.kt:1645](./app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt:1645)、[MainActivity.kt:1692](./app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt:1692)、[MainActivity.kt:3836](./app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt:3836)
- 会话长按菜单只有重命名、Fork；列表只过滤空白会话，未读取归档集合。[MainActivity.kt:3931](./app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt:3931)、[MainActivity.kt:3967](./app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt:3967)
- `workspace/follow` 已被调用，但只解析 `items`，丢弃基线中的 `archivedSessionIds`；`session/list` 本身仍会返回已归档会话。[HarnessApi.kt:168](./app/src/main/java/app/deepseek/harness/mobile/HarnessApi.kt:168)、[HarnessApi.kt:206](./app/src/main/java/app/deepseek/harness/mobile/HarnessApi.kt:206)、[DSH Host 合同](https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/dsh-v0.2.0-rc.2/packages/api/workspace-controller/src/types.ts)
- 刷新时的默认选中逻辑也未排除归档会话；仅过滤抽屉会导致它仍在主视图打开。[MainActivity.kt:2515](./app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt:2515)
- API 没有归档方法，RPC 参数封装和 bridge 白名单也没有相应方法。[HarnessApi.kt:420](./app/src/main/java/app/deepseek/harness/mobile/HarnessApi.kt:420)、[HarnessApi.kt:1074](./app/src/main/java/app/deepseek/harness/mobile/HarnessApi.kt:1074)、[allowed.go:3](./bridge/internal/gateway/allowed.go:3)

**API 与数据**

1. 在 `HarnessApi` 增加 `WorkspaceSnapshot(items, archivedSessionIds)`，让 `workspaces()` 返回完整基线；增加 `archiveSession(sessionId, stopActivity: Boolean = false)`、`unarchiveSession(sessionId)`。RPC 名称分别是 **`workspace/archiveSession`**、**`workspace/unarchiveSession`**，参数均包在 `{request: ...}`；归档请求含 `sessionId` 和可选 `stopActivity`，返回集合为 `archivedSessionIds`。[固定版本的 Host 类型定义](https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/dsh-v0.2.0-rc.2/packages/api/workspace-controller/src/types.ts)
2. 在 [allowed.go:4](./bridge/internal/gateway/allowed.go:4) 的 `allowedRPC` **只加上述两个精确条目**；不加通配符或 stream 条目。
3. 排序采用 DSH Web 的**客户端本地会话显示顺序**，为工作区、未分组及单一列表分别持久化完整 ID 序列；最近更新按 `updatedAt` 降序，时间相同用 ID 稳定排序。`workspace/insertSessionBefore` 是另一种 Host 持久排序合同，本轮不调用、不开放白名单。[Web 排序合同](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.0-rc.2/packages/client/ui-workspace/README.md)

**交互草图**

`抽屉 → 视图选项〔分组｜排序：最近更新/手动｜归档：隐藏/全部/仅归档〕 → 会话列表`

- 长按普通会话：重命名、Fork、归档；长按已归档会话：取消归档（可保留重命名、Fork）。已归档行置灰，点按提示“先取消归档”，不直接打开。
- 空闲会话直接归档并提供撤销。Host 返回 `workspace/session-active` 时，展示 Host 报告的运行工作及“停止工作并归档”确认；取消后保持原状。当前选中会话归档成功后，清除选择并导航至可见会话或新建态。
- 保留现有“排序方式”入口；手动模式下长按菜单提供“上移／下移”，仅移动同一显示分组内的可见会话，同时保留隐藏归档项在完整序列中的位置。手机用明确的移动按钮代替 Web 拖拽，避免和抽屉滑动、滚动手势冲突。
- “仅归档”无结果时显示“暂无已归档会话”和“查看其他会话”；搜索无结果仍用现有搜索空态。与 Web 相同的是三态归档筛选及恢复入口；差异是移动手势、没有桌面 hover 与快捷键。Web 的置顶分区及完整拖拽排序不纳入本轮。[Web 归档交互](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.0-rc.2/packages/client/ui-workspace/README.md)

**单元测试清单**

- API：解析 `archivedSessionIds`；两个 RPC 的精确名称、`request` 封装及 `stopActivity` 默认值；识别 `workspace/session-active` 错误码。
- 列表：隐藏／全部／仅归档，搜索与归档筛选叠加，空白会话过滤，归档当前会话后的选择回退；重连基线替换旧归档集合。
- 排序：最近更新时间与同时间稳定次序；手动上下移、跨分组拒绝、隐藏归档项保位；切换模式、进程重启、新增／消失会话后的顺序整理。
- bridge：只接受 `workspace/archiveSession`、`workspace/unarchiveSession` 的 RPC；相近名称和 stream 均拒绝。

**两个提交**

1. `feat(mobile): add session archive controls`：API、bridge 白名单、归档筛选与恢复 UI；附 OI-005 和路径、理由、验证记录。验收：`cd bridge && go test -race ./... && go vet ./...`、`./gradlew :app:testDebugUnitTest --rerun`，再做 Pixel 归档／恢复／运行中确认截图。
2. `feat(mobile): add manual session ordering`：本地顺序模型及抽屉移动入口，独立于归档 RPC，可单独回滚。验收：`./gradlew :app:testDebugUnitTest --rerun`，再做 Pixel 分组、单列、重启后顺序截图。两个提交均按路径暂存、提交后检查 `git status`；不推送。

**风险与边界**

- `HarnessRpc` 目前只保留远端错误码与消息，未保留 `workspace/session-active` 的 `activity` 详情；提交一需扩展错误解析，才能准确列出会被停止的工作。[HarnessRpc.kt:18](./app/src/main/java/app/deepseek/harness/mobile/HarnessRpc.kt:18)
- 归档集合与会话列表分两次获取，可能短暂不同步；刷新应以最新完整基线覆盖旧状态，失败时不把未知会话当作未归档。手动顺序属于本机偏好，不会跨设备同步。
- 本轮不做会话删除、置顶、跨工作区移动、工作区排序、Web 全量拖拽或后台持续订阅改造。以上是规划；**未改文件、未运行 Gradle 或测试**。

---

## 总监抽查（2026-11-01）

- `bridge/internal/gateway/allowed.go`：确认 `allowedRPC` 无归档相关方法，`workspace/follow` 只在 `allowedStream` → 方案说的缺口成立。
- `HarnessApi.workspaces()` 第 206 行确实只取 `value`，`archivedSessionIds` 未解析 → 成立。
- `MainActivity.kt:3931` 长按走 `showSessionActions`，只有重命名/Fork → 成立。
- 结论：可用。实现时按「只加两个精确白名单条目」执行，不做通配。
