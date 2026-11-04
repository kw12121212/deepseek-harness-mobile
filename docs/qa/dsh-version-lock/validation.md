# dsh 版本锁定验证（dsh-version-lock）

验证目标（planned change `dsh-version-lock`）：两条路径对不兼容上游的明确拒绝行为 + 探测机制的真实性。

环境（2026-11-03）：

| 组件 | 取值 |
| --- | --- |
| 本机 dsh Web Host | Desktop 自带 CLI `dsh web --host 127.0.0.1 --port 3080`，版本 **0.2.1-rc.2**（落地页 `__DSH_BOOT__` release.version 实测） |
| bridge | 本仓库现源码构建（含版本门禁） |
| app | debug 变体（applicationId `app.deepseek.harness.mobile.debug`）安装于 api31 模拟器，经 `http://10.0.2.2:3080` 访问宿主 |
| 锁定契约 | `0.2.0-rc.2`（app `COMPATIBLE_DSH_VERSION` 与 bridge `CompatibleDSHVersion` 各单处声明） |

## 1. 探测机制真实性（真机 dsh）

- 已认证 GET `http://127.0.0.1:3080/` → 200，HTML 内嵌
  `globalThis["__DSH_BOOT__"] = {"rev":"7cd59d6e8775","release":{"version":"0.2.1-rc.2","commit":"332895e"},...}`；
- 未认证 GET `/` → 401（68 字节）：探测必须走已认证客户端，bridge（token/cookie 交换后）与
  app（凭据交换后）在探测时点均已具备。

## 2. bridge serve 拒绝不兼容上游（真实状态目录 + 真实 dsh 0.2.1-rc.2）

```
$ ./dsh-bridge.exe serve --state <真实状态目录> --behind-tls-proxy --dsh-token-file <token>
upstream dsh version 0.2.1-rc.2 is not the pinned compatible version 0.2.0-rc.2;
refusing to start (moving to another version requires an explicit change and re-validation)
（退出码 1）
```

进程未监听、未提供任何 pair/RPC 入口。go 单测（`internal/upstream/version_test.go`、
`cmd/dsh-bridge/main_test.go`）另覆盖：匹配 0.2.0-rc.2 放行、无 `__DSH_BOOT__` 拒绝、
无 release.version 拒绝、请求失败拒绝、认证头携带、字符串内花括号解析。

## 3. app 直连拒绝文案（模拟器真机截图）

模拟器安装 debug APK，「Connect to Harness」→ Direct connection → 输入
`http://10.0.2.2:3080/?token=<本机 token>` → Test and connect：

```
The server runs dsh 0.2.1-rc.2, while this app is built for dsh 0.2.0-rc.2;
adapting to a new version requires an app update.
```

（uiautomator dump 文本 + 截图 [direct-reject-0.2.1-rc.2.png](direct-reject-0.2.1-rc.2.png)；
按钮恢复可用、连接未建立、配置未保存。）

版本门禁决策的四个分支由单测覆盖（DshVersionContractTest）：匹配放行、不匹配拒绝（携带上游
版本）、探测不到拒绝（文案「无法确定服务器 dsh 版本，按不兼容处理…」）、**未认证跳过门禁**——
裸地址首连探测得认证挑战（3xx/401/403）时不判兼容性，交由既有认证流程（unchanged_behavior：
评审发现首版实现误把未认证判为不兼容、阻断认证引导，已修复并以单测锁定）。bridge 侧超 1 MiB
落地页拒绝、未知上游错误信息断言、常量金丝雀同样已补测。

## 4. 匹配放行路径

单测以 httptest 假上游（`__DSH_BOOT__` version=0.2.0-rc.2）验证 bridge 放行 serve、
解析器提取正确；app 解析器单测（DshVersionContractTest，8 项）覆盖 pinned 提取与
全部 fail-closed 分支。真机 0.2.0-rc.2 上游放行由历史验证背书（0.2.1-rc.2 桌面已覆盖安装，
不再单独降级验证）。

## 5. 后续说明

本机 dsh 为 0.2.1-rc.2：本变更合入后，本机 direct 与 bridge 连接将被按设计拒绝，直到
显式升级变更（改两侧常量 + 重新真机验证）放行——与 bridge RPC 白名单「显式放行」同一哲学。
