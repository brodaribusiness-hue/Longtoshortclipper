package com.shortsclipper

import com.shortsclipper.ai.ClipGenerator
import com.shortsclipper.ai.ClipScorer
import com.shortsclipper.ai.HighlightAnalyzer
import com.shortsclipper.model.TargetDuration
import com.shortsclipper.model.Transcript
import com.shortsclipper.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipGenerationTest {

    private fun syntheticTranscript(sentenceCount: Int, gapMs: Long = 320): Transcript {
        val texts = mutableListOf<String>()
        for (i in 0 until sentenceCount) {
            texts.add(
                when (i % 4) {
                    0 -> "Here is the surprising truth about habit number ${i + 1}."
                    1 -> "Why do most people fail at this critical step?"
                    2 -> "But actually ninety percent of progress comes from the boring basics."
                    else -> "So remember the key point and you will see results quickly."
                }
            )
        }
        var t = 0L
        val words = ArrayList<Word>()
        for (sentence in texts) {
            for (token in sentence.split(" ")) {
                words.add(Word(token, t, t + 180, 0.95f))
                t += 200
            }
            t += gapMs
        }
        return Transcript("en", words, emptyList())
    }

    @Test
    fun `short transcript still yields a candidate without crashing`() {
        val transcript = syntheticTranscript(2)
        val candidates = ClipGenerator.generate(transcript, 20_000, TargetDuration.T15)
        // Very short content: either one small candidate or none - but never a crash.
        assertTrue(candidates.size <= 1)
        candidates.forEach { assertTrue(it.endMs > it.startMs) }
    }

    @Test
    fun `long transcript yields multiple distinct candidates`() {
        val transcript = syntheticTranscript(24)
        val durationMs = 24L * 8 * 200 + 24 * 320 // ~45s of content
        val candidates = ClipGenerator.generate(transcript, durationMs, TargetDuration.T15)
        assertTrue("expected several candidates, got ${candidates.size}", candidates.size in 2..8)
        // No near duplicates.
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                assertTrue(
                    "candidates $i and $j overlap too much",
                    ClipGenerator.iou(candidates[i], candidates[j]) <= IOU_LIMIT,
                )
            }
        }
        // Sorted and within the video bounds.
        assertEquals(candidates.map { it.startMs }, candidates.map { it.startMs }.sorted())
        assertTrue(candidates.all { it.startMs >= 0 })
        assertTrue(candidates.all { it.endMs <= durationMs })
    }

    @Test
    fun `candidate duration approximates the target`() {
        val transcript = syntheticTranscript(40, gapMs = 350)
        val durationMs = 40L * 8 * 200 + 40 * 350
        val candidates = ClipGenerator.generate(transcript, durationMs, TargetDuration.T30)
        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            val ratio = candidate.durationMs / 30_000f
            assertTrue("duration ${candidate.durationMs} outside tolerance", ratio in 0.5f..1.4f)
        }
    }

    @Test
    fun `auto target picks sensible durations`() {
        val transcript = syntheticTranscript(30)
        val durationMs = 30L * 8 * 200 + 30 * 320
        val candidates = ClipGenerator.generate(transcript, durationMs, TargetDuration.AUTO)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.durationMs in 10_000..80_000 })
    }

    @Test
    fun `rejected empty and zero duration edge cases`() {
        val empty = Transcript("en", emptyList(), emptyList())
        assertEquals(0, ClipGenerator.generate(empty, 100_000, TargetDuration.T30).size)
        assertEquals(0, ClipGenerator.generate(syntheticTranscript(5), 0, TargetDuration.T30).size)
    }

    @Test
    fun `scoring is transparent and bounded`() {
        val transcript = syntheticTranscript(12)
        val sentences = HighlightAnalyzer.splitSentences(transcript.words)
        val scored = ClipScorer.score(sentences, 0, 3, 30)
        assertTrue(scored.potential in 0f..10f)
        assertTrue(scored.components.hook in 0f..10f)
        assertTrue(scored.components.context in 0f..10f)
        assertTrue(scored.components.engagement in 0f..10f)
        assertTrue(scored.components.completeness in 0f..10f)
        assertTrue(scored.components.density in 0f..10f)
        assertTrue(scored.reasons.isNotEmpty())
    }

    @Test
    fun `stronger content scores higher`() {
        val strong = syntheticTranscript(12) // questions + numbers + contrast
        val flatSentences = (0 until 12).joinToString(" ") { "word$it filler text here." }
        var t = 0L
        val flatWords = flatSentences.split(" ").map {
            val w = Word(it, t, t + 180, 0.9f)
            t += 250
            w
        }
        val flat = Transcript("en", flatWords, emptyList())

        val strongSentences = HighlightAnalyzer.splitSentences(strong.words)
        val flatSentencesList = HighlightAnalyzer.splitSentences(flat.words)
        val strongScore = ClipScorer.score(strongSentences, 0, 3, 30).potential
        val flatScore = ClipScorer.score(flatSentencesList, 0, 3, 30).potential
        assertTrue("strong $strongScore should beat flat $flatScore", strongScore >= flatScore)
    }

    companion object {
        const val IOU_LIMIT = 0.36f
    }
}
