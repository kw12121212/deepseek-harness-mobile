package app.deepseek.harness.mobile.tunnel

import app.deepseek.harness.mobile.transportRequest
import app.deepseek.harness.mobile.loopback.LoopbackProxy
import app.deepseek.harness.mobile.loopback.UpstreamConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 传输层接入的端到端 JVM 证明：transportRequest 的地址经 TunnelRoutes 改写到回环代理，
 * LoopbackProxy 按令牌前缀校验后转发到上游，Host 由代理按上游改写、业务头透传。
 * 隧道未安装映射时恒等（行为与无隧道一致）。
 */
class TransportTunnelRouteTest {
    private class FakeUpstream {
        private val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        @JvmField val requestSeen = CountDownLatch(1)
        @JvmField val captured = arrayOf("")

        fun start() {
            thread {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { return@thread }
                    thread {
                        try {
                            s.use { sock ->
                                val input = sock.getInputStream()
                                val head = ByteArrayOutputStream()
                                while (true) {
                                    val c = input.read()
                                    if (c < 0) break
                                    head.write(c)
                                    if (head.toString(Charsets.ISO_8859_1.name()).contains("\r\n\r\n")) break
                                }
                                captured[0] = head.toString(Charsets.ISO_8859_1.name())
                                requestSeen.countDown()
                                sock.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                                    flush()
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        fun close() = server.close()
    }

    @Test
    fun transportRequestRoutesThroughInstalledTunnel() {
        val up = FakeUpstream()
        up.start()
        val proxy = LoopbackProxy("http://127.0.0.1:${up.port}", {}, UpstreamConnector { Socket("127.0.0.1", up.port) })
        val port = proxy.start()
        TunnelRoutes.install("https://gamepc-1.example.ts.net:3091", "http://127.0.0.1:$port${proxy.basePath}")
        try {
            val conn = transportRequest(
                "https://gamepc-1.example.ts.net:3091/Ua6D/xyz",
                "POST",
                """{"a":1}""",
                mapOf("Authorization" to "Bearer dev-token"),
            )
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("ok", body)
            assertTrue(up.requestSeen.await(5, TimeUnit.SECONDS))
            val seen = up.captured[0]
            assertTrue("改写后路径去掉令牌前缀到达上游", seen.contains("POST /Ua6D/xyz"))
            assertTrue("Host 由代理按上游改写", seen.contains("Host: 127.0.0.1:${up.port}"))
            assertTrue("业务头透传", seen.contains("Authorization: Bearer dev-token", ignoreCase = true))
            assertTrue("注入 Connection: close 定界", seen.contains("connection: close", ignoreCase = true))
        } finally {
            TunnelRoutes.clear()
            proxy.stop()
            up.close()
        }
    }

    @Test
    fun rewriteIsIdentityWithoutMappingOrForOtherHosts() {
        // 未安装映射：恒等
        assertEquals("https://other.example/x", TunnelRoutes.rewrite("https://other.example/x"))
        TunnelRoutes.install("https://t.example", "http://127.0.0.1:1/tok")
        try {
            // 安装映射但前缀不命中：恒等
            assertEquals("https://other.example/x", TunnelRoutes.rewrite("https://other.example/x"))
            // 命中：改写到代理基地址
            assertEquals("http://127.0.0.1:1/tok/y?z=1", TunnelRoutes.rewrite("https://t.example/y?z=1"))
        } finally {
            TunnelRoutes.clear()
        }
        // 清除映射后：恒等
        assertEquals("https://t.example/y", TunnelRoutes.rewrite("https://t.example/y"))
    }
}
