package app.deepseek.harness.mobile

internal data class QueueStripState(
    val expanded: Boolean = false,
    val busyId: String? = null,
    val editingId: String? = null,
) {
    fun toggleExpanded(): QueueStripState = if (busyId == null) copy(expanded = !expanded) else this

    fun reconcile(rows: List<HarnessApi.QueuedPrompt>): QueueStripState = copy(
        expanded = expanded && rows.isNotEmpty(),
        editingId = editingId?.takeIf { id -> rows.any { it.id == id && it.text != null } },
    )
}

internal fun queueStripVisible(rows: List<HarnessApi.QueuedPrompt>, state: QueueStripState): Boolean =
    rows.isNotEmpty() || state.busyId != null

internal fun queueTargetLabel(target: String, tr: (String, String) -> String): String =
    if (target == "next-step") tr("下一步", "Next step") else tr("下一轮", "Next turn")

internal fun queuePreview(text: String?, tr: (String, String) -> String): String =
    text?.replace(Regex("\\s+"), " ")?.trim() ?: tr("包含非文本内容", "Contains non-text content")

internal data class QueueActionAvailability(val edit: Boolean, val steer: Boolean, val remove: Boolean)

internal fun queueActionAvailability(
    row: HarnessApi.QueuedPrompt,
    state: QueueStripState,
    running: Boolean,
): QueueActionAvailability = QueueActionAvailability(
    edit = row.text != null && state.busyId == null,
    steer = running && state.busyId == null,
    remove = state.busyId == null,
)

internal fun queueEditSaveAllowed(text: String, state: QueueStripState): Boolean =
    text.isNotBlank() && state.busyId == null
