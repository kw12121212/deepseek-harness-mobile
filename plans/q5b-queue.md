# Q5b：队列编辑 / 删除（数据层先行）

2026-11-02 · 总监派单

## 合同（从本机 dsh 0.2.1-rc.5 的 app.asar 方法表核出）

```
session/updateQueue
  请求 { sessionId, itemId, action }
  action 三种：
    { kind: "edit", content: [{ type: "text", text }] }   // 非空纯文本替换
    { kind: "remove" }
    { kind: "steer" }                                     // 把排队项提前插入当前步骤
  说明："Mutate one still-pending queue occurrence, resuming a cold Agent first."
```

## 分两步

1. **数据层（本轮）**：确认排队项在客户端可见的数据来源（follow 帧 / projections），解析成模型；
   `HarnessApi` 加读取与三个动作；bridge 白名单加 `session/updateQueue`；单测覆盖解析与请求形状。
2. **UI（下一轮）**：输入框上方的队列条（有排队项时才出现），点条目弹「编辑 / 立即插入 / 删除」，
   编辑用现成对话框；真机验收。

## 状态（2026-11-02 15:0x）

- 数据层完成（`fdf59ee`）：排队项来自 inbox projection 的 `next-turn[]` / `next-step[]`，
  `id` 即 itemId，正文取 `content[]` 的 text 块；混合附件项 `text` 为 null（不做纯文本编辑）。
- `session/updateQueue` 已放行（direct，非 stream），三个 action 的请求体有单测。
- UI 未做：输入框上方的队列条、编辑/插入/删除入口，下一单。

## 风险

- 排队项可能只出现在 `session/control` 流里，而不是 follow 帧；若客户端根本拿不到 itemId，
  第 1 步要如实报告，不要自己编造 id。
- `steer` 会改动当前 turn 的投递目标，属于"对外生效"的动作，UI 上要给二次确认。
