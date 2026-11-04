package app.deepseek.harness.mobile

import android.net.ConnectivityManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * tailscale-private-transport 里程碑的 ts-embed-spike 真机验证（planned change ts-embed-spike）。
 *
 * 以 tag:dsh-mobile tagged auth key 在 App 进程内起 userspace tsnet 节点，经 host 的
 * tailscale serve --https 入口访问 dshbridge，走完一次 pair 与一条白名单 RPC（session/list）。
 *
 * 由 instrumentation 参数驱动，缺参数自动跳过（不进常规回归）：
 *   adb shell am instrument -w -e authkey tskey-... -e origin https://<节点域名>:<serve端口> \
 *     -e pairPath /xxxx/yyyy -e pairingCode zzzz \
 *     [-e hostname dsh-spike] app.deepseek.harness.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 平台事实（沿 private-content-assistant 2026-09 真机实证，蓝本 android/loopback/Socks5.kt）：
 * 1. Android SocksSocketImpl 的用户名/口令认证发 user.name+空口令，tsnet 回环代理必拒——
 *    按 RFC 1928/1929 自实现认证；
 * 2. ts.net 域名必须以 ADDRTYPE=0x03 交给内嵌节点 MagicDNS 解析（系统 DNS 不认识 ts.net）。
 */
class TstunnelSpikeTest {
    private fun arg(name: String): String? = InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotEmpty() }

    @Test
    fun taggedNodePairsAndCallsRpcThroughServe() {
        val authKey = arg("authkey")
        val origin = arg("origin")
        val pairPath = arg("pairPath")
        val pairingCode = arg("pairingCode")
        assumeTrue(
            "spike 参数缺失（需要 authkey/origin/pairPath/pairingCode），跳过——本测试只在真机 spike 运行时执行",
            authKey != null && origin != null && pairPath != null && pairingCode != null,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hostname = arg("hostname") ?: "dsh-spike-${Build.MODEL.take(8).lowercase().replace(Regex("[^a-z0-9-]"), "")}"

        // Android 硬前置（蓝本实证）：SELinux 禁 netlink → 注入网卡快照；默认路由可注入则注入。
        tstunnel.Tstunnel.setInterfacesJSON(interfacesSnapshotJson())
        defaultRouteIfname()?.let { tstunnel.Tstunnel.setDefaultRouteIfname(it) }

        val dir = java.io.File(instrumentation.targetContext.filesDir, "tstunnel-spike").apply { mkdirs() }
        val node = tstunnel.Tstunnel.newNode(hostname, dir.absolutePath, authKey)
        node.enableStderrLogs()
        try {
            node.start()
            node.up() // 阻塞至登录完成（tagged key 注册或持久状态免 key 重连）

            val status = JSONObject(node.statusJSON())
            assertEquals("tsnet 节点未进入 Running：${status.toString().take(300)}", "Running", status.optString("BackendState"))
            val ips = status.optJSONObject("Self")?.optJSONArray("TailscaleIPs")
            assertNotNull("StatusJSON 缺少本机 tailnet 地址：${status.toString().take(300)}", ips?.takeIf { it.length() > 0 })

            val socksAddr = node.loopback().split(":")
            val socksPort = socksAddr.last().toInt()
            val credential = node.proxyCredential()

            // pair：经 SOCKS5 回环 → tailnet → serve → dshbridge。
            val pairBody = JSONObject().put("deviceName", "${Build.MODEL}-spike").put("pairingCode", pairingCode).toString()
            val pairResponse = httpsPost(origin!!, pairPath!!, pairBody, null, socksPort, credential)
            assertEquals("pair HTTP 状态 ${pairResponse.first}", 200, pairResponse.first)
            val pair = JSONObject(pairResponse.second)
            assertEquals(1, pair.getInt("protocolVersion"))
            val token = pair.getString("deviceToken")
            assertTrue(token.length >= 32)
            assertNotNull(pair.getJSONObject("routes").optString("rpc"))

            // 白名单 RPC：session/list，Bearer 设备凭据。
            val rpcPath = pair.getJSONObject("routes").getString("rpc")
            val rpcBody = """{"type":"client-request","rpcId":"ts-embed-spike-1","method":"session/list","payload":{"args":{"_request":{}}}}"""
            val rpc = httpsPost(origin, rpcPath, rpcBody, token, socksPort, credential)
            assertEquals("session/list HTTP 状态 ${rpc.first}", 200, rpc.first)
            val result = JSONObject(rpc.second)
            assertTrue("RPC 未成功：${rpc.second.take(300)}", result.optJSONObject("result")?.optBoolean("ok") == true)
        } finally {
            runCatching { node.close() }
        }
    }

    /** NetworkInterface 快照 → 蓝本 ifEntry JSON（interfaces.go 的输入形态）。 */
    private fun interfacesSnapshotJson(): String {
        val array = JSONArray()
        val seen = HashSet<String>()
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
        return array.toString()
    }

    /** 有 ACCESS_NETWORK_STATE 权限时注入默认路由接口名；无权限/无网络则跳过（DERP 中继兜底）。 */
    private fun defaultRouteIfname(): String? = runCatching {
        val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.interfaceName
    }.getOrNull()

    /** RFC 1928/1929 SOCKS5 拨号（蓝本实证结论的自实现，见类注释）。 */
    private fun socks5Connect(proxyPort: Int, user: String, password: String, targetHost: String, targetPort: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", proxyPort), 15000)
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.write(byteArrayOf(5, 2, 0, 2)); out.flush()
            if (inp.read() != 5) throw IOException("socks5: bad greeting")
            when (val method = inp.read()) {
                0x02 -> {
                    val ub = user.toByteArray(Charsets.US_ASCII)
                    val pb = password.toByteArray(Charsets.US_ASCII)
                    out.write(byteArrayOf(1, ub.size.toByte())); out.write(ub)
                    out.write(pb.size); out.write(pb); out.flush()
                    if (inp.read() != 1) throw IOException("socks5: bad auth version")
                    if (inp.read() != 0) throw IOException("socks5: auth failed")
                }
                0x00 -> {}
                else -> throw IOException("socks5: no acceptable auth method ($method)")
            }
            val hb = targetHost.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte())); out.write(hb)
            out.write(byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte())); out.flush()
            if (inp.read() != 5) throw IOException("socks5: bad reply")
            if (inp.read() != 0) throw IOException("socks5: connect rejected")
            inp.read()
            when (inp.read()) {
                1 -> skip(inp, 4)
                3 -> skip(inp, 1 + inp.read())
                4 -> skip(inp, 16)
            }
            skip(inp, 2)
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    private fun skip(inp: InputStream, n: Int) { var left = n; val buf = ByteArray(64); while (left > 0) { val r = inp.read(buf, 0, minOf(left, buf.size)); if (r < 0) throw IOException("socks5: eof"); left -= r } }

    /** origin（https://host:port）经 SOCKS5 → TLS → 手写 HTTP/1.1 POST，返回 (状态码, 正文)。 */
    private fun httpsPost(origin: String, path: String, body: String, bearer: String?, socksPort: Int, credential: String): Pair<Int, String> {
        val host = origin.removePrefix("https://").removePrefix("http://").substringBefore(':')
        val port = origin.substringAfterLast(':').toIntOrNull() ?: 443
        val raw = socks5Connect(socksPort, tstunnel.Tstunnel.TstunnelSocksUser, credential, host, port)
        val tls = javax.net.ssl.SSLContext.getDefault().socketFactory.createSocket(raw, host, port, true) as SSLSocket
        try {
            // 蓝本平台事实：SSLSocket 默认不做端点主机名校验，必须显式开启，
            // 否则任何持公共 CA 证书的对端都能冒充服务（Socks5.kt layerTls 同款）。
            val params = tls.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = params
            tls.startHandshake()
            val out = tls.getOutputStream()
            val headers = buildString {
                append("POST $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/json\r\n")
                bearer?.let { append("Authorization: Bearer $it\r\n") }
                append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n")
            }
            out.write((headers + body).toByteArray(Charsets.UTF_8))
            out.flush()
            val inp = tls.getInputStream()
            val head = StringBuilder()
            while (true) {
                val line = readLine(inp) ?: break
                head.append(line).append('\n')
                if (line.isEmpty()) break
            }
            val statusLine = head.toString().lines().first()
            val status = Regex("(\\d{3})").find(statusLine)?.groupValues?.get(1)?.toInt() ?: throw IOException("无状态行：$statusLine")
            val chunked = head.toString().contains("transfer-encoding: chunked", ignoreCase = true)
            val payload = if (chunked) readChunked(inp) else readLineToEOF(inp)
            return status to payload
        } finally {
            runCatching { tls.close() }
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun readLineToEOF(inp: InputStream): String {
        val buf = ByteArray(1 shl 20)
        var offset = 0
        while (offset < buf.size) {
            val r = inp.read(buf, offset, buf.size - offset)
            if (r < 0) break
            offset += r
        }
        return String(buf, 0, offset, Charsets.UTF_8)
    }

    private fun readChunked(inp: InputStream): String {
        val out = StringBuilder()
        while (true) {
            val sizeLine = readLine(inp) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val chunk = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val r = inp.read(chunk, offset, size - offset)
                if (r < 0) break
                offset += r
            }
            out.append(String(chunk, 0, offset, Charsets.UTF_8))
            readLine(inp)
        }
        return out.toString()
    }
}
