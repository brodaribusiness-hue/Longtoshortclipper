package com.shortsclipper.video

import com.shortsclipper.model.Segment

/** Returns only timed captions that can be visible within one clipped source interval. */
internal fun captionSegmentsForSourceRange(
    segments: List<Segment>,
    sourceStartMs: Long,
    sourceEndMs: Long,
): List<Segment> {
    if (sourceEndMs <= sourceStartMs) return emptyList()
    return segments.asSequence()
        .filter {
            it.endTimeMs > it.startTimeMs &&
                it.endTimeMs > sourceStartMs &&
                it.startTimeMs < sourceEndMs &&
                it.text.isNotBlank()
        }
        .sortedBy { it.startTimeMs }
        .toList()
}
