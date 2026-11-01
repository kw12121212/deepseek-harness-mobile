# 会话归档 — 验收记录

日期：2026-11-01 · 范围：OI-005 的「会话归档」部分（Q1a/Q1b/Q1c/Q6）

## 交付

| commit | 内容 |
| --- | --- |
| `3e5cd5d` | 数据层：按服务端隔离的 `archivedSessionIds`、`archiveSession`/`unarchiveSession`、bridge 精确白名单 |
| `6c55e41` | 长按菜单「归档」、二次确认、归档后选下一个可用会话（纯函数 `nextSessionAfterArchive`） |
| `85a33e9` | `archivedSessions()` 暴露已归档摘要 |
| `931bd75` | 抽屉底部「已归档 (N)」折叠分区，行长按「取消归档」 |
| `478a816` | 抽屉改用 `sessionSnapshot()`，一次 `session/list` |
| `cc7ef1d` | 冷启动缺陷：首屏渲染前先 `ensureArchiveBaseline()`，避免归档会话先冒出来 |

## 已验收（可复现）

> 数字以 2026-11-01 22:50 在 `fefd935` 上的复跑为准。

- `./gradlew :app:testDebugUnitTest --rerun` → **131 tests，0 failures**（总监在 `65b5236` 复跑，XML 计数）。
- `cd bridge && go test -count=1 -race ./...` → 5/5 包 ok；`go vet` 无输出（`3e5cd5d` 时验证）。
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL。
- `./gradlew :app:lintDebug` → 0 errors，84 warnings（全部为历史遗留建议类，无 error）。

## 未验收（阻塞）

**真机界面尚未验收。** `adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：
设备上已安装的 `app.deepseek.harness.mobile.debug` 由另一签名产生，与本机 debug keystore 不一致。
没有执行卸载（会清掉设备上的 App 数据），也没有改动签名配置。


## 真机为什么连不上（2026-11-01 22:55 实测）

不只是签名问题，链路本身没通：

- 设备上 `.qa` 包可调试，`run-as` 读到 `shared_prefs/deepseek_remote_preferences.xml`：
  `server_base_url = http://127.0.0.1:3090`——App 连的是**手机本机的 dshbridge**。
- `adb shell pm list packages` 里没有任何 bridge 包；`/proc/net/tcp` 里 3090（0x0C12）没有监听。
- 两台包（`.qa`/`.debug`）启动后都是 "Connection failed"，与上面一致。

结论：真机验收要同时满足两件事——① 能装上新构建（签名或新 applicationId），② 手机上跑起 dshbridge 并连得上上游（OI-004 的部署工作）。

## 解除阻塞后要跑的手工验收

1. `./gradlew :app:assembleDebug`，装到 Pixel 10（需要先解决签名冲突）。
2. 连上 host，抽屉里长按任一会话 →「归档」→ 确认 → 该会话消失、Toast「已归档」。
3. 归档**当前正在看的**会话 → 自动跳到下一条；只剩一条时归档 → 进空态而不是空白页。
4. 抽屉底部出现「已归档 (N)」→ 展开 → 长按 →「取消归档」→ 回到正常列表。
5. 切换服务端再回来 → 归档集合不串台（`archivedSessionsByServer` 按 URL 隔离）。
6. 截图存 `docs/qa/session-archive/`，与改动前对比：`python3 scripts/visual_diff.py before.png after.png`。

## 真机验收（2026-11-01 23:00，Pixel 10 / 57151FDCR006YV）

链路打通：`adb reverse tcp:3090 tcp:3090` 把手机 127.0.0.1:3090 指到本机 dshbridge；
用 debug 证书（与设备上 `.qa` 包同签名 658DE1B6…）以 `applicationIdSuffix = ".qa"` 装上去，
保留原有配对数据，版本 `1.1.2-qa`。

| 步骤 | 结果 | 证据 |
| --- | --- | --- |
| 长按会话 → 菜单 | ✅ 显示 Rename / Fork session / **Archive** | [01](device/01-actions-menu.png) |
| 点 Archive → 二次确认 | ✅ 文案 "Archive this session? It will be hidden from the session list." | [02](device/02-archive-confirm.png) |
| 确认归档 | ✅ 会话从列表消失；当前会话被归档后自动跳到另一条 | [04](device/04-after-archive.png) |
| 抽屉底部 | ✅ 出现 **Archived (1)** 折叠分区 | [03](device/03-archived-section.png) |
| 展开归档区 | ✅ 分区展开后列出已归档会话（标题 + 相对时间） | [03](device/03-archived-section.png) |
| 长按已归档会话 | ✅ 菜单只给 Unarchive | [05](device/05-unarchive-menu.png) |
| 取消归档 | ✅ 会话消失、Archived 分区随之隐藏 | [06](device/06-after-unarchive.png) |

注意：归档 RPC 需要 bridge 也更新。本机 dshbridge（state `qa-2026-11-01`）用当前源码重建后
`workspace/archiveSession` 才通；旧 bridge 直接返回 `Private server HTTP 400`——这正好证明
`allowed.go` 的精确白名单是生效的。

## 真机发现的两个问题

1. **未解析地址的会话，长按完全没反应**（不是禁用样式，是静默 return）。新连接后大多数会话都处于这个状态，
   用户会以为长按坏了。应先打开会话或让 `showSessionActions` 懒解析后再弹菜单。
2. **暗色主题下弹窗仍是浅色**（白底黑字），与 App 的深色界面割裂，也和 DSH Web 的深色对话框不一致。

## 已知取舍

- 归档会话在 `session/list` 里仍然存在，客户端本地过滤；host 侧是否真的隐藏未验证。
- 「排序」尚未做，本次只覆盖归档。