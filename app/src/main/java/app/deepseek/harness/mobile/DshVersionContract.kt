package app.deepseek.harness.mobile
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 本应用经真机验证适配的上游 dsh 版本；与 bridge 侧 CompatibleDSHVersion 同源同义。
 *  版本升级（含 rc→rc）一律走显式变更并重新真机验证，不做最低兼容范围放行。 */
internal const val COMPATIBLE_DSH_VERSION = "0.2.0-rc.2"

/** 从 dsh 落地页 HTML 提取 __DSH_BOOT__ 对象的 release.version。
 *  对象缺失、畸形或无版本时返回 null：调用方必须按不兼容处理（fail closed）。 */
internal fun parseDshBootVersion(page: String): String? {
    val marker = "__DSH_BOOT__\"]"
    val markerAt = page.indexOf(marker)
    if (markerAt < 0) return null
    val start = page.indexOf('{', markerAt + marker.length)
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    var i = start
    while (i < page.length) {
        val ch = page[i]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
        } else when (ch) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return runCatching {
                    JSONObject(page.substring(start, i + 1)).getJSONObject("release").getString("version").takeIf { it.isNotBlank() }
                }.getOrNull()
            }
        }
        i++
    }
    return null
}

/** 上游版本探测结果。未认证（401/403/3xx 挑战）不是「不兼容」：跳过版本门禁，
 *  走既有认证流程；认证后仍取不到版本才按不兼容处理（fail closed）。 */
internal sealed class DshVersionProbe {
    internal object Unauthenticated : DshVersionProbe()
    internal object Undetectable : DshVersionProbe()
    internal data class Version(val value: String) : DshVersionProbe()
}

/** 版本门禁决策：连接前唯一入口，便于单测覆盖三个分支。 */
internal sealed class DshVersionGate {
    internal object Proceed : DshVersionGate()
    internal object ProceedToAuthentication : DshVersionGate()
    internal data class Reject(val messageKey: String, val upstreamVersion: String?) : DshVersionGate()
}

internal fun dshVersionGateDecision(probe: DshVersionProbe): DshVersionGate = when (probe) {
    is DshVersionProbe.Unauthenticated -> DshVersionGate.ProceedToAuthentication
    is DshVersionProbe.Undetectable -> DshVersionGate.Reject("undetectable", null)
    is DshVersionProbe.Version ->
        if (probe.value == COMPATIBLE_DSH_VERSION) DshVersionGate.Proceed
        else DshVersionGate.Reject("mismatch", probe.value)
}

/** 探测上游 dsh 版本：已认证 GET 根路径并解析 __DSH_BOOT__ 的 release.version。
 *  读取上限 1 MiB。响应为认证挑战（3xx/401/403）时返回 Unauthenticated——此时不判兼容性，
 *  交由既有认证流程处理；请求失败、非 200、超限或解析不出返回 Undetectable。 */
internal fun fetchDshServerVersion(origin: String, accessToken: String?): DshVersionProbe = runCatching {
    val connection = URL(app.deepseek.harness.mobile.tunnel.TunnelRoutes.rewrite("$origin/")).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 25000
    connection.instanceFollowRedirects = false
    accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
    harnessCookies(origin, CookieManager.getInstance().getCookie(origin))?.let { connection.setRequestProperty("Cookie", it) }
    try {
        val status = connection.responseCode
        if (status in 300..399 || status == 401 || status == 403) return@runCatching DshVersionProbe.Unauthenticated
        if (status != 200) return@runCatching DshVersionProbe.Undetectable
        val body = connection.inputStream.use { input ->
            val buffer = ByteArray(1 shl 20)
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset == buffer.size && input.read() >= 0) return@runCatching DshVersionProbe.Undetectable
            String(buffer, 0, offset, Charsets.UTF_8)
        }
        parseDshBootVersion(body)?.let { DshVersionProbe.Version(it) } ?: DshVersionProbe.Undetectable
    } finally { connection.disconnect() }
}.getOrDefault(DshVersionProbe.Undetectable)
