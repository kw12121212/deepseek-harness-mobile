# Q5d + Q5a：工作区排序与会话排序

2026-11-02 · 总监派单 · 执行：Codex（gpt-6.1-sol）

## 合同（从本机 dsh 0.2.1-rc.5 的 app.asar 方法表核出，不是猜的）

```
workspace/insertBefore
  请求 { workspaceId, beforeWorkspaceId? }   // 省略 = 移到末尾
  结果 { workspaceIds: string[] }            // 完整最终顺序

workspace/insertSessionBefore
  请求 { workspaceId, sessionId, beforeSessionId? }
  结果 { workspace: { workspaceId, title, path, sessionIds } }
```

两条都是 `invocation.kind = "direct"`，不是 stream。此前 bridge 白名单**故意**没有开放它们
（`allowed_test.go` 把它们列在"不应允许"里）——本轮是有意开放，测试要同步改。

## 设计取舍

- **交互用"上移 / 下移"菜单，不做拖拽**：抽屉是 `ScrollView` + 手写 View，拖拽要自己实现命中与重排动画，
  收益低于风险；菜单项能覆盖同样的意图，而且真机上一个手指就能操作。
- **会话排序走 Host 持久顺序**（`insertSessionBefore`），不是本地偏好：换设备、重装、Web 端都一致。
  代价是必须切到"手动顺序"才能看见效果，所以排序成功后自动把 `drawerOrderLastUpdated` 置 false。
- **手动模式下按 `workspace.sessionIds` 渲染成员**：否则 Host 改了顺序、抽屉还按 `session/list` 的全局顺序排，
  用户会以为没生效。
- **不做跨工作区移动、不碰 pin**：合同里 pin 是另一条链路（`workspace/pinSession`），本轮不碰。

## 验收

| 层 | 命令 / 步骤 | 通过标准 |
| --- | --- | --- |
| 单测 | `./gradlew :app:testDebugUnitTest --rerun` | 全绿，数量 ≥139 |
| bridge | `cd bridge && go test -count=1 -race ./...`、`go vet ./...` | 包全 ok、vet 无输出 |
| 构建 | `./gradlew :app:assembleDebug -PqaInstall=true` | BUILD SUCCESSFUL |
| 真机（Pixel 10，`57151FDCR006YV`） | `adb reverse tcp:3090` → 装 .qa → 抽屉长按工作区/会话 | 顺序变化在重启 App 后仍然保持（Host 持久），失败时弹提示且顺序不变 |

## 状态（2026-11-02 12:30）

- 代码：`HarnessApi` 两个方法、bridge 白名单、`MainActivity` 菜单与手动顺序渲染已完成，147 项单测 + Go 5 包 race/vet 全绿。
- 真机：工作区排序通过（含重启保持）；会话排序在 yuri 工作区实测上移/下移并还原。
- 2026-11-02 晚些：Yuri 决定改用长按拖动，菜单入口已撤下（能力与单测保留）；抽屉回到「最近更新」排序。
- 发现：流式输出时长按抽屉行不触发，记 OI-012。

## 风险

- Host 若拒绝对未归档会话排序，会返回业务错误；UI 只负责 toast，不猜原因。
- 工作区在 host 侧顺序与抽屉展示顺序若本来不一致，第一次"上移"可能看起来跳一格——真机验收时记录。
