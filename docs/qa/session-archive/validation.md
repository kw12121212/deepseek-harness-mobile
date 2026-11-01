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

## 已验收（可复现）

- `./gradlew :app:testDebugUnitTest --rerun` → **121 tests，0 failures**（总监在 `65b5236` 复跑，XML 计数）。
- `cd bridge && go test -count=1 -race ./...` → 5/5 包 ok；`go vet` 无输出（`3e5cd5d` 时验证）。
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL。
- `./gradlew :app:lintDebug` → 0 errors，84 warnings（全部为历史遗留建议类，无 error）。

## 未验收（阻塞）

**真机界面尚未验收。** `adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：
设备上已安装的 `app.deepseek.harness.mobile.debug` 由另一签名产生，与本机 debug keystore 不一致。
没有执行卸载（会清掉设备上的 App 数据），也没有改动签名配置。

## 解除阻塞后要跑的手工验收

1. `./gradlew :app:assembleDebug`，装到 Pixel 10（需要先解决签名冲突）。
2. 连上 host，抽屉里长按任一会话 →「归档」→ 确认 → 该会话消失、Toast「已归档」。
3. 归档**当前正在看的**会话 → 自动跳到下一条；只剩一条时归档 → 进空态而不是空白页。
4. 抽屉底部出现「已归档 (N)」→ 展开 → 长按 →「取消归档」→ 回到正常列表。
5. 切换服务端再回来 → 归档集合不串台（`archivedSessionsByServer` 按 URL 隔离）。
6. 截图存 `docs/qa/session-archive/`，与改动前对比：`python3 scripts/visual_diff.py before.png after.png`。

## 已知取舍

- 归档会话在 `session/list` 里仍然存在，客户端本地过滤；host 侧是否真的隐藏未验证。
- 「排序」尚未做，本次只覆盖归档。
