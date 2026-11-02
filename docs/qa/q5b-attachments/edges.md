# 附件边角验收清单（A 单）

2026-11-02 · 目标：把"能用"变成"敢用"。逐条真机执行，结果写回本文件。

## 环境

```
adb reverse tcp:3090 tcp:3090
./gradlew :app:assembleDebug -PqaInstall=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 1. 预览对话框

| 步骤 | 期望 |
| --- | --- |
| 选一张图 → 点缩略图 | 全屏对话框显示原图；背景点按关闭 |
| 关闭后草稿仍在 | 缩略图还在，未发送 |

## 2. 上传失败 + 重试

| 步骤 | 期望 |
| --- | --- |
| 杀掉本机 bridge（`kill <pid>`，3090 不再监听） | — |
| 选一个文件 | 卡片进入失败态，显示可点重试；发送被阻止 |
| 重启 bridge | — |
| 点重试 | 上传成功，卡片恢复正常；可发送 |

## 3. 超限提示

| 步骤 | 期望 |
| --- | --- |
| 构造 21 张图（或单测覆盖） | 第 21 张被拒，提示单条上限 20 张 |
| 选一个 >32 MiB 的文件（bridge 上传上限） | 明确提示"文件过大"，不是静默失败 |

## 4. 离场清理

| 步骤 | 期望 |
| --- | --- |
| 带未发送附件切到另一个会话 | 附件条清空；切回来不残留 |
| 带未发送附件切服务端 | 同上 |

## 记录

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 1 预览 | 通过：原图全屏显示，点背景关闭后草稿缩略图仍在。 | `/tmp/q5b-attach-edges/1-preview.png`、`1-closed.png` |
| 2 失败重试 | 通过：停 bridge 后文件卡显示失败及重试，发送禁用；按原 state/cookie 重启后重试成功，发送并在会话中显示附件。 | `/tmp/q5b-attach-edges/2-failed.png`、`2-retry.png`、`2-sent.png` |
| 3 超限 | 部分验证：40 MiB 文件被拒并提示“文件过大（最大 32 MiB）”；21 张未在真机完成，系统选择器单次限制 20 张，尚需跨批选择验证。`AttachmentsStateTest.rejectsImageAndFileLimits` 覆盖添加第 21 张抛错，但不证明真机提示。 | `/tmp/q5b-attach-edges/3-40m.png`；`app/src/test/java/app/deepseek/harness/mobile/AttachmentsStateTest.kt` |
| 4 离场 | 部分验证：带附件切换会话及切回，附件条均清空；切服务端未验证，设备连接列表仅有当前一个已保存连接。 | `/tmp/q5b-attach-edges/4-before.png`、`4-other-session.png`、`4-return-session.png`、`4-server-before.png`、`4-connection-list.png` |
