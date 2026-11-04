package app.deepseek.harness.mobile.loopback

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * 回环反向代理（沿 private-content-assistant 定型形态，蓝本
 * android/loopback/LoopbackProxy.kt）：监听 127.0.0.1，把请求转发到 host 侧 serve 端点。
 * App 的全部 dshbridge/dsh 流量走这里：平台 HTTP 栈对回环明文地址工作正常，隧道与
 * ts.net DNS 的全部难点收敛到承载一处（怎么到上游由 [connectUpstream] 注入，本类只管
 * HTTP 协议面的字节纪律）。
 *
 * 承载纪律（spike 实证，2026-09-24）：
 * - 响应体按到达分块即时下发 + 每次读后 flush：SSE/流式判据路径。
 * - Connection: close + 关连接定界，不做 keep-alive 复用（升级请求除外，见下）。
 * - Accept-Encoding 原样转发，Content-Encoding 与响应字节原样透传，由客户端自解。
 * - 只处理带 Content-Length 的请求体；Transfer-Encoding: chunked 的请求按 501 收敛。
 * - 响应方向 transfer-encoding 必须透传（剥掉它客户端会把 chunk 长度行当 body，真机
 *   unexpected_response 实测教训）。
 *
 * 本项目新增（蓝本没有）：**Upgrade 透传**——dsh 的 stream 是 WebSocket；带 Upgrade 头
 * 的请求，Connection/Upgrade/Sec-WebSocket-* 头原样转发（不剥 hop-by-hop、不注
 * Connection: close），响应方向该连接不滤任何头，状态行与头原样回给客户端后纯字节泵。
 * 没有 this，101 响应缺 Connection/Upgrade 头，客户端握手必败。
 */
class LoopbackProxy(
    private val upstreamBase: String,
    private val log: (String) -> Unit,
    private val connectUpstream: UpstreamConnector,
) {
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var stopped = false
    @Volatile var port: Int = 0
        private set

    /**
     * 本次启动的访问令牌：App 的基地址是 http://127.0.0.1:<port>/<令牌>。Android 上
     * 127.0.0.1 由全部 App 共用，不带令牌的请求一律 403 且不拨上游——否则同机任何
     * App 扫到端口就能以本机的 tailnet 身份发请求。放在路径而不是请求头：App 内所有
     * HTTP 客户端都以基地址拼路径，不需要逐一注入请求头。
     */
    private val token: String = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** App 请求的基路径前缀（"/<令牌>"），拼在 http://127.0.0.1:<port> 之后。 */
    val basePath: String = "/$token"

    /** 在役 socket（客户端与上游都记）：stop() 关掉全部，唤醒阻塞在任一端 read 的线程。 */
    private val liveSockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    /** 起监听（端口 0 自动分配），返回实际绑定端口。 */
    fun start(): Int {
        val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        stopped = false
        acceptThread = Thread({
            while (!stopped) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (!stopped) log("proxy.accept 失败：$e")
                    return@Thread
                }
                // 每连接一线程：长响应（SSE/WS）会占住连接，串行处理会被一个流拖死。
                Thread { serve(client) }.start()
            }
        }, "dsh-proxy-accept").apply { start() }
        log("proxy.listening 127.0.0.1:$port -> $upstreamBase")
        return port
    }

    fun stop() {
        stopped = true
        try {
            server?.close()
        } catch (_: IOException) {
        }
        synchronized(liveSockets) {
            for (s in liveSockets) {
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
            liveSockets.clear()
        }
        log("proxy.stopped")
    }

    private fun serve(client: Socket) {
        liveSockets.add(client)
        try {
            client.use { sock ->
                sock.soTimeout = 60_000
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 3) return
                val (method, target) = parts[0] to parts[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val path = stripToken(if (target.startsWith("http")) URL(target).file else target)
                if (path == null) {
                    respond(output, 403, "loopback proxy: forbidden")
                    return
                }
                val upgrade = headers["upgrade"]?.let { it.isNotBlank() } == true
                if (!upgrade && headers["transfer-encoding"] != null) {
                    respond(output, 501, "loopback proxy: chunked request unsupported")
                    return
                }

                val upstream = URL(upstreamBase.trimEnd('/') + path)
                val upSock = connectUpstream.connect(upstream)
                liveSockets.add(upSock)
                try {
                    upSock.soTimeout = UPSTREAM_READ_TIMEOUT_MS
                    val upOut = upSock.outputStream
                    val upHostHeader = upstream.host + if (upstream.port > 0) ":${upstream.port}" else ""
                    val upPath = upstream.file.ifEmpty { "/" }
                    val req = StringBuilder("$method $upPath HTTP/1.1\r\n")
                    req.append("Host: $upHostHeader\r\n")
                    for ((k, v) in headers) {
                        if (k == "host") continue
                        // 升级请求（WebSocket）：Connection/Upgrade/Sec-WebSocket-* 原样转发，
                        // 其余请求仍按 hop-by-hop 全剥并定界 close。
                        if (upgrade) {
                            if (k in UPGRADE_PASSTHROUGH) {
                                req.append("$k: $v\r\n")
                                continue
                            }
                            if (k.startsWith("sec-websocket-")) {
                                req.append("$k: $v\r\n")
                                continue
                            }
                            if (k in HOP_BY_HOP) continue
                            req.append("$k: $v\r\n")
                        } else {
                            if (k in HOP_BY_HOP) continue
                            req.append("$k: $v\r\n")
                        }
                    }
                    if (!upgrade) req.append("Connection: close\r\n")
                    req.append("\r\n")
                    upOut.write(req.toString().toByteArray(Charsets.ISO_8859_1))

                    val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
                    if (bodyLen > 0) {
                        copyExactly(input, upOut, bodyLen)
                    }
                    upOut.flush()

                    val upIn = upSock.inputStream
                    // 升级模式：状态行与头原样透传（不滤 hop-by-hop、不注 close），其后双向字节泵。
                    // 客户端→上游方向独立线程承载（WS ping/pong 与上行帧），任一方向结束即关双端。
                    if (upgrade) {
                        while (true) {
                            val line = readLine(upIn) ?: return
                            output.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                            if (line.isEmpty()) break
                        }
                        output.flush()
                        val upstreamWriter = Thread({
                            try {
                                pumpBytes(input, upOut)
                            } finally {
                                try { upSock.close() } catch (_: Exception) {}
                                try { sock.close() } catch (_: Exception) {}
                            }
                        }, "dsh-proxy-upgrade-up")
                        upstreamWriter.isDaemon = true
                        upstreamWriter.start()
                        pumpBytes(upIn, output)
                        return
                    }
                    val statusLine = readLine(upIn) ?: return
                    output.write((statusLine + "\r\n").toByteArray(Charsets.ISO_8859_1))
                    var hasLen = false
                    while (true) {
                        val line = readLine(upIn) ?: break
                        if (line.isEmpty()) break
                        val lk = line.substringBefore(':').trim().lowercase()
                        if (lk in RESPONSE_HOP_BY_HOP) continue
                        if (lk == "content-length") hasLen = true
                        output.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                    }
                    if (!hasLen) output.write("Connection: close\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()

                    // 按到达分块即时下发 + 每次读后 flush。
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = upIn.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        output.flush()
                    }
                } finally {
                    try { upSock.close() } catch (_: Exception) {}
                    liveSockets.remove(upSock)
                }
            }
        } catch (e: Exception) {
            if (!stopped) log("proxy.serve error=$e")
        } finally {
            liveSockets.remove(client)
        }
    }


    private fun pumpBytes(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    /** 校验并剥掉令牌前缀，返回转发给上游的路径；令牌不符时返回 null。恒时比较。 */
    private fun stripToken(path: String): String? {
        if (!path.startsWith("/")) return null
        val end = path.indexOfAny(charArrayOf('/', '?'), 1).let { if (it < 0) path.length else it }
        val got = path.substring(1, end).toByteArray(Charsets.UTF_8)
        if (!java.security.MessageDigest.isEqual(got, token.toByteArray(Charsets.UTF_8))) return null
        val rest = path.substring(end)
        return if (rest.startsWith("/")) rest else "/$rest"
    }

    private fun respond(output: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $code $msg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) throw IOException("request line/header 超长")
        }
    }

    private fun copyExactly(src: InputStream, dst: OutputStream, len: Int) {
        var remaining = len
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = src.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) throw IOException("请求体早于 Content-Length 结束")
            dst.write(buf, 0, n)
            remaining -= n
        }
    }

    companion object {
        private val HOP_BY_HOP = setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade")

        /** 升级路径必须原样转发的请求头（Connection/Upgrade 携带升级语义）。 */
        private val UPGRADE_PASSTHROUGH = setOf("connection", "upgrade")

        /** 响应方向的剥离集：transfer-encoding 必须透传（见类注释）。 */
        private val RESPONSE_HOP_BY_HOP = HOP_BY_HOP - "transfer-encoding"

        /** 上游读超时：SSE 流保活的十倍，正常闲不出这个窗口。 */
        private const val UPSTREAM_READ_TIMEOUT_MS = 150_000
    }
}
