# ts-tunnel-integration 验证记录（tailscale-private-transport）

验证目标（planned change `ts-tunnel-integration`）：App 内嵌隧道成为正式承载——设置页配置后隧道
Connected，隧道地址的全部请求经回环代理出站，配对/会话数据经隧道全链路工作。

环境（2026-11-03，模拟器按用户指示；真机补验遗留）：

| 组件 | 取值 |
| --- | --- |
| host | Windows，serve 暴露 https://gamepc-1.tail89569c.ts.net:3091 → dshbridge（上游为 0.2.0-rc.2 合规假上游） |
| 设备 | 模拟器 api31 x86_64，debug 变体（AAR 已 implementation 链入主构建） |
| 隧道 | 进程内 tsnet 节点 dsh-emu-app（用户提供 tagged auth key，实挂 tag:pca-client） |

## 1. 隧道在 App 内拉起并 Connected（真机路径：设置页 + 启动 bootstrap）

启动 bootstrap（onCreate 后台 worker 对 serverUrl=隧道地址 ensureUp）日志（logcat 原文
[tunnel-logcat.txt](tunnel-logcat.txt)）：

```
DshTunnel: tunnel.starting node=dsh-emu-app key=present
DshTunnel: proxy.listening 127.0.0.1:43415 -> https://gamepc-1.tail89569c.ts.net:3091
DshTunnel: tunnel.connected node=dsh-emu-app proxy=127.0.0.1:43415
```

设置页证据：[settings-tunnel-row.png](settings-tunnel-row.png)（设置 sheet 出现「隧道 (Tailscale)」行）、
[tunnel-connected.png](tunnel-connected.png) / [tunnel-dialog.png](tunnel-dialog.png)（隧道对话框显示
「Connected: node dsh-emu-app」与服务器地址）。开发途中发现并修复两处接线 bug（bootstrap 读取
serverUrl 早于 prefs 载入；targets() 在管理器构建前读空配置），均已以日志复验。

## 2. WS 经隧道全链路（实时证据）

App 的 mux 流（okhttp WebSocket）自动经改写后的回环地址出站，升级请求穿过
LoopbackProxy（Upgrade 透传分支）→ SOCKS5 → serve → dshbridge，桥按设计对非桥路由返回 404：

```
java.net.ProtocolException: Expected HTTP 101 response but was '404 Not Found'
  at okhttp3.internal.ws.RealWebSocket.checkUpgradeSuccess
```

该 404 正是 bridge 对 /api/remote.mux（直连模式 URL）的正确响应——**证明 WS 升级请求完整穿过
隧道三层并带回真实响应**；直连模式指向 bridge 地址本就走错路由，配对后走 stream 随机路由即正确路径。

## 3. HTTP 路径与协议纪律（JVM 单测，全量 273 项 0 失败）

- `TransportTunnelRouteTest.transportRequestRoutesThroughInstalledTunnel`：transportRequest 地址经
  TunnelRoutes 改写 → 真实 LoopbackProxy → 假上游：路径去令牌、Host 按上游改写、业务头透传、
  Connection: close 定界全断言；未安装映射/前缀不命中/清除映射三种恒等分支亦锁定。
- `LoopbackProxyTest`（6 项）：GET 转发与头纪律、令牌 403 不拨上游、chunked 透传、
  Upgrade 透传（升级头到上游 + 101 头到客户端 + 升级后下行帧字节原样流动 + 上行帧到达上游）。
  POST 转发由 `TransportTunnelRouteTest` 覆盖。
- `TunnelManagerTest`（9 项）：needs-key 分态、入网成功清 key、幂等早退、up/loopback/proxy 失败
  分态回滚、持久状态免 key 重连、restart、状态回调、空地址显式失败。

## 4. 配对经隧道（同栈证据 + 遗留）

配对 POST 经同一承载栈（SOCKS5 回环 → serve → bridge 完成配对与 session/list）已由
ts-embed-spike 的仪器测试在**同一 AAR、同一 SOCKS 路径、同一 serve 入口**上证明（见
[../ts-embed-spike/validation.md](../ts-embed-spike/validation.md)）。App 内对话框的配对操作本轮未能
完成自动化取证：模拟器 uiautomator 树在该对话框上不稳定（AccessibilityNodeInfoDumper 报错、截图
经代理无法核验），两轮带参输入后配对码过期（10 分钟时效），bridge 设备列表确认无新设备——
**不声称通过**，列为真机补验时的第一项手动验证（导入连接 JSON → 配对 → 会话列表）。截图
[app-main-through-tunnel.png](app-main-through-tunnel.png) 为隧道在役时的主界面。

## 5. 清理

模拟器隧道节点 dsh-emu-app 仍在 tailnet（tagged，可管理台移除）；host 侧 serve/bridge/假上游
在安全收尾变更后停止（见变更 tasks）。

## 6. 评审修复记录

独立评审（fresh-context）发现并已全部修复：**升级透传缺客户端→上游字节泵**（设计写明双向、
首版只实现单向下行——WS ping 与上行帧会滞留本地，已补独立线程上行泵与上行帧测试）；
TunnelRoutes 前缀碰撞（https://host:3091 误吞 :30910，已加路径/查询边界判定）；
直连路径两个裸 URL 出口（token exchange 与版本探测）未走改写点（已接入）；
TunnelManager.close() 不清映射（已改 publish）；packaging 注释与主 APK 实际含 .so 的事实不符
（已纠正）；网卡枚举异常吞掉（已恢复蓝本的传播语义）；pumpHead 死参数（已删）。

## 结论

隧道作为 App 正式承载的工程链路（TunnelManager 状态机、LoopbackProxy 含 WS 透传、
TunnelRoutes 单点改写、设置页、启动 bootstrap）全部落地并有单测与实时证据；App 内对话框级
配对留待真机补验。spike-first 门槛之后的集成面成立。
