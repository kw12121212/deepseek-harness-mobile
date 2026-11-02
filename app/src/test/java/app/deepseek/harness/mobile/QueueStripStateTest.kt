package app.deepseek.harness.mobile

import org.junit.Assert.*
import org.junit.Test

class QueueStripStateTest {
    private val textRow = HarnessApi.QueuedPrompt("text", "Original body", "next-turn")
    private val attachmentRow = HarnessApi.QueuedPrompt("attachment", null, "next-step")
    private val chinese: (String, String) -> String = { zh, _ -> zh }
    private val english: (String, String) -> String = { _, en -> en }

    @Test fun emptyQueueIsHiddenWithoutAnOperation() {
        assertFalse(queueStripVisible(emptyList(), QueueStripState()))
    }

    @Test fun singleAndMultipleRowsAreVisibleButInitiallyCollapsed() {
        for (rows in listOf(listOf(textRow), listOf(textRow, attachmentRow))) {
            val state = QueueStripState().reconcile(rows)
            assertTrue(queueStripVisible(rows, state))
            assertFalse(state.expanded)
        }
    }

    @Test fun headerTogglesBetweenExpandedAndCollapsed() {
        val expanded = QueueStripState().toggleExpanded()
        assertTrue(expanded.expanded)
        assertFalse(expanded.toggleExpanded().expanded)
    }

    @Test fun busyHeaderKeepsItsExpansionState() {
        assertTrue(QueueStripState(expanded = true, busyId = "text").toggleExpanded().expanded)
        assertFalse(QueueStripState(busyId = "text").toggleExpanded().expanded)
    }

    @Test fun refreshedRowsPreserveExpansionAndTheExistingEditor() {
        val state = QueueStripState(expanded = true, editingId = "text")
            .reconcile(listOf(attachmentRow, textRow.copy(text = "Updated by another client")))
        assertTrue(state.expanded)
        assertEquals("text", state.editingId)
    }

    @Test fun emptyProjectionCollapsesAndExitsEditing() {
        val state = QueueStripState(expanded = true, editingId = "text").reconcile(emptyList())
        assertFalse(state.expanded)
        assertNull(state.editingId)
        assertFalse(queueStripVisible(emptyList(), state))
    }

    @Test fun newlyArrivingQueueStaysCollapsedAfterThePreviousQueueEmptied() {
        val empty = QueueStripState(expanded = true).reconcile(emptyList())
        val refilled = empty.reconcile(listOf(textRow))
        assertFalse(refilled.expanded)
        assertTrue(queueStripVisible(listOf(textRow), refilled))
    }

    @Test fun removingTheEditedRowExitsEditingEvenWhenOtherRowsRemain() {
        val state = QueueStripState(expanded = true, editingId = "text").reconcile(listOf(attachmentRow))
        assertNull(state.editingId)
        assertTrue(state.expanded)
    }

    @Test fun attachmentProjectionForTheEditedIdExitsEditing() {
        val state = QueueStripState(editingId = "text").reconcile(listOf(textRow.copy(text = null)))
        assertNull(state.editingId)
    }

    @Test fun operationSurvivesRowDisappearanceUntilItsCallbackSettles() {
        val state = QueueStripState(expanded = true, busyId = "text", editingId = "text")
            .reconcile(emptyList())
        assertEquals("text", state.busyId)
        assertNull(state.editingId)
        assertFalse(state.expanded)
        assertTrue(queueStripVisible(emptyList(), state))
        assertFalse(queueStripVisible(emptyList(), state.copy(busyId = null)))
    }

    @Test fun targetLabelsSupportBothLanguages() {
        assertEquals("下一步", queueTargetLabel("next-step", chinese))
        assertEquals("下一轮", queueTargetLabel("next-turn", chinese))
        assertEquals("Next step", queueTargetLabel("next-step", english))
        assertEquals("Next turn", queueTargetLabel("next-turn", english))
    }

    @Test fun previewCollapsesNewlinesTabsAndSurroundingWhitespace() {
        assertEquals("你好 queue preview", queuePreview(" 你好\n\tqueue\r\npreview  ", chinese))
    }

    @Test fun nullTextDisplaysTheNonTextMarkerInBothLanguages() {
        assertEquals("包含非文本内容", queuePreview(null, chinese))
        assertEquals("Contains non-text content", queuePreview(null, english))
    }

    @Test fun emptyTextIsNotMislabelledAsAnAttachment() {
        assertEquals("", queuePreview("", chinese))
        assertEquals("", queuePreview(" \n ", english))
    }

    @Test fun runningTextRowAllowsEditSteerAndRemove() {
        assertEquals(QueueActionAvailability(edit = true, steer = true, remove = true),
            queueActionAvailability(textRow, QueueStripState(), running = true))
    }

    @Test fun stoppedSessionDisablesOnlySteer() {
        assertEquals(QueueActionAvailability(edit = true, steer = false, remove = true),
            queueActionAvailability(textRow, QueueStripState(), running = false))
    }

    @Test fun nonTextRowDisablesOnlyEditWhileRunning() {
        assertEquals(QueueActionAvailability(edit = false, steer = true, remove = true),
            queueActionAvailability(attachmentRow, QueueStripState(), running = true))
    }

    @Test fun nonTextRowInStoppedSessionAllowsOnlyRemoval() {
        assertEquals(QueueActionAvailability(edit = false, steer = false, remove = true),
            queueActionAvailability(attachmentRow, QueueStripState(), running = false))
    }

    @Test fun anyInFlightOperationDisablesActionsForEveryRow() {
        for (row in listOf(textRow, attachmentRow)) {
            assertEquals(QueueActionAvailability(edit = false, steer = false, remove = false),
                queueActionAvailability(row, QueueStripState(busyId = "another-row"), running = true))
        }
    }

    @Test fun editSaveRejectsEmptyAndWhitespaceOnlyDrafts() {
        assertFalse(queueEditSaveAllowed("", QueueStripState()))
        assertFalse(queueEditSaveAllowed(" \t\n", QueueStripState()))
        assertTrue(queueEditSaveAllowed(" keep this draft \n", QueueStripState()))
    }

    @Test fun editSaveWaitsForTheCurrentOperationToSettle() {
        assertFalse(queueEditSaveAllowed("retry draft", QueueStripState(busyId = "text")))
        assertTrue(queueEditSaveAllowed("retry draft", QueueStripState()))
    }
}
