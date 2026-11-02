package app.deepseek.harness.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttachmentsStateTest {
    private val state = AttachmentsState()
    private val bytes = byteArrayOf(1, 2)

    @Test fun addingImagesAndFilesAndRemovingUpdatesDraft() {
        val image = state.addImage("a.png", "image/png", bytes)
        val file = state.addFile("b.txt", bytes)
        assertEquals(listOf(image, file), state.items.map { it.id })
        state.remove(image)
        assertEquals(listOf(file), state.items.map { it.id })
    }

    @Test fun uploadMustFinishAndFailureCanBeRetried() {
        val file = state.addFile("a", bytes)
        assertFalse(state.canSend("text"))
        state.fail(file)
        assertFalse(state.canSend("text"))
        assertTrue(state.retry(file))
        assertEquals(AttachmentsState.Status.UPLOADING, state.items.single().status)
        state.complete(file, "receipt")
        assertTrue(state.canSend(""))
    }

    @Test fun rejectsImageAndFileLimits() {
        repeat(20) { state.addImage("a", "image/png", bytes) }
        assertThrows(IllegalArgumentException::class.java) { state.addImage("a", "image/png", bytes) }
        state.clear()
        assertThrows(IllegalArgumentException::class.java) { state.addImage("a", "image/png", bytes, 50L * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { state.addFile("a", bytes, 32L * 1024 * 1024 + 1) }
        repeat(4) { state.addImage("a", "image/png", bytes, 50L * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) { state.addImage("a", "image/png", bytes, 1) }
    }

    @Test fun pendingReadsBlockSendAndRemovedUploadsCannotReturn() {
        state.beginRead()
        assertFalse(state.canSend("text"))
        state.endRead()
        assertTrue(state.canSend("text"))
        val id = state.addFile("a", bytes)
        state.remove(id)
        state.complete(id, "late")
        assertTrue(state.items.isEmpty())
        assertFalse(state.retry(id))
    }

    @Test fun contentPreservesTextThenAttachmentOrder() {
        state.addImage("a", "image/png", bytes)
        val file = state.addFile("b", bytes)
        state.complete(file, "r1")
        val result = state.contentBlocks("hello") { JSONObject().put("type", "image") }
        assertEquals(listOf("text", "image", "file"), (0 until result.length()).map { result.getJSONObject(it).getString("type") })
        assertEquals("r1", result.getJSONObject(2).getString("receiptId"))
        assertEquals(2, state.contentBlocks("") { JSONObject().put("type", "image") }.length())
    }
}
