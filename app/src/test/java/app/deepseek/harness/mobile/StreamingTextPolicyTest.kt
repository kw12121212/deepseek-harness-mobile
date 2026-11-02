package app.deepseek.harness.mobile

import app.deepseek.harness.mobile.StreamingTextPolicy.MAX_CATCH_UP_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextPolicyTest {
    @Test
    fun smallBacklogRevealsOneCodePointAtNormalCadence() {
        assertEquals(0, StreamingTextPolicy.streamingStep(8, 23, MAX_CATCH_UP_MS))
        assertEquals(1, StreamingTextPolicy.streamingStep(8, 24, MAX_CATCH_UP_MS))
        assertEquals(1, StreamingTextPolicy.streamingStep(8, 33, MAX_CATCH_UP_MS))
    }

    @Test
    fun largerBacklogAcceleratesWithoutRevealingEverythingOnFirstTick() {
        val medium = StreamingTextPolicy.streamingStep(256, 24, MAX_CATCH_UP_MS)
        val large = StreamingTextPolicy.streamingStep(50_000, 24, MAX_CATCH_UP_MS)
        assertTrue(medium > 1)
        assertTrue(medium < 256)
        assertTrue(large > medium)
        assertTrue(large < 50_000)
    }

    @Test
    fun finiteBacklogsCatchUpByDeadlineWithVariableFrameIntervals() {
        for (initial in listOf(1, 8, 64, 50_000, Int.MAX_VALUE)) {
            var backlog = initial
            var elapsed = 0L
            while (elapsed < MAX_CATCH_UP_MS && backlog > 0) {
                val frameMs = minOf(33L, MAX_CATCH_UP_MS - elapsed)
                val step = StreamingTextPolicy.streamingStep(backlog, frameMs, MAX_CATCH_UP_MS - elapsed)
                assertTrue("step must stay within backlog", step in 0..backlog)
                backlog -= step
                elapsed += frameMs
            }
            assertEquals("finite backlog must be empty by deadline", 0, backlog)
        }
    }

    @Test
    fun zeroBacklogNeverAdvancesEvenAfterDeadline() {
        assertEquals(0, StreamingTextPolicy.streamingStep(0, 0, MAX_CATCH_UP_MS))
        assertEquals(0, StreamingTextPolicy.streamingStep(0, 900, -100))
    }

    @Test
    fun delayedFramesRespectDeadlineAndCannotOvershootBacklog() {
        assertEquals(17, StreamingTextPolicy.streamingStep(17, MAX_CATCH_UP_MS, MAX_CATCH_UP_MS))
        assertEquals(17, StreamingTextPolicy.streamingStep(17, MAX_CATCH_UP_MS + 32, MAX_CATCH_UP_MS))
        assertEquals(17, StreamingTextPolicy.streamingStep(17, Long.MAX_VALUE, MAX_CATCH_UP_MS))
        assertEquals(17, StreamingTextPolicy.streamingStep(17, 24, 0))
        assertEquals(5, StreamingTextPolicy.streamingStep(5, 24, 24))
        assertEquals(1, StreamingTextPolicy.streamingStep(5, 24, 120))
        assertEquals(2, StreamingTextPolicy.streamingStep(5, 24, 119))
    }

    @Test
    fun initialBacklogTruncationCountsCodePointsInsteadOfUtf16Units() {
        val target = "😀".repeat(70)
        val start = StreamingTextPolicy.initialOffset(target, "")
        assertEquals(12, start)
        assertEquals(64, target.codePointCount(start, target.length))
        assertEquals(0, StreamingTextPolicy.initialOffset("😀".repeat(64), ""))
        assertEquals(0, StreamingTextPolicy.initialOffset("", ""))
    }

    @Test
    fun matchingPrefixResumesAndCorrectedTextResets() {
        assertEquals(3, StreamingTextPolicy.initialOffset("A😀BC", "A😀"))
        assertEquals(0, StreamingTextPolicy.initialOffset("new", "old"))
        assertEquals(6, StreamingTextPolicy.initialOffset("😀".repeat(67), "old"))
    }

    @Test
    fun nextOffsetNeverSplitsSupplementaryCharactersOrOvershoots() {
        val target = "A😀中𐐀Z"
        assertEquals(3, StreamingTextPolicy.nextOffset(target, 1, 1))
        assertEquals(6, StreamingTextPolicy.nextOffset(target, 1, 3))
        assertEquals(target.length, StreamingTextPolicy.nextOffset(target, 3, Int.MAX_VALUE))
        assertEquals(3, StreamingTextPolicy.nextOffset(target, 3, 0))
    }

    @Test
    fun plainTextCanBeCachedWithoutWaitingForNewline() {
        val source = "plain " + "😀".repeat(10_000)
        assertEquals(source.length, StreamingTextPolicy.markdownStableEnd(source, 0))
        assertEquals(source.length, StreamingTextPolicy.markdownStableEnd(source, 6))
        assertEquals(5, StreamingTextPolicy.markdownStableEnd("plain*", 0))
    }

    @Test
    fun unfinishedBoldRemainsRevisableAcrossLines() {
        assertEquals(6, StreamingTextPolicy.markdownStableEnd("plain **open\nmore", 0))
        val closed = "plain **open\nmore** done"
        assertEquals(closed.length, StreamingTextPolicy.markdownStableEnd(closed, 6))
        assertEquals(0, StreamingTextPolicy.markdownStableEnd("****", 0))
        assertEquals(5, StreamingTextPolicy.markdownStableEnd("*****", 0))
    }

    @Test
    fun headingRuleAndCodeLinesRemainRevisableUntilLineEnding() {
        for (source in listOf("# heading", "---", "  ---  ", "`code` tail")) {
            assertEquals(0, StreamingTextPolicy.markdownStableEnd(source, 0))
            assertEquals(source.length + 1, StreamingTextPolicy.markdownStableEnd("$source\n", 0))
        }
        assertEquals(6, StreamingTextPolicy.markdownStableEnd("line\r\n# heading", 0))
    }

    @Test
    fun midLineMarkersCannotBecomeNewHeadingsOrRules() {
        assertEquals(13, StreamingTextPolicy.markdownStableEnd("plain # title", 6))
        assertEquals(9, StreamingTextPolicy.markdownStableEnd("plain ---", 6))
    }

    @Test
    fun cachedMarkdownChunksMatchWholeMessageForEveryRevealPrefix() {
        val sources = listOf(
            "# Heading 😀\nplain **bold\nacross lines** and `code`\n---\nend",
            "plain # mid-line and --- mid-line **bold** trailing* stars*****\n",
            "`**`x** rest`\n**bold `code`**\n  ---  \n## heading **strong**\r\n",
        )
        sources.forEach(::assertCachedPrefixes)
    }

    @Test
    fun cacheBoundariesPreserveMixedDelimitersAndLineEndings() {
        val random = java.util.Random(5)
        val alphabet = listOf("#", "-", "*", "`", " ", "\t", "\n", "\r", "x", "😀")
        repeat(250) {
            val source = buildString { repeat(48) { append(alphabet[random.nextInt(alphabet.size)]) } }
            assertCachedPrefixes(source)
        }
    }

    private fun assertCachedPrefixes(source: String) {
        var committedEnd = 0
        var committedText = ""
        var shownEnd = 0
        while (shownEnd < source.length) {
            shownEnd = StreamingTextPolicy.nextOffset(source, shownEnd, 1)
            val shown = source.substring(0, shownEnd)
            val stableEnd = StreamingTextPolicy.markdownStableEnd(shown, committedEnd)
            committedText += renderChunk(shown, committedEnd, stableEnd)
            committedEnd = stableEnd
            val cached = committedText + renderChunk(shown, committedEnd, shown.length)
            assertEquals("cached formatting must preserve each revealed prefix", markdownText(shown), cached)
        }
    }

    private fun renderChunk(source: String, start: Int, end: Int): String {
        val chunk = source.substring(start, end)
        val midLine = start > 0 && source[start - 1] != '\n' && source[start - 1] != '\r'
        return if (midLine) markdownText("x$chunk").drop(1) else markdownText(chunk)
    }

    private fun markdownText(source: String): String = source
        .replace(Regex("(?m)^(#{1,6})[ \\t]+(.+)$"), "$2")
        .replace(Regex("(?m)^[ \\t]*-{3,}[ \\t]*$"), "────────")
        .replace(Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace(Regex("`([^`\\n]+)`"), "$1")
}
