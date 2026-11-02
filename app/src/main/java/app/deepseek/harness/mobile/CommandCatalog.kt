package app.deepseek.harness.mobile

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException

internal data class CommandInfo(val name: String, val description: String, val inputHint: String? = null)

internal fun parseCommands(response: Any?): List<CommandInfo> {
    val catalog = response as? JSONArray ?: throw IOException("Invalid commands/list catalog")
    return buildList {
        for (index in 0 until catalog.length()) {
            val command = catalog.optJSONObject(index) ?: continue
            val name = (command.opt("name") as? String)?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
                ?: continue
            add(CommandInfo(
                name,
                command.opt("description") as? String ?: "",
                (command.optJSONObject("input")?.opt("hint") as? String)?.takeIf(String::isNotBlank),
            ))
        }
    }
}

internal fun fallbackCommands(tr: (String, String) -> String): List<CommandInfo> = listOf(
    CommandInfo("compact", tr("压缩较早的对话历史", "Compact older conversation history")),
    CommandInfo("export", tr("将此会话日志下载为 ZIP", "Download this session log as a ZIP archive")),
    CommandInfo("feedback", tr("记录对此会话的反馈", "Record feedback about this session")),
    CommandInfo("goal", tr("设置或查看长任务目标", "Set or view the goal for a long-running task")),
    CommandInfo("permission", tr("切换权限预设（沙盒与审批策略）", "Switch the permission preset (sandbox mode + approval policy)")),
    CommandInfo("plan", tr("进入或退出计划模式", "Enter or leave plan mode")),
)

internal fun filterCommands(commands: List<CommandInfo>, query: String): List<CommandInfo> {
    val term = query.trim().removePrefix("/")
    if (term.isEmpty()) return commands
    return commands.filter {
        it.name.contains(term, ignoreCase = true) || it.description.contains(term, ignoreCase = true) ||
            it.inputHint?.contains(term, ignoreCase = true) == true
    }.sortedBy { !it.name.startsWith(term, ignoreCase = true) }
}

/** A single session's short-lived catalog, including failures to avoid repeated requests on reopen. */
internal class CommandCatalog(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val logFailure: (Exception) -> Unit,
) {
    private data class Entry(val key: Pair<String, String>, val commands: List<CommandInfo>, val loadedAt: Long)
    private val lock = Any()
    private val loadLock = Any()
    private var generation = 0L
    private var entry: Entry? = null

    fun invalidate() = synchronized(lock) { generation++; entry = null }

    fun load(key: Pair<String, String>, fallback: List<CommandInfo>, fetch: () -> List<CommandInfo>): List<CommandInfo> = synchronized(loadLock) {
        val admitted = synchronized(lock) {
            entry?.takeIf { it.key == key && clock() - it.loadedAt < TTL_MS }?.let {
                return it.commands.ifEmpty { fallback }
            }
            generation
        }
        val commands = try {
            fetch().also {
                if (it.isEmpty()) logFailure(IOException("Empty commands/list catalog"))
            }
        } catch (error: IOException) {
            logFailure(error)
            emptyList()
        } catch (error: JSONException) {
            logFailure(error)
            emptyList()
        }
        synchronized(lock) {
            // A session or connection switch during the RPC makes this response obsolete.
            if (generation != admitted) return fallback
            entry = Entry(key, commands, clock())
        }
        commands.ifEmpty { fallback }
    }

    companion object { const val TTL_MS = 30_000L }
}
