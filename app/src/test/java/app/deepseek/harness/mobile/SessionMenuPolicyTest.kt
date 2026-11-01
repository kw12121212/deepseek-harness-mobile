package app.deepseek.harness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMenuPolicyTest {
    @Test
    fun unresolvedSessionCanShowMenuAfterAddressResolution() {
        assertEquals(SessionMenuDecision.RESOLVE, sessionMenuDecision(null, null))
        assertEquals(SessionMenuDecision.SHOW, sessionMenuDecision(null, "session"))
    }

    @Test
    fun resolvedSessionShowsMenuImmediately() {
        assertEquals(SessionMenuDecision.SHOW, sessionMenuDecision("user", "session"))
    }

    @Test
    fun knownSubagentDoesNotResolveOrShowMenu() {
        assertEquals(SessionMenuDecision.HIDE, sessionMenuDecision("subagent", null))
        assertEquals(SessionMenuDecision.HIDE, sessionMenuDecision("subagent", "session"))
    }

    @Test
    fun subagentDiscoveredDuringResolutionDoesNotShowMenu() {
        assertEquals(SessionMenuDecision.HIDE, sessionMenuDecision(null, "subagent"))
    }
}
