package app.deepseek.harness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActionTest {
    @Test fun runningWithoutDraftStops() {
        assertEquals(ComposerAction.STOP, composerAction(running = true, hasDraft = false))
    }

    @Test fun runningWithDraftSends() {
        assertEquals(ComposerAction.SEND, composerAction(running = true, hasDraft = true))
    }

    @Test fun idleWithoutDraftSelectsSend() {
        assertEquals(ComposerAction.SEND, composerAction(running = false, hasDraft = false))
    }

    @Test fun idleWithDraftSends() {
        assertEquals(ComposerAction.SEND, composerAction(running = false, hasDraft = true))
    }
}
