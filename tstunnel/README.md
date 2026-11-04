# tstunnel — Android 内嵌 tsnet 薄包装

复制自 `../private-content-assistant/android/tstunnel`（源自 mobile-manage，2026-09 真机验证
形态），为 tailscale-private-transport 里程碑的 app 侧嵌入面：把 `tailscale.com/tsnet`（与官方
客户端同源的 Go 内核）以 gomobile 可绑定的最小面暴露给 Kotlin——进程内 userspace tailnet
客户端，无 TUN、无 VPNService、无 VPN 权限。

两个 Android 硬前置已内建（蓝本真机实证）：

1. SELinux 禁止 untrusted app 读 netlink → `SetInterfacesJSON` 注入 Java NetworkInterface
   枚举快照（Start 之前调用；`AltAddrs` 非 nil 由 Go 侧保证），`SetDefaultRouteIfname`
   注入默认路由接口名；
2. tsnet logpolicy 无处存日志状态直接 panic → `NewNode` 把 `XDG_CACHE_HOME` 指到 App
   私有缓存目录（构造即完成）。

## 构建 AAR

```sh
bash tstunnel/build-aar.sh [NDK 版本，缺省 28.2.13676358]
```

产出 `app/libs/tstunnel.aar`（**不入库**，`.gitignore` 覆盖）：gomobile/gobind 与
tailscale.com 版本钉在本模块 `go.mod` 的 tool 指令与 require（tailscale v1.94.1）。
升级版本 = 改钉 + 重新真机验证（mobile-manage 已知坑）。

## spike 仪器测试

`app/src/androidTest/.../TstunnelSpikeTest.kt`，参数驱动、缺参数自动跳过（不进常规回归）：

```sh
adb shell "am instrument -w \
  -e authkey tskey-... \
  -e origin https://<节点域名>:<serve端口> \
  -e pairPath /xxxx/yyyy \
  -e pairingCode zzzz \
  app.deepseek.harness.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner"
```

参数：`authkey`＝tagged auth key（Tailscale 管理台生成）；`origin`＝host 侧 serve 暴露地址；
`pairPath`/`pairingCode`＝`dsh-bridge pair` 产物；可选 `hostname`。Windows host 注意：整条命令
加引号并配 `MSYS_NO_PATHCONV=1`，否则 Git Bash 会把以 `/` 开头的 pairPath 改写成 Windows 路径。

真机（非模拟器）运行；证据落 `docs/qa/ts-embed-spike/`。平台事实（SOCKS5 认证、
ts.net 域名经 MagicDNS）见测试类注释与蓝本 `android/loopback/Socks5.kt` 取证。
