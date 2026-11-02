package app.deepseek.harness.mobile

import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandCatalogTest {
    private val host = listOf(CommandInfo("host-only", "From Host", "<text>"))
    private val fallback = listOf(CommandInfo("fallback-only", "Local"))
    private val key = "server-a" to "session-a"

    @Test fun parsesHostFieldsAndIgnoresUnknownFields() {
        val result = parseCommands(JSONArray("""[
            {"definitionId":"@deepseek-ai/dsh-command-feedback","name":"feedback",
             "description":"Record feedback about this session",
             "input":{"hint":"<text>","attachments":true},"future":{"enabled":true}}
        ]"""))
        assertEquals(listOf(CommandInfo("feedback", "Record feedback about this session", "<text>")), result)
    }

    @Test fun missingFieldsDefaultOrSkipNamelessEntries() {
        assertEquals(listOf(CommandInfo("compact", "")), parseCommands(JSONArray("""[
            {}, {"description":"missing name"}, {"name":"compact"}, {"name":""},
            {"name":4}, {"name":"bad name"}, null
        ]""")))
    }

    @Test fun optionalFieldsWithWrongTypesAreIgnored() {
        assertEquals(listOf(CommandInfo("goal", ""), CommandInfo("plan", "")),
            parseCommands(JSONArray("""[{"name":"goal","description":true,"input":{"hint":3}},
                {"name":"plan","input":{"hint":" "}}]""")))
    }

    @Test fun emptyArrayIsLegal() {
        assertEquals(emptyList<CommandInfo>(), parseCommands(JSONArray()))
    }

    @Test fun nonArrayIsRejected() {
        assertThrows(IOException::class.java) { parseCommands(JSONObject()) }
    }

    @Test fun apiUsesAgentIdAndPreservesEmptyArray() {
        val requests = mutableListOf<JSONObject>()
        val transport = object : HarnessTransport {
            override fun rpc(envelope: JSONObject): JSONObject {
                requests.add(envelope)
                return JSONObject().put("type", "server-response").put("rpcId", envelope.getString("rpcId"))
                    .put("result", JSONObject().put("ok", true).put("value", JSONArray()))
            }
            override fun openMux(listener: WebSocketListener): WebSocket = error("Unexpected stream")
            override fun export(sessionId: String, includeDescendants: Boolean, sink: OutputStream) = error("Unexpected export")
            override fun streamFailure(response: Response?, cause: Throwable): IOException = error("Unexpected failure")
        }
        assertEquals(emptyList<CommandInfo>(), HarnessApi({ "server-a" }, transport = { transport }).commandCatalog("session-a"))
        assertEquals(1, requests.size)
        assertEquals("commands/list", requests.single().getString("method"))
        val args = requests.single().getJSONObject("payload").getJSONObject("args")
        assertEquals(setOf("agentId"), args.keys().asSequence().toSet())
        assertEquals("session-a", args.getString("agentId"))
    }

    @Test fun filterMatchesPrefixAndContainsIgnoringCase() {
        val commands = listOf(CommandInfo("replan", ""), CommandInfo("PLAN", ""), CommandInfo("export", ""))
        assertEquals(listOf(commands[1], commands[0]), filterCommands(commands, " /pLaN "))
    }

    @Test fun filterMatchesDescriptionAndInputHint() {
        val commands = listOf(CommandInfo("feedback", "Record feedback"), CommandInfo("goal", "", "<objective>"))
        assertEquals(listOf(commands[0]), filterCommands(commands, "RECORD"))
        assertEquals(listOf(commands[1]), filterCommands(commands, "OBJECTIVE"))
    }

    @Test fun blankFilterPreservesOrderAndNoMatchStaysEmpty() {
        assertEquals(host, filterCommands(host, " / "))
        assertEquals(emptyList<CommandInfo>(), filterCommands(host, "absent"))
    }

    @Test fun hostCatalogWinsWithoutWarning() {
        val warnings = mutableListOf<Exception>()
        assertEquals(host, CommandCatalog(logFailure = warnings::add).load(key, fallback) { host })
        assertEquals(emptyList<Exception>(), warnings)
    }

    @Test fun emptyCatalogFallsBackAndWarnsOnceWithinTtl() {
        val warnings = mutableListOf<Exception>()
        val cache = CommandCatalog(clock = { 0L }, logFailure = warnings::add)
        assertEquals(fallback, cache.load(key, fallback) { emptyList() })
        assertEquals(fallback, cache.load(key, fallback) { error("Should use cached fallback") })
        assertEquals(1, warnings.size)
        assertEquals("Empty commands/list catalog", warnings.single().message)
    }

    @Test fun requestFailureFallsBackAndLogsOriginalError() {
        val warnings = mutableListOf<Exception>()
        val failure = IOException("Host unavailable")
        assertEquals(fallback, CommandCatalog(logFailure = warnings::add).load(key, fallback) { throw failure })
        assertEquals(listOf(failure), warnings)
    }

    @Test fun timeoutFallsBackWithoutEscaping() {
        val warnings = mutableListOf<Exception>()
        val timeout = SocketTimeoutException("Read timed out")
        assertEquals(fallback, CommandCatalog(logFailure = warnings::add).load(key, fallback) { throw timeout })
        assertEquals(listOf(timeout), warnings)
    }

    @Test fun invalidResponseFallsBack() {
        val warnings = mutableListOf<Exception>()
        assertEquals(fallback, CommandCatalog(logFailure = warnings::add).load(key, fallback) { parseCommands(JSONObject()) })
        assertEquals(1, warnings.size)
        assertEquals("Invalid commands/list catalog", warnings.single().message)
    }

    @Test fun malformedJsonFallsBack() {
        val warnings = mutableListOf<Exception>()
        assertEquals(fallback, CommandCatalog(logFailure = warnings::add).load(key, fallback) {
            parseCommands(JSONArray("invalid JSON"))
        })
        assertEquals(1, warnings.size)
        assertTrue(warnings.single() is org.json.JSONException)
    }

    @Test fun failedCatalogRetriesAfterTtl() {
        var now = 0L
        val warnings = mutableListOf<Exception>()
        val cache = CommandCatalog(clock = { now }, logFailure = warnings::add)
        assertEquals(fallback, cache.load(key, fallback) { throw IOException("Offline") })
        now = 29_999L
        assertEquals(fallback, cache.load(key, fallback) { error("Should use cached fallback") })
        now = 30_000L
        assertEquals(host, cache.load(key, fallback) { host })
        assertEquals(1, warnings.size)
    }

    @Test fun cacheExpiresAtThirtySeconds() {
        var now = 0L
        var calls = 0
        val cache = CommandCatalog(clock = { now }, logFailure = { throw AssertionError(it) })
        fun load() = cache.load(key, fallback) { calls++; host.map { it.copy(description = "Fetch $calls") } }
        assertEquals("Fetch 1", load().single().description)
        now = 29_999L
        assertEquals("Fetch 1", load().single().description)
        assertEquals(1, calls)
        now = 30_000L
        assertEquals("Fetch 2", load().single().description)
        assertEquals(2, calls)
    }

    @Test fun changedSessionOrServerCannotReuseCatalog() {
        val cache = CommandCatalog(logFailure = { throw AssertionError(it) })
        assertEquals(host, cache.load(key, fallback) { host })
        val otherSession = listOf(CommandInfo("other-session", ""))
        assertEquals(otherSession, cache.load("server-a" to "session-b", fallback) { otherSession })
        val otherServer = listOf(CommandInfo("other-server", ""))
        assertEquals(otherServer, cache.load("server-b" to "session-b", fallback) { otherServer })
    }

    @Test fun invalidationRefetchesEvenForSameSession() {
        val cache = CommandCatalog(logFailure = { throw AssertionError(it) })
        assertEquals(host, cache.load(key, fallback) { host })
        cache.invalidate()
        val updated = listOf(CommandInfo("updated", ""))
        assertEquals(updated, cache.load(key, fallback) { updated })
    }

    @Test fun invalidationDuringFetchPreventsStaleCacheWrite() {
        val cache = CommandCatalog(logFailure = { throw AssertionError(it) })
        assertEquals(fallback, cache.load(key, fallback) { cache.invalidate(); host })
        val updated = listOf(CommandInfo("updated", ""))
        assertEquals(updated, cache.load(key, fallback) { updated })
    }
}
