package app.deepseek.harness.mobile.tunnel

import android.content.Context
import android.net.ConnectivityManager
import app.deepseek.harness.mobile.loopback.LoopbackProxy
import app.deepseek.harness.mobile.loopback.Socks5TlsConnector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

/** Go 侧节点（gomobile 绑定 tstunnel.Node）的最小面，测试注入假桩。 */
internal interface GoNode {
    fun start()

    /** 阻塞直到登录完成；必须在后台线程调用。 */
    fun up()

    fun loopbackSocks(): SocksEndpoint

    /** ipnstate.Status 的 JSON；挂起期间仍可读，null=读取失败。 */
    fun statusJson(): String?

    fun errmsg(): String?

    fun close()
}

/** 节点工厂：实现负责在创建节点前注入网络事实（Android 侧的硬前置）。 */
internal interface GoNodeFactory {
    fun create(net: NetSnapshot, hostname: String, dir: String, authKey: String): GoNode

    /** 网络变化后重新注入网卡快照（节点已在运行；netmon 读取注入的快照）。 */
    fun refreshNet(net: NetSnapshot)
}

/** 回环反代的最小面，测试注入假桩。 */
internal interface ManagedProxy {
    val port: Int

    /** 回环访问令牌路径前缀（"/<令牌>"），App 基地址 = http://127.0.0.1:<port> + basePath。 */
    val basePath: String

    fun start(): Int

    fun stop()
}

internal fun interface LoopbackProxyFactory {
    fun create(serverBase: String, endpoint: SocksEndpoint): ManagedProxy
}

/**
 * 生产装配：gomobile AAR 的 tstunnel + loopback 模块的 LoopbackProxy。
 *
 * 两个 Android 平台阻断项必须在任何 tsnet 启动之前完成（真机实证）：
 * 1. SELinux 禁止 app 读 netlink → 经 setInterfacesJSON 注入 Java NetworkInterface
 *    枚举（AltAddrs 非 nil 由 Go 侧保证），默认路由接口名经 setDefaultRouteIfname
 *    同批注入；
 * 2. logpolicy 无处存日志状态直接 panic → Go 侧 NewNode 把 XDG_CACHE_HOME 指到
 *    App 私有缓存目录（构造即完成）。
 */
internal class TstunnelGoFactory(context: Context) : GoNodeFactory {
    private val appContext = context.applicationContext

    override fun create(net: NetSnapshot, hostname: String, dir: String, authKey: String): GoNode {
        refreshNet(net)
        return TstunnelGoNode(tstunnel.Tstunnel.newNode(hostname, dir, authKey))
    }

    override fun refreshNet(net: NetSnapshot) {
        tstunnel.Tstunnel.setInterfacesJSON(net.interfacesJson)
        net.defaultRouteIfname?.takeIf { it.isNotEmpty() }?.let {
            tstunnel.Tstunnel.setDefaultRouteIfname(it)
        }
    }

    private class TstunnelGoNode(private val n: tstunnel.Node) : GoNode {
        override fun start() = n.start()
        override fun up() = n.up()
        override fun loopbackSocks(): SocksEndpoint = SocksEndpoint(n.loopback(), n.proxyCredential())
        override fun statusJson(): String? = try { n.statusJSON() } catch (_: Throwable) { null }
        override fun errmsg(): String? = try { n.errmsg() } catch (_: Throwable) { null }
        override fun close() = n.close()
    }
}

internal class LoopbackProxyFactoryImpl : LoopbackProxyFactory {
    override fun create(serverBase: String, endpoint: SocksEndpoint): ManagedProxy =
        object : ManagedProxy {
            private val proxy = LoopbackProxy(serverBase, { android.util.Log.i("DshTunnel", it) }, Socks5TlsConnector(endpoint.addr, endpoint.credential))
            override val port: Int get() = proxy.port
            override val basePath: String get() = proxy.basePath
            override fun start(): Int = proxy.start()
            override fun stop() = proxy.stop()
        }
}

/** App 侧网络事实快照：NetworkInterface 枚举 + 默认路由接口名（有权限时）。 */
internal fun platformNetSnapshot(context: Context): NetSnapshot {
    val array = JSONArray()
    val seen = HashSet<String>()
    // 网卡枚举异常按蓝本语义传播（TunnelManager 收敛为 Failed）；
    // 默认路由查询可能因权限抛出，单独吞掉（DERP 中继兜底）。
    run {
        for (ni in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!seen.add(ni.name)) continue
            val entry = JSONObject()
                .put("name", ni.name)
                .put("index", ni.index)
                .put("mtu", ni.mtu)
                .put("hw", ni.hardwareAddress?.joinToString(":") { "%02x".format(it) } ?: "")
                .put("up", ni.isUp)
                .put("loopback", ni.isLoopback)
                .put("p2p", ni.isPointToPoint)
                .put("multicast", ni.supportsMulticast())
            val addrs = JSONArray()
            for (addr in ni.interfaceAddresses) {
                val text = addr.address.hostAddress ?: continue
                addrs.put(if (addr.networkPrefixLength >= 0) "$text/${addr.networkPrefixLength}" else "$text/32")
            }
            entry.put("addrs", addrs)
            array.put(entry)
        }
    }
    val route = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.interfaceName
    }.getOrNull()
    return NetSnapshot(array.toString(), route)
}

internal fun tunnelNodeDir(context: Context): String =
    File(context.filesDir, "tstunnel").apply { mkdirs() }.absolutePath
