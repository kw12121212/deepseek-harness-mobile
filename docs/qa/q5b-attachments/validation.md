# 附件（图片 / 文件）验收

2026-11-02 · Pixel 10 · 1.1.2-qa

## 已验

| 场景 | 结果 |
| --- | --- |
| 点「+」选图片 | 系统 Photo Picker，可多选 |
| 选中后 | 输入框上方出现缩略图卡片，可移除 |
| 发送图片 | Host 收到图片块；消息里显示（用内存里的字节） |
| 选文件 | SAF `ACTION_OPEN_DOCUMENT` |
| 上传 + 发送文件 | 先 `fileUploads/upload` 拿 receipt，再以 `{type:"file", receiptId}` 发送；Host 收到 |
| 只发附件、不写文字 | 允许发送（`sendPrompt` 放宽了空文本限制） |

## 未验 / 已知缺陷

- **重新打开会话后，历史里的图片加载失败**（OI-016）：`session/attachment` 返回网关参数校验错误，图片显示不出来。
- 上传失败重试、超限提示、预览对话框只做了单测/代码覆盖，未逐个在真机上点过。

## 复现命令

```
adb reverse tcp:3090 tcp:3090
./gradlew :app:assembleDebug -PqaInstall=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
