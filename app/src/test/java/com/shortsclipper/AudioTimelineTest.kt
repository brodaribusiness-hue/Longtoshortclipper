package com.shortsclipper

import com.shortsclipper.video.TargetTimelineOverlapTrimmer
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimelineTest {

    @Test
    fun `decoder timestamp overlap trims only already written target samples`() {
        assertEquals(
            32L,
            TargetTimelineOverlapTrimmer.overlapSamples(
                writtenTargetSamples = 160L,
                targetStartSample = 128L,
            ),
        )

        val emitted = mutableListOf<Float>()
        val trimmer = TargetTimelineOverlapTrimmer(2L)
        listOf(0f, 1f, 2f, 3f).forEach { sample ->
            trimmer.emit(sample) { emitted += it }
        }

        assertEquals(listOf(2f, 3f), emitted)
    }

    @Test
    fun `decoder gap never becomes an overlap trim`() {
        assertEquals(
            0L,
            TargetTimelineOverlapTrimmer.overlapSamples(
                writtenTargetSamples = 128L,
                targetStartSample = 160L,
            ),
        )
    }
}
