package app.deepseek.harness.mobile

import java.io.IOException
import java.io.OutputStream
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessSessionArchiveTest {
    @Test
    fun unresolvedSessionAddressLoadsDirectoryThenReusesCache() {
        val transport = ArchiveTransport()
        val api = api(transport)
        assertNull(api.sessionAddress("visible"))

        val address = api.resolveSessionAddress("visible")

        assertEquals("session", address.kind)
        assertEquals("visible", address.toJson().getString("sessionId"))
        assertEquals(SessionMenuDecision.SHOW, sessionMenuDecision(null, address.kind))
        assertEquals("visible", api.resolveSessionAddress("visible").toJson().getString("sessionId"))
        assertEquals(listOf("session/list"), transport.requests.map { it.getString("method") })
    }

    @Test
    fun missingSessionAddressFailsWithoutBecomingMenuReady() {
        val transport = ArchiveTransport()
        val api = api(transport)

        assertThrows(IOException::class.java) { api.resolveSessionAddress("missing") }

        assertNull(api.sessionAddress("missing"))
        assertEquals(SessionMenuDecision.RESOLVE, sessionMenuDecision(null, api.sessionAddress("missing")?.kind))
        assertEquals(listOf("session/list"), transport.requests.map { it.getString("method") })
    }

    @Test
    fun resolvingSubagentAddressKeepsMenuHidden() {
        val transport = ArchiveTransport()
        transport.sessionItems = JSONArray("""[{"sessionId":"child","origin":"subagent","parentSessionId":"parent","blank":false}]""")
        val api = api(transport)

        val address = api.resolveSessionAddress("child")

        assertEquals("subagent", address.kind)
        assertEquals(SessionMenuDecision.HIDE, sessionMenuDecision(null, address.kind))
    }

    @Test
    fun ensureArchiveBaselineFetchesOncePerServer() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived")
        val api = api(transport)

        api.ensureArchiveBaseline()
        api.ensureArchiveBaseline()

        assertEquals(1, transport.workspaceFollowOpens)
    }

    @Test
    fun deleteWorkspaceUsesConfirmedNamedRequestShape() {
        val transport = ArchiveTransport()
        transport.mutationValue = JSONObject("""{"deleted":true}""")
        val api = api(transport)

        api.deleteWorkspace("workspace-id")

        val envelope = transport.requests.single()
        assertEquals("workspace/delete", envelope.getString("method"))
        val args = envelope.getJSONObject("payload").getJSONObject("args")
        assertEquals(setOf("request"), args.keys().asSequence().toSet())
        val request = args.getJSONObject("request")
        assertEquals(setOf("workspaceId"), request.keys().asSequence().toSet())
        assertEquals("workspace-id", request.getString("workspaceId"))
    }

    @Test
    fun deleteWorkspaceFailurePreservesWorkspaceAndSessionState() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived")
        val api = api(transport)
        api.workspaces()
        api.sessionSnapshot()
        transport.mutationError = JSONObject("""{"code":"workspace/not-found","message":"Workspace not found"}""")

        val failure = assertThrows(HarnessApi.RemoteFailure::class.java) { api.deleteWorkspace("workspace") }

        assertEquals("workspace/not-found", failure.code)
        assertEquals(
            listOf(HarnessApi.Workspace("workspace", "Project", "/project", listOf("visible", "archived"))),
            api.workspaces(),
        )
        assertEquals(setOf("archived"), api.archivedSessionIds)
        assertTrue(api.isSessionAddressResolved("visible"))
        assertTrue(api.isSessionAddressResolved("archived"))
        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })
    }

    @Test
    fun renameWorkspaceUsesConfirmedNamedRequestShape() {
        val transport = ArchiveTransport()
        val api = api(transport)

        api.renameWorkspace("workspace-id", "新工作区")

        val envelope = transport.requests.single()
        assertEquals("workspace/rename", envelope.getString("method"))
        val args = envelope.getJSONObject("payload").getJSONObject("args")
        assertEquals(setOf("request"), args.keys().asSequence().toSet())
        val request = args.getJSONObject("request")
        assertEquals(setOf("workspaceId", "title"), request.keys().asSequence().toSet())
        assertEquals("workspace-id", request.getString("workspaceId"))
        assertEquals("新工作区", request.getString("title"))
    }

    @Test
    fun renameWorkspaceFailureIsReportedWithoutChangingBaseline() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived")
        val api = api(transport)
        val existing = api.workspaces()
        transport.mutationError = JSONObject("""{"code":"workspace/name-conflict","message":"Name already exists"}""")

        val failure = assertThrows(HarnessApi.RemoteFailure::class.java) { api.renameWorkspace("workspace", "Taken") }

        assertEquals("workspace/name-conflict", failure.code)
        assertEquals("Project", existing.single().title)
        assertEquals(
            listOf(HarnessApi.Workspace("workspace", "Project", "/project", listOf("visible", "archived"))),
            api.workspaces(),
        )
        assertEquals(setOf("archived"), api.archivedSessionIds)
    }

    @Test
    fun sessionsRemainUnfilteredBeforeArchiveBaseline() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived")
        val api = api(transport)

        assertEquals(listOf("visible", "archived", "other"), api.sessions().map { it.id })
        assertEquals(0, transport.workspaceFollowOpens)
    }

    @Test
    fun ensureArchiveBaselineMakesArchiveFilteringEffective() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived")
        val api = api(transport)

        api.ensureArchiveBaseline()

        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })
        assertEquals(1, transport.workspaceFollowOpens)
        assertEquals(listOf("session/list"), transport.requests.map { it.getString("method") })
    }

    @Test
    fun ensureArchiveBaselineFetchesOnceEvenWhenArchiveSetIsEmpty() {
        val transport = ArchiveTransport()
        transport.baseline = baseline()
        val api = api(transport)

        api.ensureArchiveBaseline()
        api.ensureArchiveBaseline()

        assertEquals(1, transport.workspaceFollowOpens)
        assertEquals(emptySet<String>(), api.archivedSessionIds)
    }

    @Test
    fun ensureArchiveBaselineFetchesForEachServerOnce() {
        val transport = ArchiveTransport()
        var server = "http://first"
        val api = HarnessApi({ server }, transport = { transport })
        transport.baseline = baseline("archived")
        api.ensureArchiveBaseline()
        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })

        server = "http://second"
        transport.baseline = baseline("other")
        api.ensureArchiveBaseline()
        api.ensureArchiveBaseline()
        assertEquals(listOf("visible", "archived"), api.sessions().map { it.id })

        server = "http://first"
        api.ensureArchiveBaseline()
        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })
        assertEquals(2, transport.workspaceFollowOpens)
    }

    @Test
    fun failedArchiveBaselineRemainsRetryableAndListIsAvailable() {
        val transport = ArchiveTransport()
        val api = api(transport)
        transport.baseline = baseline().apply { getJSONObject("value").remove("archivedSessionIds") }

        assertThrows(org.json.JSONException::class.java) { api.ensureArchiveBaseline() }
        assertEquals(listOf("visible", "archived", "other"), api.sessions().map { it.id })
        transport.baseline = baseline("archived")
        api.ensureArchiveBaseline()

        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })
        assertEquals(2, transport.workspaceFollowOpens)
    }

    @Test
    fun workspaceBaselineAlreadyLoadedDoesNotNeedAnotherEnsureRequest() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived")
        val api = api(transport)
        api.workspaces()

        api.ensureArchiveBaseline()

        assertEquals(listOf("visible", "other"), api.sessions().map { it.id })
        assertEquals(1, transport.workspaceFollowOpens)
        assertEquals(listOf("session/list"), transport.requests.map { it.getString("method") })
    }

    @Test
    fun sessionSnapshotSplitsDirectoryWithOneRpcAndUpdatesMetadata() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived", "missing")
        val api = api(transport)
        api.workspaces()

        val snapshot = api.sessionSnapshot()

        assertEquals(listOf("visible", "other"), snapshot.active.map { it.id })
        assertEquals(listOf(HarnessApi.Session("archived", null, null, null, 2, false, false)), snapshot.archived)
        assertEquals(listOf("session/list"), transport.requests.map { it.getString("method") })
        assertTrue(api.isSessionAddressResolved("visible"))
        assertTrue(api.isSessionAddressResolved("archived"))
    }

    @Test
    fun sessionSnapshotKeepsAllActiveWhenArchiveSetIsEmpty() {
        val transport = ArchiveTransport()
        transport.baseline = baseline()
        val api = api(transport)
        api.workspaces()

        val snapshot = api.sessionSnapshot()

        assertEquals(listOf("visible", "archived", "other"), snapshot.active.map { it.id })
        assertEquals(emptyList<HarnessApi.Session>(), snapshot.archived)
    }

    @Test
    fun sessionSnapshotPreservesUnarchivedDirectoryOrder() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("archived")
        transport.sessionItems = JSONArray("""[
          {"sessionId":"later","updatedAt":1,"blank":false},
          {"sessionId":"archived","updatedAt":4,"blank":false},
          {"sessionId":"earlier","updatedAt":9,"blank":false},
          {"sessionId":"last","updatedAt":2,"blank":false}
        ]""")
        val api = api(transport)
        api.workspaces()

        assertEquals(listOf("later", "earlier", "last"), api.sessionSnapshot().active.map { it.id })
    }

    @Test
    fun sessionSnapshotKeepsServerArchiveSetsIsolated() {
        val transport = ArchiveTransport()
        var server = "http://first"
        val api = HarnessApi({ server }, transport = { transport })
        transport.baseline = baseline("archived")
        api.workspaces()
        assertEquals(listOf("archived"), api.sessionSnapshot().archived.map { it.id })

        server = "http://second"
        assertEquals(emptyList<HarnessApi.Session>(), api.sessionSnapshot().archived)
        transport.baseline = baseline("other")
        api.workspaces()
        val second = api.sessionSnapshot()
        assertEquals(listOf("visible", "archived"), second.active.map { it.id })
        assertEquals(listOf("other"), second.archived.map { it.id })

        server = "http://first"
        val first = api.sessionSnapshot()
        assertEquals(listOf("visible", "other"), first.active.map { it.id })
        assertEquals(listOf("archived"), first.archived.map { it.id })
    }

    @Test
    fun archivedSessionsIsEmptyWhenArchiveSetIsEmpty() {
        val transport = ArchiveTransport()
        transport.baseline = baseline()
        val api = api(transport)
        api.workspaces()

        assertEquals(emptyList<HarnessApi.Session>(), api.archivedSessions())
    }

    @Test
    fun archivedSessionsIntersectsDirectoryAndPreservesSummaryOrder() {
        val transport = ArchiveTransport()
        transport.baseline = baseline("other", "archived", "missing")
        val api = api(transport)
        api.workspaces()

        assertEquals(
            listOf(
                HarnessApi.Session("archived", null, null, null, 2, false, false),
                HarnessApi.Session("other", null, null, null, 1, false, false),
            ),
            api.archivedSessions(),
        )
    }

    @Test
    fun archivedSessionsKeepsServerArchiveSetsIsolated() {
        val transport = ArchiveTransport()
        var server = "http://first"
        val api = HarnessApi({ server }, transport = { transport })
        transport.baseline = baseline("archived")
        api.workspaces()
        assertEquals(listOf("archived"), api.archivedSessions().map { it.id })

        server = "http://second"
        assertEquals(emptyList<HarnessApi.Session>(), api.archivedSessions())
        transport.baseline = baseline("other")
        api.workspaces()
        assertEquals(listOf("other"), api.archivedSessions().map { it.id })

        server = "http://first"
        assertEquals(listOf("archived"), api.archivedSessions().map { it.id })
    }

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
        var workspaceFollowOpens = 0
        var baseline = JSONObject()
        var mutationValue = JSONObject()
        var mutationError: JSONObject? = null
        var beforeMutationReply: (() -> Unit)? = null
        val requests = mutableListOf<JSONObject>()
        var sessionItems = JSONArray("""[
          {"sessionId":"visible","updatedAt":3,"blank":false},
          {"sessionId":"archived","updatedAt":2,"blank":false},
          {"sessionId":"other","updatedAt":1,"blank":false}
        ]""")

        override fun rpc(envelope: JSONObject): JSONObject {
            requests += envelope
            val result = if (envelope.getString("method") == "session/list") {
                JSONObject().put("ok", true).put("value", JSONObject().put("items", sessionItems))
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
                        workspaceFollowOpens += 1
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
