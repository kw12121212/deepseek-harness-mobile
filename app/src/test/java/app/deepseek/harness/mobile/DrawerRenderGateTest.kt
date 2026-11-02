package app.deepseek.harness.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerRenderGateTest {
    @Test
    fun liveRefreshesCannotReplaceRowsDuringLongPress() {
        val gate = DrawerRenderGate()
        gate.beginTouch()
        // Refreshes at 250, 500 and 750ms must leave the original touch target attached.
        repeat(3) { assertFalse(gate.requestRender()) }
        assertTrue(gate.endTouch())
        assertTrue(gate.requestRender())
        assertFalse(gate.endTouch())
    }

    @Test
    fun releaseWithoutRefreshDoesNotRequestUnnecessaryRebuild() {
        val gate = DrawerRenderGate()
        assertTrue(gate.requestRender())
        gate.beginTouch()
        assertFalse(gate.endTouch())
    }

    @Test
    fun cancelledGestureAllowsTheNextLongPressToDeferRefreshAgain() {
        val gate = DrawerRenderGate()
        gate.beginTouch()
        assertFalse(gate.requestRender())
        assertTrue(gate.endTouch())
        gate.beginTouch()
        assertFalse(gate.requestRender())
        assertTrue(gate.endTouch())
    }
}
