# 触控区尺寸静态审计（OI-007）

日期：2026-11-01 · 方法：静态扫描布局参数与 minimumHeight，逐个复核命中点是否可点。**未在真机上测量。**

## 命中与判定

| 位置 | 尺寸 | 可点？ | 判定 |
| --- | --- | --- | --- |
| [MainActivity.kt:3309](../../../app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt#L3309) 活动折叠行 header（3373 行绑定点击） | `minimumHeight = dp(34)` | 是 | **不合格**。整行可点，但高度只有 34dp，低于 48dp 建议值；单行文本 + 图标，触摸时容易打偏 |
| [MainActivity.kt:833](../../../app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt#L833) 输入框 `minimumHeight = dp(44)` | 44dp | 输入框（非落点目标） | 可接受。点击目标是整个输入卡片，不是这 44dp |
| [MainActivity.kt:3322](../../../app/src/main/java/app/deepseek/harness/mobile/MainActivity.kt#L3322) 折叠行内图标 28dp | 28dp | 否（`isClickable = false`） | 无风险，点击由整行承担 |

其余 `minHeight = dp(48)` 的位置（1098/1125/1318、BridgeSetupDialog 全部按钮）已达标。

## 建议修法（未实施）

优先 **TouchDelegate**：保持 34dp 的视觉密度（与 Web 对齐的排版是刻意校准过的），把可点区域向上/下各扩 7dp。若 TouchDelegate 在滚动列表里表现不稳，再退回 `minimumHeight = dp(48)` + 负 margin 补偿，但要重跑视觉比对。

## 为什么没改

- 真机验收仍被 debug 签名冲突挡住（见 [OI-001](../session-archive/validation.md)），改完看不见效果；
- 这个行高是照着 Web 校准过的，盲改会破坏视觉一致性。

解除阻塞后：改 → `python3 scripts/visual_diff.py` 对比前后截图 → 真机上确认点击不再打偏。
