# Q5b 队列条验收

2026-11-02 · Pixel 10 · 1.1.2-qa
证据图：`.qa-evidence/q5b-queue/`（本地，不入库）。

## 合同

排队项来自 inbox projection 的 `next-turn[]` / `next-step[]`；`id` 是 itemId，正文取 `content[]`。
三个动作走 `session/updateQueue`：`edit`（非空纯文本）/ `remove` / `steer`。

## 交互与验收

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 有排队项时输入框上方出现「n 条排队消息」，默认折叠 | 通过 | `last-collapsed.png` |
| 点标题展开，逐行显示 目标标签 + 正文预览 + 「⋯」 | 通过 | `font150-expanded.png`（同时验证 150% 字体不破版） |
| 含图片/文件的排队项显示「包含非文本内容」，无编辑入口 | 通过 | `font150-expanded.png` 第二行 |
| 「⋯」→ 编辑排队消息：预填现有文本，保存后刷新 | 通过 | `popup-after-polls.png`、`last-editor-exit.png` |
| 「⋯」→ 插话发送：先二次确认；会话未运行时置灰并说明原因 | 通过 | `running-confirm.png`、`steer-stopped-before-confirm.png` |
| 「⋯」→ 删除排队消息：直接执行，失败用上游措辞提示 | 通过 | `last-stopped-confirm.png` |
| 编辑/删除失败不清空本地列表（无乐观修改） | 通过（单测覆盖） | `QueueStripStateTest` |

单元测试：`QueueStripStateTest` 21 项（折叠切换、双语标签、非运行态只禁用 steer、reconcile 等）。

## 造队列的最短步骤

1. 在 DSH Web 打开同一会话，发送一个长任务（例如 `执行 sleep 60，结束后回复完成`）。
2. 运行中再发一条普通消息（队列模式）。
3. 手机打开该会话：输入框上方出现「1 条排队消息」。

## 已知缺口（OI-013）

手机在会话运行中时，发送键会变成「停止」，所以**没法从手机本身往队列里加消息**——只能从 Web 追加。
桌面端没有这个限制（运行中仍可排队）。这是下一步要补的输入侧能力。

## 2026-11-02 19:4x 运行中排队（OI-013）与删除缺陷（OI-014）

真机（Pixel 10，1.1.2-qa）逐步操作：

| 步骤 | 结果 |
| --- | --- |
| 运行中、输入框为空 | 按钮为停止图标（Stop current task） |
| 运行中，用输入法输入并上屏 | 按钮变成 Send message（`e50bd8b`） |
| 点发送 | 输入框清空、本轮**没有**被取消、输入框上方出现「1 queued messages」 |
| 队列条「⋯」→ Delete queued message | **失败**：Toast「Delete failed: this message may already have started sending.」（Host 返回 queue-item-not-found） |
| 队列条「⋯」→ Edit queued message | 编辑框能打开并预填，未提交保存（避免二次污染） |

截图：`queued-from-phone.png`、`delete-toast2.png`、`edit-dialog.png`。

复现：会话运行中打字发送 → 队列条出现一条 → 打开「⋯」→ 删除 → 观察 Toast。

## 2026-11-02 20:0x 删除闭环：根因是旧 bridge 二进制

排查结论：删除失败时客户端只拿到 `IOException: Private server HTTP 400`（bridge 没把响应体的错误码回传），
而根因是**本机 3090 上跑的 bridge 是白名单加入 `session/updateQueue` 之前编译的旧二进制**——该 RPC 直接被桥拒绝，
所以既不是 `queue-item-not-found`，也不是 id 取错。

处置：

1. 用当前源码重建 bridge 并重启（`go build ./cmd/dsh-bridge`，同 state / cookie 参数）；
2. 重新装当前 `.qa` 包；
3. 真机复测：运行中发送排队消息 → 队列条出现「1 queued messages」→ 展开 → 「⋯」→ Delete queued message → **队列条消失，无错误 Toast**。

截图：`delete-fixed-queued.png`（删除前）、`delete-fixed-after.png`（删除后）。

**部署注意**：只要 `bridge/internal/gateway/allowed.go` 变了（新增/移除 RPC），本机与远端部署的 bridge 都必须重建重启；
否则客户端会表现成"功能做完了但一点就报错"。这一条已写进 [bridge 说明](../../../bridge/README.md)。
