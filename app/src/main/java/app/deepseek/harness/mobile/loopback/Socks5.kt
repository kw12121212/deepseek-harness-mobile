package app.deepseek.harness.mobile.loopback

import java.io.IOException
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 自实现 SOCKS5 客户端拨号（沿 private-content-assistant 2026-09-24 抓包实证的 libcore
 * 平台事实）：Android 的 SocksSocketImpl 走用户名/口令认证时不查 java.net.Authenticator，
 * 直接发 System.getProperty("user.name") + 空口令，对 tsnet 回环代理（用户名 "tsnet" +
 * 随机口令）必然认证失败。按 RFC 1928/1929 自实现，认证帧带真实口令。
 *
 * 域名用 ADDRTYPE=0x03 交给代理侧解析——ts.net 名走内嵌节点的 MagicDNS，这是回环
 * 承载形态的关键一步（平台栈自己做系统 DNS 时对 ts.net 名直接 UnknownHostException）。
 * 蓝本：private-content-assistant android/loopback/src/main/kotlin/dev/pca/loopback/Socks5.kt。
 */
class Socks5Dialer(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val user: String,
    private val password: String,
    private val connectTimeoutMs: Int = 15_000,
) {
    /** 建立经 SOCKS5 代理到达 target 的 TCP 连接（阻塞）。 */
    fun connect(targetHost: String, targetPort: Int): Socket {
        val s = Socket()
        try {
            s.connect(java.net.InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs)
            val out = s.getOutputStream()
            val inp = s.getInputStream()

            // 问候：提供 no-auth 与 username/password 两法
            out.write(byteArrayOf(5, 2, 0, 2)); out.flush()
            val ver = inp.read(); val method = inp.read()
            if (ver != 5) throw IOException("socks5: bad greeting version $ver")
            when (method) {
                0x02 -> { // RFC 1929
                    val ub = user.toByteArray(Charsets.US_ASCII)
                    val pb = password.toByteArray(Charsets.US_ASCII)
                    if (ub.size > 255 || pb.size > 255) throw IOException("socks5: credential too long")
                    out.write(byteArrayOf(1, ub.size.toByte())); out.write(ub)
                    out.write(pb.size); out.write(pb); out.flush()
                    if (inp.read() != 1) throw IOException("socks5: bad auth version")
                    val status = inp.read()
                    if (status != 0) throw IOException("socks5: auth failed status=$status")
                }
                0x00 -> { /* 代理未要求认证（不应出现于 tsnet 回环，容忍） */ }
                else -> throw IOException("socks5: no acceptable auth method ($method)")
            }

            // CONNECT：域名形态，代理侧解析（ts.net → 内嵌节点 MagicDNS）
            val hb = targetHost.toByteArray(Charsets.US_ASCII)
            if (hb.size > 255) throw IOException("socks5: host too long")
            out.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte())); out.write(hb)
            out.write(byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte())); out.flush()

            val rv = inp.read(); val rep = inp.read()
            if (rv != 5) throw IOException("socks5: bad reply version $rv")
            if (rep != 0) throw IOException("socks5: connect rejected rep=$rep")
            inp.read() // RSV
            when (val atyp = inp.read()) {
                1 -> skip(inp, 4)
                3 -> skip(inp, 1 + inp.read())
                4 -> skip(inp, 16)
                else -> throw IOException("socks5: bad atyp $atyp")
            }
            skip(inp, 2) // BND.PORT
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun skip(inp: java.io.InputStream, n: Int) {
        var left = n
        val buf = ByteArray(64)
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(left, buf.size))
            if (r < 0) throw IOException("socks5: eof in reply")
            left -= r
        }
    }
}

/**
 * 经 SOCKS5 隧道到达 target 并在其上分层 TLS。返回的 SSLSocket 的 SNI 与证书校验
 * （含主机名）都按 targetHost 进行（ts.net 的 LE 证书可过），全程不触碰系统 DNS——
 * 平台 HTTP 栈（HttpURLConnection，含 Cronet/HttpEngine 后端）会忽略自定义
 * SSLSocketFactory、自己先做系统 DNS 解析，对 MagicDNS 域名直接 UnknownHostException
 * （EMUI 15 / Android 16 真机+模拟器实证）。HTTP/1.1 由调用方手写在本 socket 上。
 */
fun Socks5Dialer.tlsConnect(targetHost: String, targetPort: Int): SSLSocket =
    layerTls(connect(targetHost, targetPort), targetHost, targetPort)

/**
 * 在已连接的 raw socket 上分层 TLS（SNI 为 targetHost）并完成握手；失败时关闭 raw。
 * SSLSocket 本身只校验证书链、不校验主机名：必须显式开启 HTTPS 端点识别，否则任何
 * 持有公共 CA 证书的对端都能冒充服务。
 */
fun layerTls(
    raw: Socket,
    targetHost: String,
    targetPort: Int,
    factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
): SSLSocket = try {
    (factory.createSocket(raw, targetHost, targetPort, true) as SSLSocket).also { tls ->
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
    }
} catch (e: Exception) {
    try { raw.close() } catch (_: Exception) {}
    throw e
}

/** 上游连接器：为转发目标建立已连接的 socket（含承载方式与 TLS 分层）。协议层只依赖本接口。 */
fun interface UpstreamConnector {
    fun connect(url: URL): Socket
}

/** 正式承载的上游连接器：经内嵌节点的 SOCKS5 回环代理拨到 serve 端点并在其上分层 TLS。 */
class Socks5TlsConnector(
    private val socksAddr: String,
    private val proxyCred: String,
) : UpstreamConnector {
    override fun connect(url: URL): Socket {
        val socksHost = socksAddr.substringBefore(':')
        val socksPort = socksAddr.substringAfter(':').toInt()
        check(proxyCred.isNotEmpty()) { "SOCKS 口令缺失" }
        val targetPort = if (url.port > 0) url.port else 443
        return Socks5Dialer(socksHost, socksPort, SOCKS_USER, proxyCred)
            .tlsConnect(url.host, targetPort)
    }

    companion object {
        /** tsnet 回环 SOCKS5 代理的固定用户名（tsnet.go 的口径）。 */
        const val SOCKS_USER = "tsnet"
    }
}
