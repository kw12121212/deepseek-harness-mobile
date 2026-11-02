package app.deepseek.harness.mobile

import org.json.JSONArray
import org.json.JSONObject

/** Draft attachment state, mutated on the main thread. IDs are never reused. */
internal class AttachmentsState {
    enum class Status { READY, UPLOADING, FAILED }
    data class Item(val id: Long, val name: String, val mediaType: String?, val bytes: ByteArray,
                    val size: Long, val status: Status, val receiptId: String? = null) {
        val isImage: Boolean get() = mediaType != null
    }
    private val entries = mutableListOf<Item>()
    private val sentImages = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<ByteArray>>()
    fun rememberImage(bytes: ByteArray) {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val key = "sha256:" + digest.joinToString("") { "%02x".format(it) }
        sentImages[key] = java.util.concurrent.FutureTask { bytes }.also { it.run() }
    }
    @Volatile private var imageGeneration = 0L
    fun imageCacheGeneration(): Long = imageGeneration
    fun loadImage(id: String, generation: Long, download: () -> ByteArray): ByteArray {
        val task = java.util.concurrent.FutureTask(download)
        val selected = synchronized(sentImages) {
            if (generation == imageGeneration) sentImages.putIfAbsent(id, task) ?: task else task
        }
        if (selected === task) task.run()
        try {
            return selected.get()
        } catch (error: java.util.concurrent.ExecutionException) {
            sentImages.remove(id, selected)
            throw (error.cause ?: error)
        }
    }
    val items: List<Item> get() = entries.toList()
    private var nextId = 0L
    var pendingReads: Int = 0
        private set
    fun beginRead() { pendingReads++ }
    fun endRead() { if (pendingReads > 0) pendingReads-- }

    fun addImage(name: String, mediaType: String, bytes: ByteArray, size: Long = bytes.size.toLong()): Long {
        require(size <= 50L * MIB) { "图片不能超过 50 MiB" }
        require(entries.count { it.isImage } < 20) { "最多选择 20 张图片" }
        require(entries.filter { it.isImage }.sumOf { it.size } + size <= 200L * MIB) { "图片总大小不能超过 200 MiB" }
        return add(name, mediaType, bytes, size, Status.READY)
    }
    fun addFile(name: String, bytes: ByteArray, size: Long = bytes.size.toLong()): Long {
        require(size <= 32L * MIB) { "文件过大（最大 32 MiB）" }
        return add(name, null, bytes, size, Status.UPLOADING)
    }
    private fun add(name: String, type: String?, bytes: ByteArray, size: Long, status: Status): Long {
        require(size >= 0 && bytes.isNotEmpty()) { "附件为空" }
        val id = ++nextId
        entries.add(Item(id, name, type, bytes, size, status))
        return id
    }
    fun remove(id: Long) { entries.removeAll { it.id == id } }
    fun clear() { entries.clear(); synchronized(sentImages) { imageGeneration++; sentImages.clear() }; pendingReads = 0 }
    fun fail(id: Long) = change(id, Status.UPLOADING) { it.copy(status = Status.FAILED) }
    fun complete(id: Long, receiptId: String) {
        require(receiptId.isNotBlank())
        change(id, Status.UPLOADING) { it.copy(status = Status.READY, receiptId = receiptId) }
    }
    fun retry(id: Long): Boolean = change(id, Status.FAILED) { it.copy(status = Status.UPLOADING) }
    private fun change(id: Long, from: Status, update: (Item) -> Item): Boolean {
        val index = entries.indexOfFirst { it.id == id && it.status == from }
        if (index < 0) return false
        entries[index] = update(entries[index]); return true
    }
    fun snapshot(): AttachmentsState = AttachmentsState().also { it.entries.addAll(entries); it.nextId = nextId; it.pendingReads = pendingReads }
    fun canSend(text: String): Boolean = (text.isNotBlank() || entries.isNotEmpty()) && pendingReads == 0 && entries.all { it.status == Status.READY }
    fun contentBlocks(text: String, image: (Item) -> JSONObject): JSONArray {
        require(canSend(text))
        return JSONArray().apply {
            if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
            entries.forEach { put(if (it.isImage) image(it) else JSONObject().put("type", "file").put("receiptId", it.receiptId)) }
        }
    }
    companion object { private const val MIB = 1024L * 1024 }
}
