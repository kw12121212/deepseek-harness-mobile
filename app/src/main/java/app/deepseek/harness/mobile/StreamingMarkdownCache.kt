package app.deepseek.harness.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView

internal class StreamingAnimationState(
    val key: String,
    var view: TextView,
    var message: ChatMessage,
    var target: String,
    var shownOffset: Int,
    var lastTextUpdateMs: Long,
    var burstDeadlineMs: Long,
    var fadeDeadlineMs: Long,
    val markdownCache: StreamingMarkdownCache,
)

internal class StreamingMarkdownCache(
    private val mutedColor: Int,
    private val inlineCodeColor: Int,
    textColor: Int,
) {
    private var committedSourceOffset = 0
    private val committedBuffer = SpannableStringBuilder()
    private var boundView: TextView? = null
    private var activeAlphaSpan: ForegroundColorSpan? = null
    private val newestColor = Color.argb(
        StreamingTextPolicy.STREAM_NEWEST_ALPHA,
        Color.red(textColor), Color.green(textColor), Color.blue(textColor),
    )

    fun bindTo(view: TextView, target: String, shownOffset: Int, applyAlpha: Boolean) {
        boundView = view
        if (shownOffset < committedSourceOffset) {
            committedSourceOffset = 0
            committedBuffer.clear()
            committedBuffer.clearSpans()
        }
        val shown = SourcePrefix(target, shownOffset)
        commitPrefix(shown)
        val full = SpannableStringBuilder(committedBuffer).append(renderTail(shown))
        activeAlphaSpan = null
        if (applyAlpha) fadeNewest(full)
        view.setText(full, TextView.BufferType.EDITABLE)
    }

    fun update(target: String, shownOffset: Int, view: TextView, applyAlpha: Boolean) {
        if (shownOffset < committedSourceOffset || boundView !== view || view.editableText == null) {
            bindTo(view, target, shownOffset, applyAlpha)
            return
        }
        val editable = view.editableText
        val shown = SourcePrefix(target, shownOffset)
        val previousCommittedLength = committedBuffer.length
        commitPrefix(shown)
        val replacement = SpannableStringBuilder(committedBuffer, previousCommittedLength, committedBuffer.length)
            .append(renderTail(shown))
        removeAlphaSpan(editable)
        editable.replace(previousCommittedLength, editable.length, replacement)
        if (applyAlpha) fadeNewest(editable)
    }

    fun clearFade(view: TextView) {
        val editable = view.editableText
        removeAlphaSpan(editable)
        android.text.util.Linkify.addLinks(editable, view.autoLinkMask)
        view.invalidate()
    }

    private fun commitPrefix(shown: CharSequence) {
        val stableEnd = StreamingTextPolicy.markdownStableEnd(shown, committedSourceOffset)
        if (stableEnd > committedSourceOffset) {
            committedBuffer.append(renderChunk(shown, committedSourceOffset, stableEnd))
            committedSourceOffset = stableEnd
        }
    }

    private fun renderTail(shown: CharSequence): CharSequence =
        renderChunk(shown, committedSourceOffset, shown.length)

    private fun fadeNewest(text: Spannable) {
        if (text.isEmpty()) return
        val newestStart = text.length - Character.charCount(Character.codePointBefore(text, text.length))
        val span = ForegroundColorSpan(newestColor)
        text.setSpan(span, newestStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        activeAlphaSpan = span
    }

    private fun removeAlphaSpan(text: Spannable) {
        activeAlphaSpan?.let { text.removeSpan(it) }
        activeAlphaSpan = null
    }

    private fun renderChunk(source: CharSequence, start: Int, end: Int): SpannableStringBuilder {
        if (start >= end) return SpannableStringBuilder()
        val slice = source.subSequence(start, end).toString()
        val midLine = start > 0 && source[start - 1] != '\n' && source[start - 1] != '\r'
        val styled = styleMarkdown(if (midLine) "x$slice" else slice, mutedColor, inlineCodeColor)
        if (midLine) styled.delete(0, 1)
        return styled
    }

    companion object {
        private val REGEX_HEADING = Regex("(?m)^(#{1,6})[ \\t]+(.+)$")
        private val REGEX_RULE = Regex("(?m)^[ \\t]*-{3,}[ \\t]*$")
        private val REGEX_BOLD = Regex("\\*\\*(.+?)\\*\\*", setOf(RegexOption.DOT_MATCHES_ALL))
        private val REGEX_INLINE_CODE = Regex("`([^`\\n]+)`")

        fun styleMarkdown(
            source: String,
            mutedColor: Int,
            inlineCodeColor: Int,
        ): SpannableStringBuilder {
            val text = SpannableStringBuilder(source)
            REGEX_HEADING.findAll(text).toList().asReversed().forEach { match ->
                val markerStart = match.range.first
                val contentStart = match.groups[2]?.range?.first ?: return@forEach
                val level = match.groups[1]?.value?.length ?: 6
                val contentLength = match.groups[2]?.value?.length ?: 0
                text.delete(markerStart, contentStart)
                val headingEnd = (markerStart + contentLength).coerceAtMost(text.length)
                text.setSpan(StyleSpan(Typeface.BOLD), markerStart, headingEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                val scale = when (level) {
                    1 -> 1.16f
                    2 -> 1.12f
                    3 -> 1.08f
                    else -> 1.04f
                }
                text.setSpan(RelativeSizeSpan(scale), markerStart, headingEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            REGEX_RULE.findAll(text).toList().asReversed().forEach { match ->
                text.replace(match.range.first, match.range.last + 1, "────────")
                text.setSpan(
                    ForegroundColorSpan(mutedColor),
                    match.range.first,
                    (match.range.first + 8).coerceAtMost(text.length),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            REGEX_BOLD.findAll(text).toList().asReversed().forEach { match ->
                val start = match.range.first
                val endExclusive = match.range.last + 1
                text.delete(endExclusive - 2, endExclusive)
                text.delete(start, start + 2)
                text.setSpan(StyleSpan(Typeface.BOLD), start, endExclusive - 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            REGEX_INLINE_CODE.findAll(text).toList().asReversed().forEach { match ->
                val start = match.range.first
                val endExclusive = match.range.last + 1
                text.delete(endExclusive - 1, endExclusive)
                text.delete(start, start + 1)
                val styledEnd = endExclusive - 2
                text.setSpan(TypefaceSpan("monospace"), start, styledEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(BackgroundColorSpan(inlineCodeColor), start, styledEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return text
        }
    }
}

/** A view of the visible prefix avoids copying the entire transcript on every frame. */
private class SourcePrefix(private val source: String, override val length: Int) : CharSequence {
    override fun get(index: Int): Char = source[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = source.subSequence(startIndex, endIndex)
    override fun toString(): String = source.substring(0, length)
}
