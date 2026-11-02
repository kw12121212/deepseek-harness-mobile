package app.deepseek.harness.mobile

import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HarnessAttachmentTest {
    @Test fun uploadUsesAgentScopeCanonicalBase64AndReceipt() {
        val transport = AttachmentTransport()
        val api = api(transport)
        // Empty files and all three base64 padding partitions are valid.
        val cases = listOf(byteArrayOf() to "", byteArrayOf(1) to "AQ==",
            byteArrayOf(0, -1) to "AP8=", byteArrayOf(0, -1, -128) to "AP+A")
        for ((bytes, encoded) in cases) {
            val before = bytes.copyOf()
            val receipt = api.uploadFile("session-a", bytes, "资料 & notes.bin")
            assertEquals("fileUploads/upload", transport.requests.last().getString("method"))
            assertJson("""{"agentId":"session-a","request":{"data":"$encoded","name":"资料 & notes.bin"}}""", transport.args())
            assertEquals("receipt-a", receipt.receiptId)
            assertJson(FILE_REF, receipt.file)
            assertJson("""{"type":"file","receiptId":"receipt-a"}""", receipt.promptContent())
            assertArrayEquals(before, bytes)
        }
        val binary = ByteArray(256) { it.toByte() }
        api.uploadFile("session-b", binary)
        assertEquals(setOf("data"), transport.args().getJSONObject("request").keys().asSequence().toSet())
        assertEquals("session-b", transport.args().getString("agentId"))
        val encoded = transport.args().getJSONObject("request").getString("data")
        assertFalse(encoded.contains('\n'))
        assertArrayEquals(binary, Base64.getDecoder().decode(encoded))
    }

    @Test fun promptPreservesOrderedTextImageAndFileBlocks() {
        val transport = AttachmentTransport()
        val api = api(transport)
        val receipt = api.uploadFile("session-a", byteArrayOf(7))
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "caption"))
            .put(api.imageContent(byteArrayOf(0, -1, -128, 10), "image/png", "照片.png"))
            .put(receipt.promptContent())
        api.prompt("session-a", content, "steer", "request-a")
        assertEquals("session/prompt", transport.requests.last().getString("method"))
        val expected = JSONObject("""{"request":{"sessionId":"session-a","requestId":"request-a","mode":"steer",
          "content":[{"type":"text","text":"caption"},
          {"type":"image","mediaType":"image/png","data":"AP+ACg==","name":"照片.png"},
          {"type":"file","receiptId":"receipt-a"}]}}""")
        expected.getJSONObject("request").put("clientTimeZone", java.util.TimeZone.getDefault().id)
        assertEquals(expected.toString(), transport.args().toString())
        for (mediaType in listOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
            assertJson("""{"type":"image","mediaType":"$mediaType","data":"AQ=="}""",
                api.imageContent(byteArrayOf(1), mediaType))
        }
    }

    @Test fun imageRejectsEmptyBytesAndUnsupportedTypeBeforeRpc() {
        val transport = AttachmentTransport()
        val api = api(transport)
        assertThrows(IllegalArgumentException::class.java) { api.imageContent(byteArrayOf(), "image/png") }
        assertThrows(IllegalArgumentException::class.java) { api.imageContent(byteArrayOf(1), "image/svg+xml") }
        assertEquals(0, transport.requests.size)
    }

    @Test fun readSendsCompleteReferenceAndDecodesReturnedBytes() {
        val transport = AttachmentTransport()
        val reference = JSONObject(IMAGE_REF)
        val before = reference.toString()
        val read = api(transport).readAttachment("session-a", reference)
        assertEquals("session/attachment", transport.requests.single().getString("method"))
        assertJson("""{"request":{"sessionId":"session-a","attachment":$IMAGE_REF}}""", transport.args())
        assertJson(IMAGE_REF, read.attachment)
        assertArrayEquals(byteArrayOf(0, -1, -128), read.bytes)
        assertEquals(before, reference.toString())
    }

    @Test fun readFiltersUnexpectedFieldsAndFallsBackForOlderGateway() {
        val transport = AttachmentTransport().apply { legacy = true }
        val reference = JSONObject(IMAGE_REF).put("type", "image").put("offloaded", true)
        reference.getJSONObject("originalDimensions").put("unexpected", 1)
        assertArrayEquals(byteArrayOf(0, -1, -128), api(transport).readAttachment("session-a", reference).bytes)
        assertEquals(2, transport.requests.size)
        val modern = transport.requests[0].getJSONObject("payload").getJSONObject("args")
        assertJson("""{"request":{"sessionId":"session-a","attachment":$IMAGE_REF}}""", modern)
        assertJson("""{"request":{"sessionId":"session-a","attachmentId":"sha256:image"}}""", transport.args())
    }

    @Test fun readRejectsMalformedOrNonCanonicalBase64() {
        val transport = AttachmentTransport()
        val api = api(transport)
        for (encoded in listOf("A", "AA", "AB==", "AA==\n", "@@==")) {
            transport.readEncoding = encoded
            val failure = assertThrows(IOException::class.java) { api.readAttachment("session-a", JSONObject(IMAGE_REF)) }
            assertEquals("Invalid attachment base64", failure.message)
        }
    }

    @Test fun failedUploadReadAndPromptPreserveCallerStateForRetry() {
        val transport = AttachmentTransport()
        val api = api(transport)
        val bytes = byteArrayOf(0, -1, -128)
        val receipt = api.uploadFile("session-a", bytes)
        val reference = JSONObject(IMAGE_REF)
        val content = JSONArray().put(api.imageContent(bytes, "image/png")).put(receipt.promptContent())
        val contentBefore = content.toString()
        val referenceBefore = reference.toString()
        for (networkFailure in listOf(false, true)) {
            transport.reject = true
            transport.networkFailure = networkFailure
            val operations = listOf<() -> Unit>(
                { api.uploadFile("session-a", bytes) },
                { api.readAttachment("session-a", reference) },
                { api.prompt("session-a", content, requestId = "retry-a") },
            )
            operations.forEach { operation ->
                val failure = assertThrows(IOException::class.java) { operation() }
                if (networkFailure) assertEquals("offline", failure.message)
                else {
                    assertEquals("session/attachment-invalid", (failure as HarnessApi.RemoteFailure).code)
                    assertEquals("attachment rejected", failure.message)
                }
            }
            assertArrayEquals(byteArrayOf(0, -1, -128), bytes)
            assertEquals(contentBefore, content.toString())
            assertEquals(referenceBefore, reference.toString())
            assertEquals("receipt-a", receipt.receiptId)
            transport.reject = false
            assertEquals("receipt-a", api.uploadFile("session-a", bytes).receiptId)
            assertArrayEquals(bytes, api.readAttachment("session-a", reference).bytes)
            api.prompt("session-a", content, requestId = "retry-a")
            assertEquals(contentBefore, transport.args().getJSONObject("request").getJSONArray("content").toString())
            assertEquals("retry-a", transport.args().getJSONObject("request").getString("requestId"))
        }
    }

    private fun api(transport: AttachmentTransport) = HarnessApi({ "http://server" }, transport = { transport })
    private fun assertJson(expected: String, actual: JSONObject) = assertEquals(JSONObject(expected).toString(), actual.toString())

    private class AttachmentTransport : HarnessTransport {
        val requests = mutableListOf<JSONObject>()
        var legacy = false
        var reject = false
        var networkFailure = false
        var readEncoding = "AP+A"
        fun args(): JSONObject = requests.last().getJSONObject("payload").getJSONObject("args")
        override fun rpc(envelope: JSONObject): JSONObject {
            requests += JSONObject(envelope.toString())
            if (legacy && envelope.getString("method") == "session/attachment" && args().getJSONObject("request").has("attachment")) {
                return JSONObject().put("type", "server-response").put("rpcId", envelope.getString("rpcId"))
                    .put("result", JSONObject("""{"ok":false,"error":{"code":"gateway/input-invalid","message":"invalid"}}"""))
            }
            if (reject && networkFailure) throw IOException("offline")
            val result = if (reject) JSONObject("""{"ok":false,"error":{"code":"session/attachment-invalid",
              "message":"attachment rejected","details":{"reason":"FILE_NOT_STAGED"}}}""") else {
                val response = when (envelope.getString("method")) {
                    "fileUploads/upload" -> JSONObject().put("receiptId", "receipt-a").put("file", JSONObject(FILE_REF))
                    "session/attachment" -> JSONObject().put("attachment", JSONObject(IMAGE_REF)).put("data", readEncoding)
                    "session/prompt" -> JSONObject().put("accepted", true)
                    else -> error("Unexpected RPC")
                }
                JSONObject().put("ok", true).put("value", response)
            }
            return JSONObject().put("type", "server-response").put("rpcId", envelope.getString("rpcId")).put("result", result)
        }
        override fun openMux(listener: WebSocketListener): WebSocket = error("Unexpected mux")
        override fun export(sessionId: String, includeDescendants: Boolean, sink: OutputStream) = error("Unexpected export")
        override fun streamFailure(response: Response?, cause: Throwable): IOException = IOException(cause)
    }

    companion object {
        private const val FILE_REF = """{"attachmentId":"sha256:file","name":"stored.bin","bytes":3}"""
        private const val IMAGE_REF = """{"attachmentId":"sha256:image","mediaType":"image/png","bytes":3,
          "width":1,"height":1,"name":"照片.png","originalDimensions":{"width":2,"height":2}}"""
    }
}
