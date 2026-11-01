# Q10 session/delete 合同核验（待确认）

2026-11-01；结论仅覆盖以下静态源码和安装包模块，不代表已遍历运行时插件注册表。

- 固定 `dsh-v0.2.0-rc.2` 的 [SessionController](https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/dsh-v0.2.0-rc.2/packages/api/session-controller/src/index.ts) 未声明 `@Remote('delete')`；[命令实现](https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/dsh-v0.2.0-rc.2/packages/api/session-controller/src/commands.ts) 未提供对应删除命令；[types](https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/dsh-v0.2.0-rc.2/packages/api/session-controller/src/types.ts) 未声明 SessionDelete 请求类型。
- 本机安装包 `/Applications/DeepSeek Harness.app/Contents/Resources/app.asar` 的桌面与 session-controller 包版本均为 `0.2.1-rc.5`；模块 `dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js` 的 SessionController 使用 `namespace: "session"`，显式 Remote 装饰成员表也无 `delete`。仅出现的 Map/Set `.delete()` 不是会话删除 RPC。
- 参数、历史/工作目录副作用及是否可恢复均未确认；不据此猜测 `{request:{sessionId}}`，不开放白名单或加入不可恢复的删除按钮，未对真实会话执行删除探测。

可检查证据：

- [方法清单、固定源码哈希与安装包模块哈希](method-inventories.json)。
- [REA 原始 summary Evidence](rea-controller-summary.json)，Evidence ID `ev_e48a1322e3f0d762da15c2c7570c74862150eefa7997097c12ac9edde5864629`，parent `ev_3f0045e299f0f0e9725b461c9258f3ef74b69f335b1fe5fc16061e272400cef0`；ASAR SHA-256 `8dad52f295bdbe9fa71948fdf38ac2ce3c910213d3b89c37174b2af77db9fb31`。本次只选中 session-controller package.json 和 lib/index.js；其他模块与运行时行为仍未知。

`./gradlew :app:testDebugUnitTest --rerun` 已实跑，25 份 XML 共 131 项通过，0 失败/错误/跳过；这是既有基线验证，本轮尚未实现 Q10、未增加删除功能测试。日志 `/tmp/q10-contract-baseline-tests.log`。

下一步需要总监提供实际 `session/delete` 方法表条目或实现路径，确认合同后继续原 Q10 范围。
