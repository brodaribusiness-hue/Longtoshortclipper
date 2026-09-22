package com.shortsclipper.video

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import com.shortsclipper.model.Segment

/**
 * Dynamic local captions for one clipped source sequence. Media3 asks this
 * overlay on every output frame; lookup is binary-search based and all styled
 * strings are prebuilt, so export does not allocate a new caption bitmap/text
 * for each frame while a segment remains active.
 */
@OptIn(UnstableApi::class)
internal class TimedCaptionOverlay(
    segments: List<Segment>,
    private val sourceSegmentStartMs: Long,
) : TextOverlay() {

    private data class Caption(val startMs: Long, val endMs: Long, val text: SpannableString)

    private val captions = segments
        .asSequence()
        .filter { it.endTimeMs > it.startTimeMs && it.text.isNotBlank() }
        .sortedBy { it.startTimeMs }
        .map { segment -> Caption(segment.startTimeMs, segment.endTimeMs, styledCaption(segment.text)) }
        .toList()
    private val blank = SpannableString(" ")
    private val visibleSettings = OverlaySettings.Builder()
        // Bottom-center in NDC. The overlay's top-center attaches here so text
        // expands upward and remains inside a vertical short frame.
        .setBackgroundFrameAnchor(0f, -0.73f)
        .setOverlayFrameAnchor(0f, -1f)
        .setScale(0.82f, 0.82f)
        .build()
    private val hiddenSettings = OverlaySettings.Builder().setAlphaScale(0f).build()

    override fun getText(presentationTimeUs: Long): SpannableString =
        captionAt(sourceSegmentStartMs + presentationTimeUs / 1_000L)?.text ?: blank

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (captionAt(sourceSegmentStartMs + presentationTimeUs / 1_000L) == null) hiddenSettings else visibleSettings

    private fun captionAt(sourceTimeMs: Long): Caption? {
        var low = 0
        var high = captions.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (captions[middle].startMs <= sourceTimeMs) low = middle + 1 else high = middle - 1
        }
        val candidate = captions.getOrNull(high) ?: return null
        return candidate.takeIf { sourceTimeMs < it.endMs }
    }

    private fun styledCaption(raw: String): SpannableString {
        val text = lineWrap(raw.replace(Regex("\\s+"), " ").trim())
        return SpannableString(text).apply {
            val end = length.coerceAtLeast(1)
            setSpan(ForegroundColorSpan(Color.WHITE), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BackgroundColorSpan(Color.argb(178, 0, 0, 0)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.72f), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun lineWrap(text: String): String {
        if (text.length <= 34) return text
        val words = text.split(' ')
        val firstLine = StringBuilder()
        val secondLine = StringBuilder()
        for (word in words) {
            val target = if (firstLine.length == 0 || firstLine.length + word.length + 1 <= 30) firstLine else secondLine
            if (target.isNotEmpty()) target.append(' ')
            target.append(word)
            if (secondLine.length >= 34) break
        }
        val result = if (secondLine.isEmpty()) firstLine.toString() else "$firstLine\n$secondLine"
        return if (result.length < text.length && secondLine.length >= 34) "$result…" else result
    }
}
