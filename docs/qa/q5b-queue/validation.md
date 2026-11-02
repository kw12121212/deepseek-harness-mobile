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
