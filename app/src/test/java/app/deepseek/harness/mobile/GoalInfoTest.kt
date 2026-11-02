package app.deepseek.harness.mobile

import java.io.IOException
import java.io.OutputStream
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoalInfoTest {
    private val full = """{"id":"goal-1","revision":3,"phase":"active","objective":"Ship it","maxGoalRounds":10,"roundsStarted":2,"activation":"armed"}"""

    @Test fun parsesCompleteHostView() {
        assertEquals(GoalInfo("goal-1", 3, "active", "Ship it", 10, 2, "armed"), parseGoal(JSONObject(full)))
    }

    @Test fun missingRequiredFieldIsRejected() {
        val value = JSONObject(full).apply { remove("objective") }
        assertThrows(Exception::class.java) { parseGoal(value) }
    }

    @Test fun undefinedAndNullAreEmpty() {
        assertNull(parseGoal(null))
        assertNull(parseGoal(JSONObject.NULL))
    }

    @Test fun apiRequestsCorrectEndpointAndReturnsNullForUndefined() {
        val transport = object : HarnessTransport {
            override fun rpc(envelope: JSONObject): JSONObject {
                assertEquals("goals/get", envelope.getString("method"))
                val args = envelope.getJSONObject("payload").getJSONObject("args")
                assertEquals(setOf("agentId"), args.keys().asSequence().toSet())
                assertEquals("session-a", args.getString("agentId"))
                return JSONObject().put("type", "server-response").put("rpcId", envelope.getString("rpcId"))
                    .put("result", JSONObject().put("ok", true))
            }
            override fun openMux(listener: WebSocketListener): WebSocket = error("Unexpected stream")
            override fun export(sessionId: String, includeDescendants: Boolean, sink: OutputStream) = error("Unexpected export")
            override fun streamFailure(response: Response?, cause: Throwable): IOException = error("Unexpected failure")
        }
        assertNull(HarnessApi({ "server" }, transport = { transport }).goal("session-a"))
    }

    @Test fun selectingAnotherSessionClearsAndRejectsStaleResponse() {
        val state = GoalState()
        val old = state.select("a")
        state.accept("a", old, parseGoal(JSONObject(full)))
        assertNotNull(state.goal)
        state.select("b")
        assertNull(state.goal)
        state.accept("a", old, parseGoal(JSONObject(full)))
        assertNull(state.goal)
    }
}
