package app.deepseek.harness.mobile

import app.deepseek.harness.mobile.tunnel.TunnelConfigStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private class FakeTunnelStore : TunnelConfigStore {
    var server = ""
    var key = ""
    var host = ""
    override fun serverBase(): String = server
    override fun setServerBase(value: String) { server = value }
    override fun authKey(): String = key
    override fun setAuthKey(value: String) { key = value }
    override fun clearAuthKey() { key = "" }
    override fun hostname(): String = host.ifEmpty { "dsh-auto" }
    override fun setHostname(value: String) { host = value }
}

class TailscalePairingParamsTest {
    private fun configJson(tailscale: String? = null, origin: String = "https://node.example.ts.net:3091"): String {
        val json = JSONObject().put("protocolVersion", 1).put("origin", origin).put("pairPath", "/rrFl-FcFltk/VWIIM8N0FnY")
        tailscale?.let { json.put("tailscale", JSONObject(it)) }
        return json.toString()
    }

    @Test
    fun parsesTailscaleParamsFromProfile() {
        val config = BridgePairingClient.parseConfig(configJson("""{"origin":"https://node.example.ts.net:3091","authKey":"tskey-auth-kxDfaXoPwW11CNTRL-787CxwGSSFLX9RdEizTKFL6UWHEkDcsG","hostname":"dsh-phone-1"}"""))
        val ts = config.tailscale!!
        assertEquals("https://node.example.ts.net:3091", ts.origin)
        assertEquals("tskey-auth-kxDfaXoPwW11CNTRL-787CxwGSSFLX9RdEizTKFL6UWHEkDcsG", ts.authKey)
        assertEquals("dsh-phone-1", ts.hostname)
    }

    @Test
    fun profileWithoutTailscaleFieldKeepsLegacyBehavior() {
        val config = BridgePairingClient.parseConfig(configJson())
        assertNull(config.tailscale)
        assertEquals("https://node.example.ts.net:3091", config.origin)
    }

    @Test
    fun hostnameIsOptional() {
        val config = BridgePairingClient.parseConfig(configJson("""{"origin":"https://node.example.ts.net:3091","authKey":"tskey-abc"}"""))
        assertNull(config.tailscale!!.hostname)
    }

    @Test
    fun rejectsNonHttpsTailscaleOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgePairingClient.parseConfig(configJson("""{"origin":"http://node.example.ts.net:3091","authKey":"tskey-abc"}"""))
        }
    }

    @Test
    fun rejectsEmptyOrOversizedAuthKey() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgePairingClient.parseConfig(configJson("""{"origin":"https://node.example.ts.net:3091","authKey":"  "}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgePairingClient.parseConfig(configJson("""{"origin":"https://node.example.ts.net:3091","authKey":"${"k".repeat(257)}"}"""))
        }
    }

    @Test
    fun rejectsInvalidHostname() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgePairingClient.parseConfig(configJson("""{"origin":"https://node.example.ts.net:3091","authKey":"tskey-abc","hostname":"Bad_Host!"}"""))
        }
    }

    @Test
    fun applyToWritesTunnelConfigStore() {
        val store = FakeTunnelStore()
        val config = BridgePairingClient.parseConfig(configJson("""{"origin":"https://node.example.ts.net:3091","authKey":"tskey-abc","hostname":"dsh-phone-1"}"""))
        config.tailscale!!.applyTo(store)
        assertEquals("https://node.example.ts.net:3091", store.server)
        assertEquals("tskey-abc", store.key)
        assertEquals("dsh-phone-1", store.host)
    }

    @Test
    fun toStringNeverLeaksAuthKey() {
        val config = BridgePairingClient.parseConfig(configJson("""{"origin":"https://node.example.ts.net:3091","authKey":"tskey-secret-value"}"""))
        assertEquals("https://node.example.ts.net:3091", config.toString())
        // 参数对象自身的字符串化同样不得携带 key（防日志/错误面意外泄漏）
        val paramsText = config.tailscale.toString()
        assertEquals(false, paramsText.contains("tskey-secret-value"))
        assertEquals(true, paramsText.contains("node.example.ts.net"))
    }

    @Test
    fun rejectsExplicitNullAuthKey() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgePairingClient.parseConfig("""{"protocolVersion":1,"origin":"https://node.example.ts.net:3091","pairPath":"/rrFl-FcFltk/VWIIM8N0FnY","tailscale":{"origin":"https://node.example.ts.net:3091","authKey":null}}""")
        }
    }
}
