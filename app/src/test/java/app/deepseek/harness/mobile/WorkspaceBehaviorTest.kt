package app.deepseek.harness.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBehaviorTest {
    private val root = HarnessApi.Workspace("root-id", "root", "/root", emptyList())
    private val project = HarnessApi.Workspace("project-id", "project", "/root/project/", listOf("listed"))

    @Test
    fun `current workspace is resolved by session membership first`() {
        val session = HarnessApi.Session("listed", null, "/somewhere/else", null, 0, false, false)

        assertEquals(project, WorkspaceBehavior.matchingWorkspace(session, listOf(root, project)))
    }

    @Test
    fun `current workspace is resolved by normalized cwd`() {
        val session = HarnessApi.Session("session", null, "/root/project", null, 0, false, false)

        assertEquals(project, WorkspaceBehavior.matchingWorkspace(session, listOf(root, project)))
        assertTrue(WorkspaceBehavior.samePath("/root/project/", "/root/project"))
    }

    @Test
    fun `ungrouped session creates without a workspace id`() {
        val session = HarnessApi.Session("session", null, "/opt/unregistered", null, 0, false, false)

        assertNull(WorkspaceBehavior.matchingWorkspace(session, listOf(root, project)))
    }

    @Test
    fun lastUpdatedFallsBackToDescendingHostTime() {
        val olderReply = session("older-reply", 100)
        val newerReply = session("newer-reply", 300)
        val middleReply = session("middle-reply", 200)
        val sessions = listOf(olderReply, newerReply, middleReply)

        assertEquals(listOf(newerReply, middleReply, olderReply), sessionOrder(sessions, lastUpdated = true))
        assertEquals(listOf(olderReply, newerReply, middleReply), sessions)
    }

    @Test
    fun userMessageWithoutAssistantReplyStillUsesHostTimeFallback() {
        val replied = session("replied", 100)
        // SessionSummary cannot distinguish a pending user prompt from a newer assistant reply.
        val userOnly = session("user-only", 200).copy(running = true)

        assertEquals(listOf(userOnly, replied), sessionOrder(listOf(replied, userOnly), lastUpdated = true))
    }

    @Test
    fun manualOrderPreservesInputDespiteNewerHostTime() {
        val first = session("first", 100)
        val second = session("second", 300)
        val third = session("third", 200)

        assertEquals(listOf(first, second, third), sessionOrder(listOf(first, second, third), lastUpdated = false))
    }

    @Test
    fun equalHostTimesPreserveInputOrder() {
        val first = session("first", 100)
        val second = session("second", 100)

        assertEquals(listOf(first, second), sessionOrder(listOf(first, second), lastUpdated = true))
    }

    @Test
    fun emptySessionOrderRemainsEmpty() {
        assertEquals(emptyList<HarnessApi.Session>(), sessionOrder(emptyList(), lastUpdated = true))
        assertEquals(emptyList<HarnessApi.Session>(), sessionOrder(emptyList(), lastUpdated = false))
    }

    @Test
    fun singleSessionOrderRemainsUnchanged() {
        val only = session("only", 100)

        assertEquals(listOf(only), sessionOrder(listOf(only), lastUpdated = true))
        assertEquals(listOf(only), sessionOrder(listOf(only), lastUpdated = false))
    }

    private fun session(id: String, updatedAt: Long) =
        HarnessApi.Session(id, null, null, null, updatedAt, false, false)
}
