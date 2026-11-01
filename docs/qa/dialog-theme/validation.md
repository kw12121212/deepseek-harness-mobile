# Q13 / OI-011 原生弹窗主题

2026-11-01；Pixel 10 / `57151FDCR006YV`，应用 `1.1.2-qa`，系统 `cmd uimode night` 前后均为 `no`。

## 根因复核

- Manifest 使用 `Theme.DeepSeekRemote`，各 API 级别的样式均继承 `Theme.DeepSeekRemote.Base`，其父主题为 `Theme.Material3.DayNight.NoActionBar`；无 values-night/styles.xml 或自定义 Application 夜间模式设置。
- 应用并非完全写死深色：`AppThemePreference` 支持 LIGHT/DARK/SYSTEM、默认 DARK；MainActivity 只据此选 AppPalette，没有调用全局/局部 night-mode API。因此应用配色偏好与平台 DayNight 可不一致。
- 当前导入的是 `android.app.AlertDialog`，原生 Builder 读取 `android:alertDialogTheme`，系统浅色时此前得到浅色弹窗；原先 `EditText(this)` 又直接使用 Activity 浅色上下文。

## 修复范围

- Base 主题显式设置 `android:alertDialogTheme`，使用原生 `Theme.Material.Dialog.Alert` 深色父主题，颜色取自已有 AppPalettes.DARK 的 menu/text/muted/drawerBlue。
- 浅色应用配色只应用一个覆盖 `android:alertDialogTheme` 的 LightDialogs overlay，保留浅色偏好，不强制 Activity 或系统 night mode。原有 SYSTEM 模式在配置变化时 recreate 的逻辑不变。
- 8 处弹窗输入框共用 `dialogEditText()`，从 Builder.context 创建，保留各字段显式样式，继承正确的前景色、提示色、激活色、光标与选区手柄。主输入区和抽屉搜索未改。
- 未增加依赖，未修改 manifest、bridge、AGENTS.md 或签名/仓库构建配置。

## 验证

- `./gradlew :app:testDebugUnitTest --rerun :app:assembleDebug` → BUILD SUCCESSFUL；26 份 XML 共 **139 项通过**，0 失败/错误/跳过。资源样式修复未增加镜像 XML 的测试。
- 临时 QA applicationId 覆盖构建通过并 `adb install -r` 成功，保留原桥接配对；日志 `/tmp/q13-dialog-theme-tests-build.log`、`/tmp/q13-dialog-theme-qa-build.log`。
- QA APK SHA-256：`951a87420309d1acd2f8dc02c9aebb29d79098c207814cbf795ef4919ae7e537`。
- 系统浅色、应用深色：实际打开会话菜单、归档确认及重命名框，逐张检查深色背景、浅色前景和按钮对比；重命名输入框聚焦后蓝色光标及选区手柄均可见。
- 仅取消弹窗，未确认归档、未保存标题；未修改系统夜间模式或应用主题偏好。

本机截图按 artifacts 本地证据约定不入库：

- `artifacts/q13-dialog-theme/00-before.png`：旧白底菜单。
- `artifacts/q13-dialog-theme/01-actions-dark.png`：修复后的深色菜单。
- `artifacts/q13-dialog-theme/02-archive-dark.png`：深色归档确认。
- `artifacts/q13-dialog-theme/03-rename-dark-cursor.png`：深色输入框、前景及可见光标。

应用浅色/SYSTEM 偏好原有单测通过；本轮真机观察固定在问题复现所用的系统浅色、应用深色组合。
