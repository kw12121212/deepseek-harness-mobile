package app.deepseek.harness.mobile.loopback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 假上游：接受一条连接，把收到的请求字节交给断言，按脚本回写响应。 */
private class FakeUpstream(
    private val onRequest: (String) -> Unit,
    private val responseScript: (OutputStream) -> Unit,
) {
    private val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @JvmField val accepted = CountDownLatch(1)

    fun start() {
        thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { return@thread }
                thread {
                    try {
                        s.use { sock ->
                            accepted.countDown()
                            val input = sock.getInputStream()
                            val head = ByteArrayOutputStream()
                            while (true) {
                                val c = input.read()
                                if (c < 0) break
                                head.write(c)
                                if (head.toString(Charsets.ISO_8859_1.name()).contains("\r\n\r\n")) break
                            }
                            val headText = head.toString(Charsets.ISO_8859_1.name())
                            val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val body = ByteArray(len)
                            var off = 0
                            while (off < len) {
                                val n = input.read(body, off, len - off)
                                if (n < 0) break
                                off += n
                            }
                            onRequest(headText + String(body, Charsets.UTF_8))
                            responseScript(sock.getOutputStream())
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun close() = server.close()
}

/** 直连上游连接器（协议级测试不引入 SOCKS/TLS，承载由生产装配负责）。 */
private class DirectConnector(private val upstreamPort: Int) : UpstreamConnector {
    override fun connect(url: URL): Socket = Socket("127.0.0.1", upstreamPort)
}

class LoopbackProxyTest {
    private fun startProxy(upstreamPort: Int): Pair<LoopbackProxy, Int> {
        val proxy = LoopbackProxy("http://127.0.0.1:$upstreamPort", {}, DirectConnector(upstreamPort))
        val port = proxy.start()
        return proxy to port
    }

    private fun httpGet(port: Int, path: String, headers: String = ""): Pair<String, String> {
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: x\r\n$headers\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val all = s.getInputStream().readAllBytes()
            val text = String(all, Charsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n")
            val body = text.substringAfter("\r\n\r\n", "")
            return head to body
        }
    }

    @org.junit.Test
    fun forwardsRequestsWithoutTokenPathAndStripsHopByHop() {
        val request = CountDownLatch(1)
        val seen: Array<String> = arrayOf("")
        val up = FakeUpstream({ seen[0] = it; request.countDown() }, { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".toByteArray())
            out.flush()
        })
        up.start()
        val (proxy, port) = startProxy(up.port)
        try {
            val (head, body) = httpGet(port, "${proxy.basePath}/api/session.list", "Keep-Alive: timeout=5\r\nX-App: 1\r\n")
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertEquals("hi", body)
            request.await(5, TimeUnit.SECONDS)
            assertTrue("Host 由代理按上游改写", seen[0].contains("Host: 127.0.0.1:${up.port}"))
            assertTrue("hop-by-hop 头必须剥除", !seen[0].contains("keep-alive", ignoreCase = true))
            assertTrue("业务头透传", seen[0].contains("x-app: 1", ignoreCase = true))
            assertTrue("非令牌路径原样转发", seen[0].contains("GET /api/session.list"))
            assertTrue("注入 Connection: close 定界", seen[0].contains("connection: close", ignoreCase = true))
        } finally {
            proxy.stop(); up.close()
        }
    }

    @org.junit.Test
    fun rejectsWrongTokenWithoutDialingUpstream() {
        val up = FakeUpstream({ }, { })
        up.start()
        val (proxy, port) = startProxy(up.port)
        try {
            val (head, _) = httpGet(port, "/wrong-token/api")
            assertTrue(head.startsWith("HTTP/1.1 403"))
            assertEquals("令牌不符时不得拨上游", 1, up.accepted.count)
        } finally {
            proxy.stop(); up.close()
        }
    }

    @org.junit.Test
    fun passesThroughChunkedResponseTransferEncoding() {
        val up = FakeUpstream({ }, { out ->
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n0\r\n\r\n".toByteArray())
            out.flush()
        })
        up.start()
        val (proxy, port) = startProxy(up.port)
        try {
            val (head, body) = httpGet(port, "${proxy.basePath}/big")
            assertTrue("transfer-encoding 必须透传", head.contains("transfer-encoding: chunked", ignoreCase = true))
            assertEquals("4\r\nabcd\r\n0\r\n\r\n", body)
        } finally {
            proxy.stop(); up.close()
        }
    }

    /** 升级请求头原样透传到上游（本项目新增分支）。 */
    @org.junit.Test
    fun passesThroughWebSocketUpgradeHeaders() {
        val request = CountDownLatch(1)
        val seen: Array<String> = arrayOf("")
        val up = FakeUpstream({ seen[0] = it; request.countDown() }, { })
        up.start()
        val (proxy, port) = startProxy(up.port)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().apply {
                    write(("GET ${proxy.basePath}/api/remote.mux HTTP/1.1\r\nHost: x\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                request.await(5, TimeUnit.SECONDS)
            }
            assertTrue("升级请求必须携带 Sec-WebSocket 头到上游", seen[0].contains("sec-websocket-key", ignoreCase = true))
            assertTrue("升级请求的 Upgrade 头必须原样转发", seen[0].contains("upgrade: websocket", ignoreCase = true))
            assertTrue("升级请求不注入 Connection: close", !seen[0].contains("connection: close", ignoreCase = true))
        } finally {
            proxy.stop(); up.close()
        }
    }

    /** 升级后的上行帧（客户端→服务端）必须经代理到达上游（双向泵）。 */
    @org.junit.Test
    fun upgradeClientFramesReachUpstream() {
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val upstreamGotFrame: Array<ByteArray?> = arrayOfNulls(1)
        val frameSeen = CountDownLatch(1)
        thread {
            try {
                server.accept().use { up ->
                    val input = up.getInputStream()
                    val head = StringBuilder()
                    while (true) {
                        val c = input.read()
                        if (c < 0) break
                        head.append(c.toChar())
                        if (head.toString().endsWith("\r\n\r\n")) break
                    }
                    up.getOutputStream().apply {
                        write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                    val frame = ByteArray(4)
                    var off = 0
                    while (off < 4) {
                        val n = input.read(frame, off, 4 - off)
                        if (n < 0) break
                        off += n
                    }
                    if (off == 4) {
                        upstreamGotFrame[0] = frame.copyOf()
                        frameSeen.countDown()
                    }
                    while (input.read() >= 0) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        val proxy = LoopbackProxy("http://127.0.0.1:${server.localPort}", {}, DirectConnector(server.localPort))
        val port = proxy.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().apply {
                    write(("GET ${proxy.basePath}/api/remote.mux HTTP/1.1\r\nHost: x\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: k=\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val input = s.getInputStream()
                val head = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) break
                    head.append(c.toChar())
                    if (head.toString().endsWith("\r\n\r\n")) break
                }
                assertTrue(head.toString().startsWith("HTTP/1.1 101"))
                s.getOutputStream().apply {
                    write(byteArrayOf(0x89.toByte(), 0x02.toByte(), 0x70.toByte(), 0x69.toByte())) // ping "pi"
                    flush()
                }
                assertTrue("上行帧必须到达上游", frameSeen.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(0x89.toByte(), 0x02.toByte(), 0x70.toByte(), 0x69.toByte()), upstreamGotFrame[0]?.toList())
            }
        } finally {
            proxy.stop()
            server.close()
        }
    }

    /** 升级全链路：101 + 头透传 + 升级后下行帧到达客户端。 */
    @org.junit.Test
    fun upgradeResponseAndFramesFlowThrough() {
        val server = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        thread {
            try {
                server.accept().use { up ->
                    val input = up.getInputStream()
                    val head = StringBuilder()
                    while (true) {
                        val c = input.read()
                        if (c < 0) break
                        head.append(c.toChar())
                        if (head.toString().endsWith("\r\n\r\n")) break
                    }
                    val out = up.getOutputStream()
                    out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.write(byteArrayOf(0x81.toByte(), 0x02.toByte(), 0x68.toByte(), 0x69.toByte())) // 文本帧 "hi"
                    out.flush()
                    while (input.read() >= 0) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        val proxy = LoopbackProxy("http://127.0.0.1:${server.localPort}", {}, DirectConnector(server.localPort))
        val port = proxy.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().apply {
                    write(("GET ${proxy.basePath}/api/remote.mux HTTP/1.1\r\nHost: x\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: k=\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val input = s.getInputStream()
                val head = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) break
                    head.append(c.toChar())
                    if (head.toString().endsWith("\r\n\r\n")) break
                }
                val headText = head.toString()
                assertTrue(headText.startsWith("HTTP/1.1 101"))
                assertTrue("101 的 Connection/Upgrade 头必须透传", headText.contains("Upgrade: websocket", ignoreCase = true))
                val frame = ByteArray(4)
                var off = 0
                while (off < 4) {
                    val n = input.read(frame, off, 4 - off)
                    if (n < 0) break
                    off += n
                }
                assertEquals("升级后帧字节必须原样到达客户端", listOf(0x81.toByte(), 0x02.toByte(), 0x68.toByte(), 0x69.toByte()), frame.toList())
            }
        } finally {
            proxy.stop()
            server.close()
        }
    }
}
