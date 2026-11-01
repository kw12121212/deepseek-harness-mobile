## 改了什么

<!-- 3 行以内：改的是行为，不是文件清单。 -->

## 关联

- OI：OI-xxx（见 `docs/devlog/OPEN-ISSUES.md`）
- 方案 / 计划：`plans/...`

## 验证

- [ ] `./gradlew :app:testDebugUnitTest --rerun` → 测试数 ___，0 失败
- [ ] `cd bridge && go test -count=1 -race ./... && go vet ./...`
- [ ] `./gradlew :app:assembleDebug`
- [ ] 真机（Pixel 10）：截图路径 ___
- [ ] 视觉对比：`python3 scripts/visual_diff.py before.png after.png` → ___%

## 影响面

- [ ] 仅文档  [ ] app  [ ] bridge  [ ] 构建 / 签名
- 是否改动 RPC 合同、bridge 白名单、applicationId：___

## 回滚

<!-- 一个 revert 够不够；有没有数据迁移或不可逆操作。 -->

## 提交检查

- [ ] `git status` 干净；按路径 `git add`，没有用 `-A`
- [ ] commit body 写了：改了哪几处、为什么、怎么验证的、关联 OI
- [ ] 没有 push（推公开仓库由 Yuri 决定）
