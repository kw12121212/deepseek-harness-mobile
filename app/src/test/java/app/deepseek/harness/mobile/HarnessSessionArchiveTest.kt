package app.deepseek.harness.mobile

import java.io.IOException
import java.io.OutputStream
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessSessionArchiveTest {
    @Test
    fun workspacesRetainsGlobalArchivedSessionIds() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived", "ungrouped", "archived")
        val api = api(transport)

        assertEquals(
            listOf(HarnessApi.Workspace("workspace", "Project", "/project", listOf("visible", "archived"))),
            api.workspaces(),
        )
        assertEquals(setOf("archived", "ungrouped"), api.archivedSessionIds)
        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })
    }

    @Test
    fun workspaceBaselineReplacesPreviousArchiveSet() {
        val transport = ArchiveTransport()
        val api = api(transport)
        transport.baseline = baseline("archived", "other")
        api.workspaces()
        assertEquals(listOf("visible"), api.sessions().map { it.id })

        transport.baseline = baseline()
        api.workspaces()

        assertEquals(emptySet<String>(), api.archivedSessionIds)
        assertEquals(listOf("visible", "archived", "other"), api.sessions().map { it.id })
    }

    @Test
    fun switchingServersDoesNotReuseAnotherServersArchiveSet() {
        val transport = ArchiveTransport()
        var server = "http://first"
        val api = HarnessApi({ server }, transport = { transport })
        transport.baseline = baseline("archived")
        api.workspaces()
        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })

        server = "http://second"

        assertEquals(emptySet<String>(), api.archivedSessionIds)
        assertEquals(listOf("visible", "archived", "other"), api.sessions().map { it.id })
    }

    @Test
    fun lateArchiveResponseDoesNotReplaceAnotherServersBaseline() {
        val first = ArchiveTransport()
        val second = ArchiveTransport()
        var server = "http://first"
        val api = HarnessApi({ server }, transport = { if (server == "http://first") first else second })
        first.mutationValue = JSONObject("""{"archivedSessionIds":["archived"]}""")
        second.baseline = baseline("other")
        first.beforeMutationReply = {
            server = "http://second"
            api.workspaces()
        }

        assertEquals(setOf("archived"), api.archiveSession("archived"))

        assertEquals(setOf("other"), api.archivedSessionIds)
        assertEquals(listOf("visible", "archived"), api.sessions().map { it.id })
        server = "http://first"
        assertEquals(setOf("archived"), api.archivedSessionIds)
        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })
    }

    @Test
    fun archiveSessionDefaultsToKeepingActivityAndFiltersList() {
        val transport = ArchiveTransport()
        val api = api(transport)
        assertEquals(listOf("visible", "archived", "other"), api.sessions().map { it.id })
        transport.mutationValue = JSONObject("""{"archivedSessionIds":["archived","other"]}""")

        assertEquals(setOf("archived", "other"), api.archiveSession("archived"))

        assertRequest(transport, "workspace/archiveSession", "archived", false)
        assertEquals(setOf("archived", "other"), api.archivedSessionIds)
        assertEquals(listOf("visible"), api.sessions().map { it.id })
    }

    @Test
    fun archiveSessionSendsExplicitStopActivity() {
        val transport = ArchiveTransport()
        val api = api(transport)
        transport.mutationValue = JSONObject("""{"archivedSessionIds":["archived"]}""")

        api.archiveSession("archived", stopActivity = true)

        assertRequest(transport, "workspace/archiveSession", "archived", true)
    }

    @Test
    fun unarchiveSessionRestoresListAndKeepsOtherArchives() {
        val transport = ArchiveTransport()
        val api = api(transport)
        transport.baseline = baseline("archived", "other")
        api.workspaces()
        assertEquals(listOf("visible"), api.sessions().map { it.id })
        transport.mutationValue = JSONObject("""{"archivedSessionIds":["other"]}""")

        assertEquals(setOf("other"), api.unarchiveSession("archived"))

        assertRequest(transport, "workspace/unarchiveSession", "archived")
        assertEquals(setOf("other"), api.archivedSessionIds)
        assertEquals(listOf("visible", "archived"), api.sessions().map { it.id })
    }

    @Test
    fun sessionActiveFailurePreservesArchiveSet() {
        val transport = ArchiveTransport()
        val api = api(transport)
        transport.baseline = baseline("other")
        api.workspaces()
        transport.mutationError = JSONObject("""{"code":"workspace/session-active","message":"Session is active"}""")

        val failure = assertThrows(HarnessApi.RemoteFailure::class.java) { api.archiveSession("archived") }

        assertEquals("workspace/session-active", failure.code)
        assertEquals(setOf("other"), api.archivedSessionIds)
        assertEquals(listOf("visible", "archived"), api.sessions().map { it.id })
    }

    @Test
    fun unarchiveFailurePreservesArchiveSet() {
        val transport = ArchiveTransport()
        val api = api(transport)
        transport.baseline = baseline("archived")
        api.workspaces()
        transport.mutationError = JSONObject("""{"code":"workspace/not-found","message":"Unavailable"}""")

        assertThrows(HarnessApi.RemoteFailure::class.java) { api.unarchiveSession("archived") }

        assertEquals(setOf("archived"), api.archivedSessionIds)
        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })
    }

    private fun api(transport: ArchiveTransport) = HarnessApi({ "http://127.0.0.1" }, transport = { transport })

    private fun baseline(vararg archivedIds: String): JSONObject = JSONObject("""
        {"type":"baseline","value":{
          "items":[{"workspaceId":"workspace","title":"Project","path":"/project","sessionIds":["visible","archived"]}],
          "archivedSessionIds":[]
        }}
    """).apply { getJSONObject("value").put("archivedSessionIds", org.json.JSONArray(archivedIds.toList())) }

    private fun assertRequest(transport: ArchiveTransport, method: String, sessionId: String, stopActivity: Boolean? = null) {
        val envelope = transport.requests.last { it.getString("method") != "session/list" }
        assertEquals(method, envelope.getString("method"))
        val args = envelope.getJSONObject("payload").getJSONObject("args")
        assertEquals(setOf("request"), args.keys().asSequence().toSet())
        val request = args.getJSONObject("request")
        assertEquals(sessionId, request.getString("sessionId"))
        if (stopActivity == null) {
            assertEquals(setOf("sessionId"), request.keys().asSequence().toSet())
        } else {
            assertEquals(setOf("sessionId", "stopActivity"), request.keys().asSequence().toSet())
            assertEquals(stopActivity, request.getBoolean("stopActivity"))
        }
    }

    private class ArchiveTransport : HarnessTransport {
        var baseline = JSONObject()
        var mutationValue = JSONObject()
        var mutationError: JSONObject? = null
        var beforeMutationReply: (() -> Unit)? = null
        val requests = mutableListOf<JSONObject>()

        override fun rpc(envelope: JSONObject): JSONObject {
            requests += envelope
            val result = if (envelope.getString("method") == "session/list") {
                JSONObject("""{"ok":true,"value":{"items":[
                  {"sessionId":"visible","updatedAt":3,"blank":false},
                  {"sessionId":"archived","updatedAt":2,"blank":false},
                  {"sessionId":"other","updatedAt":1,"blank":false}
                ]}}""")
            } else {
                beforeMutationReply?.invoke()
                mutationError?.let { JSONObject().put("ok", false).put("error", it) }
                    ?: JSONObject().put("ok", true).put("value", mutationValue)
            }
            return JSONObject().put("type", "server-response").put("rpcId", envelope.getString("rpcId"))
                .put("result", result)
        }

        override fun openMux(listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                override fun request(): Request = Request.Builder().url("http://127.0.0.1/api/remote.mux").build()
                override fun queueSize(): Long = 0
                override fun send(bytes: ByteString): Boolean = error("Unexpected binary frame")
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    if (frame.getString("type") == "open") {
                        assertEquals("workspace/follow", frame.getString("endpoint"))
                        listener.onMessage(this, JSONObject().put("type", "item")
                            .put("streamId", frame.getString("streamId")).put("value", baseline).toString())
                    }
                    return true
                }
                override fun close(code: Int, reason: String?): Boolean = true
                override fun cancel() = Unit
            }
            listener.onOpen(socket, Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
            return socket
        }

        override fun export(sessionId: String, includeDescendants: Boolean, sink: OutputStream) = error("Unexpected export")
        override fun streamFailure(response: Response?, cause: Throwable): IOException = IOException(cause)
    }
}
