package app.deepseek.harness.mobile.tunnel

/**
 * 内嵌 tailnet 隧道的生命周期（沿 private-content-assistant 已验证形态，蓝本
 * android/app/.../tunnel/TunnelManager.kt）：按需启动、进程内单例，ensureUp 幂等
 * （节点与回环代理健在时只刷新状态直接返回，不重建节点——同进程反复重建节点的
 * 构造性取证是遗留边界，默认不重建）。
 *
 * 全部方法阻塞且线程安全（@Synchronized）：调用方必须在后台线程驱动，不得占用主线程。
 * 与蓝本的差异：本项目无协程，StateFlow 换 @Volatile 状态 + onChange 回调。
 */
internal class TunnelManager(
    val config: TunnelConfigStore,
    private val netProvider: () -> NetSnapshot,
    private val nodeFactory: GoNodeFactory,
    private val proxyFactory: LoopbackProxyFactory,
    private val nodeDirProvider: () -> String,
    private val log: (String) -> Unit = { android.util.Log.i("DshTunnel", it) },
    /** Connected/NeedsKey/Failed 终态变化时回调（已确保不在 manager 锁内执行）。 */
    private val onStateChange: (TunnelState) -> Unit = {},
) {
    @Volatile
    var state: TunnelState = initialState()
        private set

    /** 当前节点主机名（tailnet 管理台里可见；持久化、跨进程稳定）。 */
    val nodeName: String get() = config.hostname()

    private var node: GoNode? = null
    private var proxy: ManagedProxy? = null

    /**
     * 幂等拉起隧道；返回终态。失败不抛异常（异常面收敛为 [TunnelState.Failed]），
     * 「无 key 且注册不起来 → 需要 key；带 key 失败 → 失败」如实分态。
     */
    @Synchronized
    fun ensureUp(): TunnelState {
        val existing = proxy
        if (node != null && existing != null && existing.port > 0) {
            refreshBackendState()
            return state
        }
        teardownLocked()

        val serverBase = config.serverBase().trim().trimEnd('/')
        if (serverBase.isEmpty()) {
            publish(TunnelState.Failed("未配置隧道服务器地址：请先在设置页填写（host 侧 serve 暴露的 https 地址）"))
            return state
        }

        val key = config.authKey()
        // 首启判定：无 key 且无已注册节点的持久状态时直接 needs-key——tsnet.Up 在该
        // 状态下会进入交互登录等待并永久阻塞（实证），调它只会把 App 卡在「连接中」。
        if (key.isEmpty() && !hasPersistedState()) {
            log("tunnel.needs-key（无 key 且无已注册节点状态）")
            publish(TunnelState.NeedsKey)
            return state
        }
        publish(TunnelState.Starting)
        log("tunnel.starting node=${config.hostname()} key=${if (key.isEmpty()) "空（已注册节点免 key 重连）" else "present"}")

        val n = try {
            nodeFactory.create(netProvider(), config.hostname(), nodeDirProvider(), key)
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message}"
            log("tunnel.create 失败: $msg")
            publish(TunnelState.Failed(msg))
            return state
        }
        node = n
        try {
            n.start()
            n.up()
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message}（errmsg=${n.errmsg().orEmpty()}）"
            log("tunnel.up 失败: $msg")
            closeNodeQuietly(n)
            publish(if (key.isEmpty()) TunnelState.NeedsKey else TunnelState.Failed(msg))
            return state
        }

        val endpoint = try {
            n.loopbackSocks()
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message}（errmsg=${n.errmsg().orEmpty()}）"
            log("tunnel.loopback 失败: $msg")
            closeNodeQuietly(n)
            publish(TunnelState.Failed(msg))
            return state
        }

        val p = proxyFactory.create(serverBase, endpoint)
        val port = try {
            p.start()
        } catch (t: Throwable) {
            log("proxy.start 失败: ${t.javaClass.simpleName}: ${t.message}")
            p.stop()
            closeNodeQuietly(n)
            publish(TunnelState.Failed("${t.javaClass.simpleName}: ${t.message}"))
            return state
        }
        proxy = p
        refreshBackendState()
        // 节点状态（tailscaled.state）已持久化，之后强杀重开免 key 重连：tagged auth
        // key 不再需要，继续明文保存只增加泄露面（蓝本用户确认方案沿用）。
        if (key.isNotEmpty()) config.clearAuthKey()
        publish(TunnelState.Connected(config.hostname(), "http://127.0.0.1:$port${p.basePath}"))
        log("tunnel.connected node=${config.hostname()} proxy=127.0.0.1:$port")
        return state
    }

    /** 用户粘贴新 key 后重试：保存并立即用它连接。空 key 静默忽略；已连接时重建生效。 */
    @Synchronized
    fun connectWithKey(key: String): TunnelState {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            return state
        }
        config.setAuthKey(trimmed)
        return if (node != null && proxy != null) restart() else ensureUp()
    }

    /** 服务器地址变更后重连：换地址必须重建反代（上游基址在代理构造时固定）。 */
    @Synchronized
    fun restart(): TunnelState {
        teardownLocked()
        return ensureUp()
    }

    /** 系统默认网络变化（Wi-Fi/移动网络切换）时由平台回调调用。 */
    @Synchronized
    fun onNetworkChanged() {
        if (node == null) return
        try {
            nodeFactory.refreshNet(netProvider())
        } catch (t: Throwable) {
            log("platformnet.refresh 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 进程收尾用；App 按需启动形态下通常不调用（强杀后由持久状态自愈）。 */
    @Synchronized
    fun close() {
        teardownLocked()
        publish(initialState())
    }

    private fun initialState(): TunnelState =
        if (config.authKey().isNotEmpty() || hasPersistedState()) TunnelState.Starting else TunnelState.NeedsKey

    private fun hasPersistedState(): Boolean = java.io.File(nodeDirProvider(), "tailscaled.state").isFile

    private fun publish(next: TunnelState) {
        state = next
        onStateChange(next)
    }

    @Synchronized
    private fun teardownLocked() {
        proxy?.let {
            try {
                it.stop()
            } catch (_: Throwable) {
            }
        }
        proxy = null
        node?.let(::closeNodeQuietly)
        node = null
    }

    private fun closeNodeQuietly(n: GoNode) {
        try {
            n.close()
        } catch (_: Throwable) {
        }
    }

    /** BackendState 变化打到日志（排障面；正则而非 org.json：JVM 单测里是 stub）。 */
    private fun refreshBackendState() {
        val st = node?.statusJson() ?: return
        val backend = BACKEND_STATE_RE.find(st)?.groupValues?.get(1) ?: return
        if (backend.isNotEmpty()) lastBackend = backend
    }

    @Volatile
    var lastBackend: String = ""
        private set

    private companion object {
        val BACKEND_STATE_RE = Regex(""""BackendState"\s*:\s*"([^"]+)"""")
    }
}
