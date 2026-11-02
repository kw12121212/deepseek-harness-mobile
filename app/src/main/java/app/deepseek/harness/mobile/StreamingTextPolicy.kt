package app.deepseek.harness.mobile

internal object StreamingTextPolicy {
    const val MAX_STREAM_BACKLOG = 64
    const val STREAM_CHARACTER_MS = 24L
    const val STREAM_FADE_MS = 90L
    const val STREAM_NEWEST_ALPHA = 92
    const val MAX_CATCH_UP_MS = 768L

    /** A fixed deadline avoids an exponentially shrinking tail that never catches up. */
    fun streamingStep(backlog: Int, elapsedMs: Long, remainingMs: Long): Int {
        if (backlog <= 0 || elapsedMs <= 0) return 0
        if (elapsedMs >= remainingMs) return backlog
        if (elapsedMs < STREAM_CHARACTER_MS) return 0
        val accelerated = kotlin.math.ceil(backlog.toDouble() * elapsedMs / remainingMs).toLong()
        return maxOf(elapsedMs / STREAM_CHARACTER_MS, accelerated).coerceAtMost(backlog.toLong()).toInt()
    }

    fun initialOffset(target: String, previous: String): Int {
        if (previous.isNotEmpty() && target.startsWith(previous)) return previous.length
        val points = target.codePointCount(0, target.length)
        return target.offsetByCodePoints(0, (points - MAX_STREAM_BACKLOG).coerceAtLeast(0))
    }

    fun nextOffset(target: String, shownOffset: Int, step: Int): Int {
        val remaining = target.codePointCount(shownOffset, target.length)
        return target.offsetByCodePoints(shownOffset, step.coerceIn(0, remaining))
    }

    /**
     * Only unfinished constructs can change when more source arrives. Plain runs can be
     * committed mid-line; headings, rules and inline code wait for their line ending.
     * Bold may cross line endings, so its opening delimiter must remain revisable.
     */
    fun markdownStableEnd(source: CharSequence, start: Int): Int {
        var cursor = start
        var stableEnd = start
        var boldStart = -1
        var plainLine = start > 0 && source[start - 1] != '\n' && source[start - 1] != '\r'
        if (!plainLine) plainLine = !hasBlockPrefix(source, start)
        while (cursor < source.length) {
            val character = source[cursor]
            // Inline code excludes LF, but may cross CR and other regex line separators.
            if (character == '`' || character in "\r\u0085\u2028\u2029") plainLine = false
            if (character == '*' && cursor + 1 < source.length && source[cursor + 1] == '*') {
                if (boldStart < 0) {
                    boldStart = cursor
                    cursor += 2
                    continue
                }
                if (cursor > boldStart + 2) {
                    boldStart = -1
                    cursor += 2
                    if (plainLine) stableEnd = cursor
                    continue
                }
            }
            cursor += Character.charCount(Character.codePointAt(source, cursor))
            if (character == '\n') {
                if (boldStart < 0) stableEnd = cursor
                plainLine = !hasBlockPrefix(source, cursor)
            } else if (plainLine && boldStart < 0 && !(character == '*' && cursor == source.length)) {
                stableEnd = cursor
            }
        }
        return stableEnd
    }

    private fun hasBlockPrefix(source: CharSequence, start: Int): Boolean {
        if (start < source.length && source[start] == '#') return true
        var cursor = start
        while (cursor < source.length && (source[cursor] == ' ' || source[cursor] == '\t')) cursor++
        return cursor == source.length || source[cursor] == '-'
    }
}
