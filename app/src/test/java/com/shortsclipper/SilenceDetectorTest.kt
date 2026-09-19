package com.shortsclipper

import com.shortsclipper.ai.SilenceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    private fun envelopeOf(vararg parts: Pair<Int, Float>): List<Float> {
        // parts: (sampleCount, rms) chunks appended together.
        return parts.flatMap { (count, rms) -> List(count) { rms } }
    }

    @Test
    fun `continuous speech has no silences`() {
        val env = envelopeOf(200 to 0.3f) // 20s of speech
        val silences = SilenceDetector.detect(env)
        assertTrue(silences.isEmpty())
    }

    @Test
    fun `long pause is detected`() {
        val env = envelopeOf(50 to 0.3f, 40 to 0.0005f, 50 to 0.3f) // 5s speech, 4s silence, 5s speech
        val silences = SilenceDetector.detect(env)
        assertEquals(1, silences.size)
        assertEquals(5_000L, silences[0].startMs)
        assertEquals(9_000L, silences[0].endMs)
    }

    @Test
    fun `short natural pauses are kept by default`() {
        val env = envelopeOf(30 to 0.3f, 3 to 0.0005f, 30 to 0.3f) // 0.3s pause
        val silences = SilenceDetector.detect(env) // min 600ms by default
        assertTrue(silences.isEmpty())
    }

    @Test
    fun `lowering the minimum finds short pauses too`() {
        val env = envelopeOf(30 to 0.3f, 3 to 0.0005f, 30 to 0.3f)
        val silences = SilenceDetector.detect(env, SilenceDetector.Config(minSilenceMs = 200))
        assertEquals(1, silences.size)
        assertEquals(3_000L, silences[0].startMs)
    }

    @Test
    fun `multiple pauses are all detected in order`() {
        val env = envelopeOf(
            20 to 0.3f,
            15 to 0.0005f,
            20 to 0.3f,
            20 to 0.0005f,
            20 to 0.3f,
        )
        val silences = SilenceDetector.detect(env)
        assertEquals(2, silences.size)
        assertTrue(silences[0].endMs <= silences[1].startMs)
    }

    @Test
    fun `empty envelope returns nothing`() {
        assertTrue(SilenceDetector.detect(emptyList()).isEmpty())
    }

    @Test
    fun `threshold follows the noise floor for quiet recordings`() {
        // Whole recording is quiet; threshold must adapt via the noise floor.
        val env = envelopeOf(20 to 0.002f, 20 to 0.0001f, 20 to 0.002f)
        val silences = SilenceDetector.detect(env)
        assertEquals(1, silences.size)
    }
}
