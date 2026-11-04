package app.deepseek.harness.mobile.tunnel

import android.content.Context

/**
 * 隧道配置的持久化：服务器地址（host 侧 serve 暴露的 https 根地址）、auth key、节点主机名。
 * key 不打日志、不进备份（迁移到 ts-token-profile 时随连接资料走）；节点状态目录
 * （filesDir/tstunnel）由 tsnet 自行持久化，强杀后免 key 重连依赖它，不在本 store 里。
 */
internal interface TunnelConfigStore {
    /** 生效的服务器地址（https 根地址，无末尾斜杠）；未配置为空串。 */
    fun serverBase(): String

    fun setServerBase(value: String)

    /** 保存的 auth key；未保存为空串（已注册节点凭持久状态免 key 重连）。 */
    fun authKey(): String

    fun setAuthKey(value: String)

    fun clearAuthKey()

    /** 持久化的节点主机名（首次调用时生成并保存，之后稳定）。 */
    fun hostname(): String

    /** 连接资料携带 hostname 约定时显式写入（ts-token-profile）。 */
    fun setHostname(value: String)
}

internal class PrefsTunnelConfigStore(context: Context) : TunnelConfigStore {
    private val prefs = context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)

    override fun serverBase(): String = prefs.getString(KEY_SERVER, null).orEmpty()
    override fun setServerBase(value: String) {
        prefs.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()
    }

    override fun authKey(): String = prefs.getString(KEY_AUTH, null).orEmpty()
    override fun setAuthKey(value: String) {
        prefs.edit().putString(KEY_AUTH, value.trim()).apply()
    }

    override fun clearAuthKey() {
        prefs.edit().remove(KEY_AUTH).apply()
    }

    override fun hostname(): String {
        prefs.getString(KEY_HOSTNAME, null)?.let { return it }
        val name = TunnelNames.defaultHostname()
        prefs.edit().putString(KEY_HOSTNAME, name).apply()
        return name
    }

    override fun setHostname(value: String) {
        prefs.edit().putString(KEY_HOSTNAME, value.trim()).apply()
    }

    private companion object {
        const val KEY_SERVER = "server_base"
        const val KEY_AUTH = "auth_key"
        const val KEY_HOSTNAME = "hostname"
    }
}
