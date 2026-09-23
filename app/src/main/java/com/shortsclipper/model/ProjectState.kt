package com.shortsclipper.model

import kotlinx.serialization.Serializable

/** Source video metadata (URI-based; the file is never fully loaded into RAM). */
@Serializable
data class VideoSource(
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    /** Encoded dimensions (as stored in the container). */
    val width: Int,
    val height: Int,
    /** Rotation metadata in degrees (0/90/180/270). */
    val rotationDegrees: Int,
    /** Display dimensions after applying rotation. */
    val displayWidth: Int,
    val displayHeight: Int,
    val fps: Float,
    val hasAudio: Boolean,
    val videoMimeType: String?,
    val audioMimeType: String?,
)

/** Timeline selection state. The playhead is transient UI state and NOT part of history. */
@Serializable
data class TimelineState(
    val selectionStartMs: Long = 0L,
    val selectionEndMs: Long = 0L,
    val zoom: Float = 1f,
) {
    val durationMs: Long get() = (selectionEndMs - selectionStartMs).coerceAtLeast(0)
}

/** A removed time interval (source-relative, milliseconds). */
@Serializable
data class SilenceEdit(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** One contiguous playable/exportable piece of the source video. */
@Serializable
data class ClipSegment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * Splits a selected range into segments by removing the given silence intervals.
 * Used identically by preview (playhead skipping) and export (sequence building),
 * which keeps preview and export synchronized.
 */
object ExportPlanner {

    const val MIN_SEGMENT_MS = 250L
    const val KEEP_PAD_MS = 120L

    fun buildSegments(selectionStartMs: Long, selectionEndMs: Long, removals: List<SilenceEdit>): List<ClipSegment> {
        if (selectionEndMs <= selectionStartMs) return emptyList()
        val cuts = normalizedCuts(selectionStartMs, selectionEndMs, removals)
        val raw = splitAroundCuts(selectionStartMs, selectionEndMs, cuts)
        return preserveShortSpeech(raw, selectionStartMs, selectionEndMs)
    }

    /** Pads silence cuts, drops cuts that vanish inside the pad, and merges overlaps. */
    private fun normalizedCuts(
        selectionStartMs: Long,
        selectionEndMs: Long,
        removals: List<SilenceEdit>,
    ): List<SilenceEdit> {
        val padded = removals
            .map { SilenceEdit(it.startMs + KEEP_PAD_MS, it.endMs - KEEP_PAD_MS) }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        val merged = ArrayList<SilenceEdit>()
        for (cut in padded) {
            val start = cut.startMs.coerceIn(selectionStartMs, selectionEndMs)
            val end = cut.endMs.coerceIn(selectionStartMs, selectionEndMs)
            if (end <= start) continue
            val last = merged.lastOrNull()
            if (last != null && start <= last.endMs) {
                merged[merged.lastIndex] = SilenceEdit(last.startMs, maxOf(last.endMs, end))
            } else {
                merged.add(SilenceEdit(start, end))
            }
        }
        return merged
    }

    private fun splitAroundCuts(
        selectionStartMs: Long,
        selectionEndMs: Long,
        cuts: List<SilenceEdit>,
    ): List<ClipSegment> {
        val segments = ArrayList<ClipSegment>()
        var cursor = selectionStartMs
        for (cut in cuts) {
            if (cut.startMs > cursor) segments.add(ClipSegment(cursor, cut.startMs))
            cursor = maxOf(cursor, cut.endMs)
        }
        if (cursor < selectionEndMs) segments.add(ClipSegment(cursor, selectionEndMs))
        return segments
    }

    /**
     * Extends a short speech island into adjacent removed silence until it
     * reaches [MIN_SEGMENT_MS]. Never bridges across the silence into the next
     * speech region — that would undo the removal. A selection shorter than
     * [MIN_SEGMENT_MS] is kept so a brief source still exports.
     */
    private fun preserveShortSpeech(
        segments: List<ClipSegment>,
        rangeStart: Long,
        rangeEnd: Long,
    ): List<ClipSegment> {
        if (segments.isEmpty()) return emptyList()
        val adjusted = ArrayList<ClipSegment>(segments.size)
        for (i in segments.indices) {
            val seg = segments[i]
            if (seg.durationMs >= MIN_SEGMENT_MS) {
                adjusted.add(seg)
                continue
            }
            val deficit = MIN_SEGMENT_MS - seg.durationMs
            val previousEnd = adjusted.lastOrNull()?.endMs ?: rangeStart
            val nextStart = segments.getOrNull(i + 1)?.startMs ?: rangeEnd
            val gapBefore = (seg.startMs - previousEnd).coerceAtLeast(0L)
            val gapAfter = (nextStart - seg.endMs).coerceAtLeast(0L)
            val takeBefore = minOf(gapBefore, deficit)
            val takeAfter = minOf(gapAfter, deficit - takeBefore)
            adjusted.add(ClipSegment(seg.startMs - takeBefore, seg.endMs + takeAfter))
        }
        val viable = adjusted.filter { it.durationMs >= MIN_SEGMENT_MS }
        return if (viable.isNotEmpty()) viable else adjusted.filter { it.durationMs > 0L }
    }
}

/** Clamps timeline handles so a short source cannot throw or produce an empty range. */
object SelectionConstraints {
    const val PREFERRED_MIN_MS = 1000L

    fun clamp(startMs: Long, endMs: Long, durationMs: Long): Pair<Long, Long>? {
        if (durationMs <= 0L) return null
        val minClip = minOf(PREFERRED_MIN_MS, durationMs)
        val maxStart = (durationMs - minClip).coerceAtLeast(0L)
        val start = startMs.coerceIn(0L, maxStart)
        val end = endMs.coerceIn(start + minClip, durationMs)
        return start to end
    }
}

/**
 * Preview silence skipping uses the same segment plan as export, so the
 * playhead does not play audio that the exporter will cut.
 */
object PlaybackSkip {
    fun gapSkipTarget(
        positionMs: Long,
        selectionStartMs: Long,
        selectionEndMs: Long,
        segments: List<ClipSegment>,
    ): Long? {
        if (segments.isEmpty()) return null
        if (positionMs < selectionStartMs || positionMs >= selectionEndMs) return null
        if (segments.any { positionMs >= it.startMs && positionMs < it.endMs }) return null
        return segments.firstOrNull { it.startMs > positionMs }?.startMs ?: selectionEndMs
    }
}

/**
 * The single authoritative editor state. Immutable: every edit produces a new
 * instance which is pushed onto the undo/redo history.
 */
@Serializable
data class ProjectState(
    val id: String,
    val name: String,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val source: VideoSource? = null,
    val timeline: TimelineState = TimelineState(),
    val tracking: TrackingState = TrackingState(),
    /** Downsampled audio loudness (RMS) used for the waveform + silence detection. */
    val audioEnvelope: List<Float> = emptyList(),
    val envelopeStepMs: Int = 100,
    /** Detected silence intervals (not yet applied). */
    val detectedSilences: List<SilenceEdit> = emptyList(),
    /** Applied silence removals inside the selected clip. */
    val silenceRemovals: List<SilenceEdit> = emptyList(),
    val exportQuality: QualityMode = QualityMode.HIGH_QUALITY,
) {
    /** Segments that will actually be exported/previewed for the current clip. */
    val exportSegments: List<ClipSegment>
        get() = ExportPlanner.buildSegments(timeline.selectionStartMs, timeline.selectionEndMs, silenceRemovals)

    val editedDurationMs: Long get() = exportSegments.sumOf { it.durationMs }

    fun sourceAt(segmentTimeMs: Long): Long {
        var remaining = segmentTimeMs
        for (seg in exportSegments) {
            if (remaining < seg.durationMs) return seg.startMs + remaining
            remaining -= seg.durationMs
        }
        return exportSegments.lastOrNull()?.endMs ?: timeline.selectionStartMs
    }
}
