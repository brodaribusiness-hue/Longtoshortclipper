package com.shortsclipper

import com.shortsclipper.model.Segment
import com.shortsclipper.video.captionSegmentsForSourceRange
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionRangesTest {

    @Test
    fun `caption range keeps only segments intersecting the output clip`() {
        val captions = listOf(
            Segment("before", 0L, 100L),
            Segment("crosses start", 100L, 250L),
            Segment("inside", 250L, 300L),
            Segment("crosses end", 300L, 450L),
            Segment("after", 450L, 500L),
        )

        assertEquals(
            listOf("crosses start", "inside", "crosses end"),
            captionSegmentsForSourceRange(captions, sourceStartMs = 200L, sourceEndMs = 400L)
                .map { it.text },
        )
    }

    @Test
    fun `caption range excludes empty and boundary-only captions`() {
        val captions = listOf(
            Segment("ends at clip start", 0L, 100L),
            Segment("starts at clip end", 200L, 300L),
            Segment("", 120L, 180L),
            Segment("valid", 120L, 180L),
        )

        assertEquals(
            listOf("valid"),
            captionSegmentsForSourceRange(captions, sourceStartMs = 100L, sourceEndMs = 200L)
                .map { it.text },
        )
    }
}
