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

## 会话排序（Q5a）：只验到"没有邻居时正确隐藏"

| 步骤 | 结果 | 证据 |
| --- | --- | --- |
| 长按工作区里唯一一条会话 | 菜单只有 Rename / Fork session / Archive，**没有** Move up / Move down | `12-session-menu-single.png` |

**没验到的**：真正的两条会话互换。当前 host 上每个工作区都只有 1 条可见会话
（抽屉按工作区分组，每条会话的 cwd 各自独立），没有"相邻项"可以移动。
代码路径与请求体由 8 项单测覆盖（请求形状、可选字段省略、结果解析、失败不改状态），
真机互换要等有 ≥2 条会话的工作区，或另建两条一次性会话。

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
