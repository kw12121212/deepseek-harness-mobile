package app.deepseek.harness.mobile.tunnel

import android.content.Context

/**
 * 隧道的进程内装配 + 对传输层的单一改写面。
 *
 * TunnelRoutes：隧道 Connected 时登记（真实 serveBase → 回环 proxyBase）映射；
 * transportRequest 与两条传输路径的 WS URL 在请求时刻经 [rewrite] 改写，映射翻转即刻
 * 生效、不重建任何 API/传输对象；未连接或前缀不匹配时恒等返回（行为与无隧道一致）。
 * Origin/Host 头不在改写范围：直连路径的 dsh 信任围栏按真实 origin 校验。
 */
internal object TunnelRoutes {
    @Volatile
    private var mapping: Pair<String, String>? = null

    internal fun install(realBase: String, proxyBase: String) {
        mapping = realBase.trimEnd('/') to proxyBase.trimEnd('/')
    }

    internal fun clear() {
        mapping = null
    }

    /** 把指向隧道服务器地址的 URL 改写到回环代理；其余原样返回。
     *  命中要求前缀后紧跟路径/查询边界（/ ?）或串尾，避免 https://host:3091 误吞 https://host:30910。 */
    internal fun rewrite(url: String): String {
        val (real, proxy) = mapping ?: return url
        if (!url.startsWith(real)) return url
        val next = url.getOrNull(real.length)
        if (next != null && next != '/' && next != '?') return url
        return proxy + url.removePrefix(real)
    }
}

/** 隧道管理器单例装配（主进程内唯一；全部方法阻塞，调用方需在后台线程）。 */
internal object TunnelIntegration {
    @Volatile
    private var managerField: TunnelManager? = null

    @Volatile
    private var appContext: Context? = null

    internal fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    internal fun manager(): TunnelManager {
        appContext?.let { ctx ->
            if (managerField == null) {
                synchronized(this) {
                    if (managerField == null) {
                        managerField = TunnelManager(
                            config = PrefsTunnelConfigStore(ctx),
                            netProvider = { platformNetSnapshot(ctx) },
                            nodeFactory = TstunnelGoFactory(ctx),
                            proxyFactory = LoopbackProxyFactoryImpl(),
                            nodeDirProvider = { tunnelNodeDir(ctx) },
                            onStateChange = { state -> onTunnelState(state) },
                        )
                    }
                }
            }
        }
        return requireNotNull(managerField) { "TunnelIntegration 未初始化（先在 Application/Activity 启动时 init）" }
    }

    /** 当前隧道状态（未装配时视为未启用）。 */
    internal fun state(): TunnelState? = managerField?.state

    /** 隧道服务器地址是否就是当前连接目标（用于启动 bootstrap 判定）。 */
    internal fun targets(origin: String): Boolean {
        val base = runCatching { manager().config.serverBase() }.getOrDefault("")
        return base.isNotEmpty() && app.deepseek.harness.mobile.ServerConfig.normalize(origin) == app.deepseek.harness.mobile.ServerConfig.normalize(base)
    }

    /** 当前隧道连接所服务的 serverBase（Connected 时记录；换地址重导入时据此判定重建）。 */
    @Volatile
    private var installedFor: String? = null

    /** 后台线程驱动：目标为隧道地址时确保隧道在役。幂等短路只对同一 serverBase 成立——
     *  资料重导入换了地址时必须 restart（换地址必须重建反代，TunnelManager 文档语义），
     *  否则旧映射在役、新 key 滞留，pair 会直连出站。 */
    internal fun ensureUpFor(origin: String): Boolean {
        if (!targets(origin)) return false
        val m = manager()
        val normalized = app.deepseek.harness.mobile.ServerConfig.normalize(origin)
        if (m.state is TunnelState.Connected && installedFor != null && installedFor != normalized) {
            m.restart()
        } else {
            m.ensureUp()
        }
        return m.state is TunnelState.Connected
    }

    /** 状态变化时维护 TunnelRoutes 映射。 */
    private fun onTunnelState(state: TunnelState) {
        when (state) {
            is TunnelState.Connected -> {
                val real = managerField?.config?.serverBase().orEmpty()
                if (real.isNotEmpty()) {
                    TunnelRoutes.install(real, state.proxyBase)
                    installedFor = app.deepseek.harness.mobile.ServerConfig.normalize(real)
                }
            }
            else -> {
                TunnelRoutes.clear()
                installedFor = null
            }
        }
    }
}
