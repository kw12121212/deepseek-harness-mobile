package app.deepseek.harness.mobile.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 假节点：按脚本起舞，记录生命周期。 */
private class FakeGoNode(private val script: Script) : GoNode {
    enum class Script { OK, UP_FAILS, LOOPBACK_FAILS }
    var started = false
    var closed = false
    override fun start() { started = true }
    override fun up() { if (script == Script.UP_FAILS) throw java.io.IOException("up timeout") }
    override fun loopbackSocks(): SocksEndpoint {
        if (script == Script.LOOPBACK_FAILS) throw java.io.IOException("no loopback")
        return SocksEndpoint("127.0.0.1:1080", "cred")
    }
    override fun statusJson(): String = """{"BackendState":"Running","Self":{"TailscaleIPs":["100.64.0.1"]}}"""
    override fun errmsg(): String = "boom"
    override fun close() { closed = true }
}

private class FakeFactory(private val script: FakeGoNode.Script) : GoNodeFactory {
    var created: FakeGoNode? = null
    override fun create(net: NetSnapshot, hostname: String, dir: String, authKey: String): GoNode =
        FakeGoNode(script).also { created = it }
    override fun refreshNet(net: NetSnapshot) {}
}

private class FakeProxy(val fail: Boolean = false) : ManagedProxy {
    var started = false
    var stopped = false
    override val port: Int get() = if (started) 41000 else 0
    override val basePath: String = "/tok"
    override fun start(): Int { if (fail) throw java.io.IOException("bind"); started = true; return 41000 }
    override fun stop() { stopped = true }
}

private class MapConfig(
    private var server: String = "https://node.example.ts.net:3091",
    private var key: String = "",
    private val dir: File,
) : TunnelConfigStore {
    override fun serverBase(): String = server
    override fun setServerBase(value: String) { server = value }
    override fun authKey(): String = key
    override fun setAuthKey(value: String) { key = value }
    override fun clearAuthKey() { key = "" }
    override fun hostname(): String = "dsh-test"
    override fun setHostname(value: String) {}
    fun nodeDir(): File = dir
}

class TunnelManagerTest {
    private fun tempDir(): File = java.io.File(kotlin.io.path.createTempDirectory("tunnel-test").toString())

    private fun manager(
        config: MapConfig,
        script: FakeGoNode.Script = FakeGoNode.Script.OK,
        proxyFail: Boolean = false,
        onChange: (TunnelState) -> Unit = {},
    ): Triple<TunnelManager, FakeFactory, FakeProxy> {
        val factory = FakeFactory(script)
        val proxy = FakeProxy(proxyFail)
        val m = TunnelManager(config, { NetSnapshot("[]", null) }, factory, { _, _ -> proxy }, { config.nodeDir().absolutePath }, {}, onChange)
        return Triple(m, factory, proxy)
    }

    @Test
    fun firstStartWithoutKeyOrStateGoesNeedsKey() {
        val dir = tempDir()
        val (m, factory, _) = manager(MapConfig(dir = dir))
        assertEquals(TunnelState.NeedsKey, m.ensureUp())
        assertEquals("needs-key 下不得创建节点", null, factory.created)
    }

    @Test
    fun connectWithKeyJoinsClearsKeyAndConnectsIdempotently() {
        val dir = tempDir()
        val config = MapConfig(dir = dir)
        val (m, _, proxy) = manager(config)
        val connected = m.connectWithKey("tskey-abc")
        assertTrue(connected is TunnelState.Connected)
        assertEquals("入网成功后必须清 key", "", config.authKey())
        assertEquals(true, proxy.started)
        val again = m.ensureUp()
        assertTrue("幂等：再次 ensureUp 直接返回已连接", again is TunnelState.Connected)
    }

    @Test
    fun upFailureWithoutKeyIsNeedsKeyWithKeyIsFailed() {
        val dir = tempDir()
        val (m1, f1, _) = manager(MapConfig(dir = dir), script = FakeGoNode.Script.UP_FAILS)
        assertEquals(TunnelState.NeedsKey, m1.ensureUp())
        assertEquals("needs-key 分支不创建节点", null, f1.created)
        val (m2, f2, _) = manager(MapConfig(dir = dir), script = FakeGoNode.Script.UP_FAILS)
        assertEquals(true, m2.connectWithKey("tskey-x") is TunnelState.Failed)
        assertEquals(true, f2.created?.closed)
    }

    @Test
    fun loopbackFailureClosesNodeAndFails() {
        val dir = tempDir()
        val (m, f, _) = manager(MapConfig(dir = dir), script = FakeGoNode.Script.LOOPBACK_FAILS)
        val state = m.connectWithKey("tskey-x")
        assertTrue(state is TunnelState.Failed)
        assertEquals(true, f.created?.closed)
    }

    @Test
    fun proxyStartFailureFailsAndStopsProxy() {
        val dir = tempDir()
        val (m, _, proxy) = manager(MapConfig(dir = dir), proxyFail = true)
        assertEquals(true, m.connectWithKey("tskey-x") is TunnelState.Failed)
        assertEquals(true, proxy.stopped)
    }

    @Test
    fun persistedStateAllowsKeylessReconnect() {
        val dir = tempDir()
        // 预置持久状态文件：无 key 也应尝试连接（免 key 重连路径）
        File(dir, "tailscaled.state").writeText("{}")
        val (m, _, _) = manager(MapConfig(dir = dir))
        val state = m.ensureUp()
        assertTrue("有持久状态时无 key 也可连接", state is TunnelState.Connected)
    }

    @Test
    fun restartTearsDownAndRebuilds() {
        val dir = tempDir()
        val dir2 = tempDir()
        // 模拟已注册节点的持久状态：入网成功清 key 后的重连依赖它（真实 tsnet 落盘）。
        File(dir2, "tailscaled.state").writeText("{}")
        val config2 = MapConfig(dir = dir2)
        val (m2, _, proxy2) = manager(config2)
        m2.connectWithKey("tskey-x")
        val state = m2.restart()
        assertTrue("重启后凭持久状态免 key 重连：$state", state is TunnelState.Connected)
        assertEquals(true, proxy2.stopped)
    }

    @Test
    fun stateChangeCallbackFiresOnTerminalStates() {
        val dir = tempDir()
        val seen = mutableListOf<TunnelState>()
        val (m, _, _) = manager(MapConfig(dir = dir), onChange = { seen.add(it) })
        m.connectWithKey("tskey-x")
        assertTrue(seen.contains(TunnelState.Starting))
        assertTrue(seen.any { it is TunnelState.Connected })
    }

    @Test
    fun emptyServerBaseFailsExplicitly() {
        val dir = tempDir()
        val (m, _, _) = manager(MapConfig(server = "", dir = dir))
        val state = m.connectWithKey("tskey-x")
        assertTrue(state is TunnelState.Failed && state.message.contains("服务器地址"))
    }

    private fun createTempDirectory(prefix: String): File =
        java.io.File(kotlin.io.path.createTempDirectory(prefix).toString())
}
