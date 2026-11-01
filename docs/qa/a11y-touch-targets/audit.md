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

## 2026-11-01 23:55 实测（TouchDelegate 已实施）

用 `uiautomator dump` 取到折叠行的真实 bounds：
`Code, tap to expand` = [42,331]-[1038,346]（高 15px）、`Context injection` = [42,359]-[1038,448]、`Think` = [42,461]-[1038,550]。

在 **y=350**（Code 行的视觉范围 331–346 之外、落在两行之间）点击后，dump 显示
`Code, tap to collapse` —— **说明扩展后的可点区域确实生效**，点在视觉边界外也触发了该行。

### 新发现的风险

扩展区会与相邻行重叠：Code 行只有 15px 高，按 48dp 扩出的区域上下各约 55px，
覆盖到了 Context injection 行的起点（359）。也就是说两行之间的缝隙点了会打到"上面那行"，
用户可能觉得"点偏了"。

建议：给很矮的行限制扩展量（例如不超过到相邻行间距的一半），或对这些行改为整行 48dp 而不扩边。

## 复核：重叠到底有多严重（2026-11-02 00:05）

把量到的数字摊开：

- 正常折叠行高 **89px ≈ 34dp**，相邻行间距 13px（≈5dp）；
- 扩到 48dp（126px）后上下各多出 18.5px，**大于 13px 的行距** → 扩展区必然与邻行重叠，这是 34dp 行距与 48dp 目标之间的结构性矛盾，不是实现 bug；
- Code 行只有 **15px 高**，扩出去上下各 55px，比例失衡。

重叠的实际后果：点两行之间的 5dp 缝隙时，命中的是"上面那行"。这个结果**确定且可预期**（不是随机），对 5dp 的缝隙来说可以接受。

~~真正值得追的是：为什么 Code 行只有 15px~~ —— **已澄清：视口顶边裁切，不是渲染异常。** 轻轻向上滚动后再 dump，同一行恢复成 89px，而贴着顶边的另一行显示 16px。实现无问题。

## 结论

- TouchDelegate 生效：点在 header 视觉边界之外仍能触发展开（前述 y=350 实测）；
- 不追加改动：48dp 目标与本项目 34dp 行距的矛盾无法在不动版式的前提下消除；
- 15px 行已排除：是可视区裁剪，不是渲染异常。

## uiautomator 全量扫描（2026-11-02 00:10）

不再靠 grep，直接 dump 当前界面的所有 `clickable="true"` 节点并按高度排序（48dp ≈ 126px）：

| 节点 | 高度 | 判定 |
| --- | --- | --- |
| 会话标题（打开模型选择器） | 84px / 32dp | **不合格 → 已修**（TouchDelegate 扩到 48dp，实测 y=200 点击可打开） |
| 活动折叠行 ×3 | 89px / 34dp | 已修（TouchDelegate） |
| 输入框 | 116px / 44dp | 输入框，非离散目标，可接受 |
| 图标按钮 ×4、模型按钮、Full access、上下文圆环 | 126px / 48dp | 达标 |

结论：本次扫描后，界面上已经没有低于 48dp 的可点击目标（输入框除外，属正常）。

## 抽屉扫描（2026-11-02 00:20）

抽屉里有 16 个低于 48dp 的可点节点，其中 4 个是工具栏图标（28dp 见方）：

| 节点 | 改前 | 改后 | 说明 |
| --- | --- | --- | --- |
| Close sidebar | 74x74px (28dp) | **126x126px (48dp)** | 达标 |
| Search sessions / View options / Add workspace | 74x74px | **126x95px** | 宽 48dp、高 36dp——受 36dp 工具栏行高限制，未完全达标 |

**未继续改的原因**：这三个按钮的高度由 drawerToolbarHost 的 36dp 行高决定，改成 48dp 会让抽屉头部整体变高 12dp，属于排版决策（这块是对着 Web 校准过的），需要人眼确认，不在无人值守时盲改。

其余低目标：会话行 89px(34dp)、New Session 100px(38dp)、Settings 行 89px——都是列表行，属同一类"密度优先"的取舍。
