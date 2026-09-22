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

    /** Actual source intervals removed after preserving speech-safe padding. */
    fun effectiveCuts(selectionStartMs: Long, selectionEndMs: Long, removals: List<SilenceEdit>): List<SilenceEdit> {
        if (selectionEndMs <= selectionStartMs) return emptyList()
        val normalized = removals
            .mapNotNull { removal ->
                val start = (removal.startMs + KEEP_PAD_MS).coerceIn(selectionStartMs, selectionEndMs)
                val end = (removal.endMs - KEEP_PAD_MS).coerceIn(selectionStartMs, selectionEndMs)
                if (end > start) SilenceEdit(start, end) else null
            }
            .sortedBy { it.startMs }

        // Coalesce overlaps so cursor advancement and timeline rendering agree.
        val merged = ArrayList<SilenceEdit>()
        for (cut in normalized) {
            val previous = merged.lastOrNull()
            if (previous != null && cut.startMs <= previous.endMs) {
                merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, cut.endMs))
            } else {
                merged += cut
            }
        }
        return merged
    }

    fun buildSegments(selectionStartMs: Long, selectionEndMs: Long, removals: List<SilenceEdit>): List<ClipSegment> {
        if (selectionEndMs <= selectionStartMs) return emptyList()
        val cuts = effectiveCuts(selectionStartMs, selectionEndMs, removals)
        val rawSegments = ArrayList<ClipSegment>()
        var cursor = selectionStartMs
        for (cut in cuts) {
            if (cut.startMs > cursor) rawSegments += ClipSegment(cursor, cut.startMs)
            cursor = maxOf(cursor, cut.endMs)
        }
        if (cursor < selectionEndMs) rawSegments += ClipSegment(cursor, selectionEndMs)
        if (rawSegments.isEmpty()) return emptyList()

        // A micro-segment would make an audible click or cut a word. Merge it
        // with a neighbor (thereby keeping that tiny silence) instead of
        // dropping source video at either edge of the selection.
        val result = ArrayList<ClipSegment>()
        for (index in rawSegments.indices) {
            val segment = rawSegments[index]
            if (segment.durationMs >= MIN_SEGMENT_MS) {
                result += segment
            } else if (result.isNotEmpty()) {
                val previous = result.removeAt(result.lastIndex)
                result += ClipSegment(previous.startMs, segment.endMs)
            } else if (index + 1 < rawSegments.size) {
                val next = rawSegments[index + 1]
                rawSegments[index + 1] = ClipSegment(segment.startMs, next.endMs)
            } else {
                // A very short whole selection is still a legitimate clip.
                result += segment
            }
        }
        return result.filter { it.durationMs > 0L }
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
    /** Monotonic edit generation prevents same-millisecond autosave rollback. */
    val revision: Long = 0L,
    val source: VideoSource? = null,
    val timeline: TimelineState = TimelineState(),
    val tracking: TrackingState = TrackingState(),
    /** Downsampled audio loudness (RMS) used for waveform + silence detection. */
    val audioEnvelope: List<Float> = emptyList(),
    /** Source-video timestamp of the first decoded PCM sample. */
    val audioStartMs: Long = 0L,
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
        var remaining = segmentTimeMs.coerceAtLeast(0L)
        for (seg in exportSegments) {
            if (remaining < seg.durationMs) return seg.startMs + remaining
            remaining -= seg.durationMs
        }
        return exportSegments.lastOrNull()?.endMs ?: timeline.selectionStartMs
    }
}
