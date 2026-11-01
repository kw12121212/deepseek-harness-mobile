package app.deepseek.harness.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionArchiveSelectionTest {
    @Test
    fun selectsNextAvailableSessionInListOrder() {
        val sessions = listOf(session("before"), session("current"), session("next"), session("last"))

        assertEquals("next", nextSessionAfterArchive(sessions, "current", setOf("current"))?.id)
    }

    @Test
    fun skipsBlankAndOtherArchivedSessions() {
        val sessions = listOf(session("current"), session("blank", blank = true), session("archived"), session("next"))

        assertEquals("next", nextSessionAfterArchive(sessions, "current", setOf("current", "archived"))?.id)
    }

    @Test
    fun wrapsToFirstAvailableSessionAtEndOfList() {
        val sessions = listOf(session("first"), session("middle"), session("current"))

        assertEquals("first", nextSessionAfterArchive(sessions, "current", setOf("current"))?.id)
    }

    @Test
    fun returnsEmptyStateWhenNoAvailableSessionsRemain() {
        val sessions = listOf(session("current"), session("blank", blank = true), session("archived"))

        assertNull(nextSessionAfterArchive(sessions, "current", setOf("current", "archived")))
    }

    @Test
    fun excludesTargetEvenWhenArchiveSetDoesNotContainIt() {
        assertNull(nextSessionAfterArchive(listOf(session("current")), "current", emptySet()))
    }

    @Test
    fun choosesFirstAvailableWhenTargetIsMissingFromList() {
        assertEquals("first", nextSessionAfterArchive(listOf(session("first"), session("second")), "gone", setOf("gone"))?.id)
    }

    @Test
    fun returnsEmptyStateForEmptyList() {
        assertNull(nextSessionAfterArchive(emptyList(), "gone", setOf("gone")))
    }

    private fun session(id: String, blank: Boolean = false) =
        HarnessApi.Session(id, id, null, null, 0, false, blank)
}
