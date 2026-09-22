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
        val clips = removals
            .map { SilenceEdit(it.startMs + KEEP_PAD_MS, it.endMs - KEEP_PAD_MS) }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }

        val segments = ArrayList<ClipSegment>()
        var cursor = selectionStartMs
        for (cut in clips) {
            val cutStart = cut.startMs.coerceIn(selectionStartMs, selectionEndMs)
            val cutEnd = cut.endMs.coerceIn(selectionStartMs, selectionEndMs)
            if (cutStart > cursor) {
                segments.add(ClipSegment(cursor, cutStart))
            }
            cursor = maxOf(cursor, cutEnd)
        }
        if (cursor < selectionEndMs) segments.add(ClipSegment(cursor, selectionEndMs))

        // Merge/protect against micro segments so speech is never chopped into slivers.
        val merged = ArrayList<ClipSegment>()
        for (seg in segments) {
            if (seg.durationMs < MIN_SEGMENT_MS && merged.isNotEmpty()) {
                val prev = merged.removeAt(merged.size - 1)
                merged.add(ClipSegment(prev.startMs, seg.endMs))
            } else {
                merged.add(seg)
            }
        }
        return merged.filter { it.durationMs >= MIN_SEGMENT_MS }
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
