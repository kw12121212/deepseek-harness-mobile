package app.deepseek.harness.mobile

internal fun nextSessionAfterArchive(
    sessions: List<HarnessApi.Session>,
    archivedSessionId: String,
    archivedSessionIds: Set<String>,
): HarnessApi.Session? {
    val archivedIndex = sessions.indexOfFirst { it.id == archivedSessionId }
    val candidates = sessions.drop(archivedIndex + 1) + sessions.take(archivedIndex.coerceAtLeast(0))
    return candidates.firstOrNull { !it.blank && it.id != archivedSessionId && it.id !in archivedSessionIds }
}
