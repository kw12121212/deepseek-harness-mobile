package app.deepseek.harness.mobile.tunnel

/** 设备侧可观测的隧道状态（四态；UI 按类型呈现）。 */
internal sealed class TunnelState {
    internal data object Starting : TunnelState()
    internal data class Connected(val nodeName: String, val proxyBase: String) : TunnelState()
    internal data object NeedsKey : TunnelState()
    internal data class Failed(val message: String) : TunnelState()
}

/** tsnet 回环 SOCKS5 代理的端点与口令（LoopbackProxy 的上游凭据）。 */
internal data class SocksEndpoint(val addr: String, val credential: String)

/** App 侧观测到的网络事实快照：注入给 tsnet（SELinux 禁止 app 读 netlink）。 */
internal data class NetSnapshot(val interfacesJson: String, val defaultRouteIfname: String?)

/** 节点主机名生成：dsh- + 4 位十六进制（每台安装持久化一次，见 TunnelConfigStore）。 */
internal object TunnelNames {
    internal fun defaultHostname(random: () -> Int = { kotlin.random.Random.nextInt() }): String =
        // 掩码而不是 abs：abs(Int.MIN_VALUE) 仍为负，会产生 dsh--… 形态。
        "dsh-" + (random() and 0x7fffffff).toString(16).take(4).padStart(4, '0')
}
