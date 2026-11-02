# Q5d / Q5a 排序验收

2026-11-02 · Pixel 10（`57151FDCR006YV`）· 包 `app.deepseek.harness.mobile.qa` 1.1.2-qa
证据图在 `.qa-evidence/q5-reorder/`（本地，不入库）。

## 环境

- 本机 dshbridge 用当前源码重建后重启（`~/.local/opt/dshbridge-2026-11-02/dsh-bridge`，PID 53798），
  白名单已包含 `workspace/insertBefore` 与 `workspace/insertSessionBefore`。
- `adb reverse tcp:3090 tcp:3090`，手机通过桥连本机 host。

## 工作区排序（Q5d）：通过

| 步骤 | 结果 | 证据 |
| --- | --- | --- |
| 长按工作区标题 | 菜单出现 Move up / Move down / Rename / Delete workspace；第一项只有 Move down | `05-workspace-menu.png`、`07-workspace-menu.png` |
| 点 Move down | 该工作区下移一位，抽屉立即重排 | `08-after-move-down.png`、`10-order-after-move.png` |
| 再次 Move down | 顺序继续变化（说明每次都走 Host） | `10-order-after-move.png` |
| force-stop 后重启 | 新顺序保持 | 见下方"重启后顺序" |

重启后的顺序：`Shakespeare restoration` → `deepseek-harness-mobile` → `3506 复习` → ……（移动前是 deepseek 在前）。
之后用 Move down 把 `Shakespeare` 移回原位，顺序恢复成 `deepseek-harness-mobile` → `Shakespeare restoration`。

## 会话排序（Q5a）：通过

| 步骤 | 结果 | 证据 |
| --- | --- | --- |
| 展开 `yuri` 工作区（host 里 25 条会话，磁盘 30 个目录含 5 个 subagent） | 抽屉里能看到全部普通会话，往下滚动还有 Seek、玄离99、Siri、内存排查、UQ 考试、SSH、菲律宾节点、Playwright MCP、微信、语音提醒等 | — |
| 长按 `Codex 远程连接与 DSSH 插件` | 菜单出现 Move up / Move down / Rename / Fork session / Archive | `13-session-menu.png` |
| 点 Move up | 该会话上移一位（安卓 → **Codex** → Playwright），抽屉立即重排 | `14-after-session-move.png` |
| 再点 Move down | 回到原顺序（安卓 → Playwright → Codex），已还原 | `15-restored-order.png` |
| 单条会话的工作区 | 菜单只给 Rename / Fork / Archive，Move 项正确隐藏 | `12-session-menu-single.png` |

注意：第一次排序后抽屉会从"最近更新"切到"手动"，此时未动过的会话也会按 Host 存的顺序重排，
看起来像一次大洗牌——这是有意行为（Host 持久顺序），不是错乱。

## 顺带发现（已记 OI-012）

会话正在流式输出时，长按抽屉里的行**经常不触发**（同一位置等流式结束后 900ms 长按照常弹出菜单）。
高概率是主线程被逐字渲染占满，长按的 Handler 回调被推迟到手指抬起之后。
不是本次改动引入的，但会影响"想趁 AI 输出时操作列表"的场景。

## 复现命令

```
adb reverse tcp:3090 tcp:3090
./gradlew :app:assembleDebug -PqaInstall=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 抽屉 → 长按工作区标题 → Move down / Move up
```

## 2026-11-02 13:05 入口撤下（保留能力）

Yuri 决定改用「长按拖动」交互，先撤掉菜单入口：

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 长按工作区 | 只有 Rename / Delete workspace | `16-workspace-menu-no-move.png` |
| 长按会话 | 只有 Rename / Fork session / Archive | `17-session-menu-no-move.png` |

RPC、`HarnessApi` 方法、bridge 白名单和 8 项 API 单测都保留，等拖动交互复用。

排序：抽屉回到「最近更新」（`drawer_order_updated = true`），按 `updatedAt` 倒序。
Host 的 `session/list` 与 `session/projections` 都没有 assistant 回复时间字段，所以"按最后一条 AI 回复排序"
目前只能用 `updatedAt` 近似——只发用户消息、还没回复的会话仍会靠前。
