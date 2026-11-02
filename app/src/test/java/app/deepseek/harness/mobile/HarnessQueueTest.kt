package app.deepseek.harness.mobile

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HarnessQueueTest {
    @Test fun parsesOccurrenceIdsTextBlocksAndInboxTargets() {
        val inbox = JSONObject("""{"next-turn":[
          {"id":"occurrence-a","source":{"rpcId":"different-id"},"content":[{"type":"text","text":"你好"},{"type":"text","text":"\n world "}]},
          {"id":"attachment","content":[{"type":"text","text":"caption"},{"type":"image"}]}
        ],"next-step":[{"id":"occurrence-b","content":[{"type":"text","text":"now"}]}]}""")

        assertEquals(listOf(
            HarnessApi.QueuedPrompt("occurrence-a", "你好\n world ", "next-turn"),
            HarnessApi.QueuedPrompt("attachment", null, "next-turn"),
            HarnessApi.QueuedPrompt("occurrence-b", "now", "next-step"),
        ), parseQueuedPrompts(inbox))
    }

    @Test fun emptyAndMissingInboxFieldsDoNotInventItems() {
        assertEquals(emptyList<HarnessApi.QueuedPrompt>(), parseQueuedPrompts(null))
        assertEquals(emptyList<HarnessApi.QueuedPrompt>(), parseQueuedPrompts(JSONObject()))
        assertEquals(emptyList<HarnessApi.QueuedPrompt>(), parseQueuedPrompts(JSONObject("""{"next-turn":[],"next-step":[]}""")))
        assertEquals(emptyList<HarnessApi.QueuedPrompt>(), parseQueuedPrompts(JSONObject("""{"next-turn":[
          null,{}, {"id":"","content":[]}, {"id":42,"content":[]}, {"id":"missing-content"}
        ]}""")))
        assertEquals(listOf(HarnessApi.QueuedPrompt("bad-text", null, "next-step")),
            parseQueuedPrompts(JSONObject("""{"next-step":[{"id":"bad-text","content":[{"type":"text"}]}]}""")))
    }

    @Test fun editRemoveAndSteerUseNamedUpdateQueueRequest() {
        val transport = QueueTransport()
        val api = HarnessApi({ "http://server-a" }, transport = { transport })
        val actions = listOf(SessionQueueAction.Edit(" 保留\nwhitespace "), SessionQueueAction.Remove, SessionQueueAction.Steer)
        val expectedActions = listOf(
            JSONObject("""{"kind":"edit","content":[{"type":"text","text":" 保留\nwhitespace "}]}"""),
            JSONObject("""{"kind":"remove"}"""), JSONObject("""{"kind":"steer"}"""),
        )
        actions.forEach { api.updateQueue("session-a", "occurrence-a", it) }

        assertEquals(3, transport.requests.size)
        transport.requests.zip(expectedActions).forEach { (envelope, expectedAction) ->
            assertEquals("session/updateQueue", envelope.getString("method"))
            val args = envelope.getJSONObject("payload").getJSONObject("args")
            assertEquals(setOf("request"), args.keys().asSequence().toSet())
            val request = args.getJSONObject("request")
            assertEquals(setOf("sessionId", "itemId", "action"), request.keys().asSequence().toSet())
            assertEquals("session-a", request.getString("sessionId"))
            assertEquals("occurrence-a", request.getString("itemId"))
            val action = request.getJSONObject("action")
            assertEquals(expectedAction.keys().asSequence().toSet(), action.keys().asSequence().toSet())
            assertEquals(expectedAction.getString("kind"), action.getString("kind"))
            if (action.getString("kind") == "edit") {
                val content = action.getJSONArray("content")
                assertEquals(1, content.length())
                assertEquals(setOf("type", "text"), content.getJSONObject(0).keys().asSequence().toSet())
                assertEquals("text", content.getJSONObject(0).getString("type"))
                assertEquals(" 保留\nwhitespace ", content.getJSONObject(0).getString("text"))
            }
        }
    }

    @Test fun editRejectsEmptyAndWhitespaceBeforeSending() {
        val transport = QueueTransport()
        val api = HarnessApi({ "http://server-a" }, transport = { transport })
        for (text in listOf("", " \t\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                api.updateQueue("session-a", "occurrence-a", SessionQueueAction.Edit(text))
            }
        }
        assertEquals(0, transport.requests.size)
    }

    @Test fun followAndControlKeepQueuesIsolatedAndClearOnServerSwitch() {
        val transport = QueueTransport()
        var server = "http://server-a"
        val api = HarnessApi({ server }, transport = { transport })
        transport.followInbox = inbox("from-follow")
        assertEquals("from-follow", api.history("session-a").queuedPrompts.single().id)
        transport.controlInbox = inbox("from-baseline")
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { api.streamMux {} } catch (error: Throwable) { failure.set(error) } }
        try {
            worker.start()
            assertTrue(transport.ready.await(3, TimeUnit.SECONDS))
            assertEquals("from-baseline", api.history("session-a").queuedPrompts.single().id)
            transport.publishInbox("session-b", inbox("other-session"), 2)
            assertEquals("from-baseline", api.history("session-a").queuedPrompts.single().id)
            transport.publishInbox("session-a", inbox("edited"), 3)
            transport.publishInbox("session-a", inbox("stale"), 2)
            assertEquals(listOf(HarnessApi.QueuedPrompt("edited", "body", "next-turn")), api.history("session-a").queuedPrompts)
            transport.publishInbox("session-a", JSONObject(), 4)
            assertTrue(api.history("session-a").queuedPrompts.isEmpty())
            transport.publishInbox("session-a", inbox("old-server"), 5)
            assertEquals("old-server", api.history("session-a").queuedPrompts.single().id)
        } finally {
            api.closeMux()
            worker.join(3_000)
        }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        server = "http://server-b"
        transport.followInbox = JSONObject()
        transport.publishInbox("session-a", inbox("late-old-server"), 6)
        assertTrue(api.history("session-a").queuedPrompts.isEmpty())
        assertTrue(api.history("session-b").queuedPrompts.isEmpty())
    }

    private fun inbox(id: String) = JSONObject().put("next-turn", JSONArray().put(JSONObject()
        .put("id", id).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "body")))))

    private class QueueTransport : HarnessTransport {
        val requests = mutableListOf<JSONObject>()
        val ready = CountDownLatch(1)
        var followInbox = JSONObject()
        var controlInbox = JSONObject()
        private lateinit var publishControl: (JSONObject) -> Unit

        override fun rpc(envelope: JSONObject): JSONObject {
            requests += envelope
            val value = if (envelope.getString("method") == "session/list") JSONObject().put("items", JSONArray("""[
              {"sessionId":"session-a"},{"sessionId":"session-b"}
            ]""")) else JSONObject().put("accepted", true)
            return JSONObject().put("type", "server-response").put("rpcId", envelope.getString("rpcId"))
                .put("result", JSONObject().put("ok", true).put("value", value))
        }

        fun publishInbox(sessionId: String, inbox: JSONObject, seq: Int) = publishControl(JSONObject()
            .put("type", "projection").put("sessionId", sessionId).put("key", "inbox").put("seq", seq).put("value", inbox))

        override fun openMux(listener: WebSocketListener): WebSocket {
            val socket = object : WebSocket {
                override fun request(): Request = Request.Builder().url("http://localhost/api/remote.mux").build()
                override fun queueSize(): Long = 0
                override fun send(bytes: okio.ByteString): Boolean = error("Unexpected binary frame")
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text)
                    if (frame.getString("type") != "open") return true
                    val publish: (JSONObject) -> Unit = { listener.onMessage(this, JSONObject().put("type", "item")
                        .put("streamId", frame.getString("streamId")).put("value", it).toString()) }
                    when (frame.getString("endpoint")) {
                        "session/follow" -> publish(JSONObject("""{"type":"snapshot","cursor":0,"records":[],"projections":{"asOfSeq":0,"values":{}}}""")
                            .apply { getJSONObject("projections").getJSONObject("values").put("inbox", followInbox) })
                        "session/control" -> {
                            publishControl = publish
                            publish(JSONObject("""{"type":"baseline","value":{"projections":{"session-a":{"asOfSeq":1,"values":{}}}}}""")
                                .apply { getJSONObject("value").getJSONObject("projections").getJSONObject("session-a")
                                    .getJSONObject("values").put("inbox", controlInbox) })
                        }
                        "\$events" -> ready.countDown()
                        else -> error("Unexpected stream")
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
