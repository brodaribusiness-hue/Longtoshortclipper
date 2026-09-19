package com.shortsclipper

import com.shortsclipper.ai.HighlightAnalyzer
import com.shortsclipper.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightAnalyzerTest {

    private fun words(vararg text: String, startMs: Long = 0, gap: Long = 300): List<Word> {
        var t = startMs
        return text.map { s ->
            val w = Word(s, t, t + 200, 0.95f)
            t += 200 + gap
            w
        }.toList()
    }

    @Test
    fun `splits sentences at punctuation`() {
        val w = words("This", "is", "fine.", "Another", "sentence", "here.")
        val sentences = HighlightAnalyzer.splitSentences(w)
        assertEquals(2, sentences.size)
        assertEquals("This is fine.", sentences[0].text)
        assertEquals("Another sentence here.", sentences[1].text)
    }

    @Test
    fun `splits sentences at long pauses`() {
        val first = listOf(Word("Wait", 0, 200, 1f), Word("for", 300, 400, 1f), Word("it.", 500, 600, 1f))
        val second = listOf(Word("Here", 2_500, 2_700, 1f), Word("we", 2_800, 2_900, 1f), Word("go.", 3_000, 3_100, 1f))
        val sentences = HighlightAnalyzer.splitSentences(first + second)
        assertEquals(2, sentences.size)
    }

    @Test
    fun `question sentences are detected`() {
        val sentence = HighlightAnalyzer.splitSentences(words("Why", "does", "this", "work?")).first()
        val signals = HighlightAnalyzer.signalsFor(sentence, listOf(sentence))
        assertTrue(signals.isQuestion)
    }

    @Test
    fun `contrast numbers and importance produce signals`() {
        val sentence = HighlightAnalyzer.splitSentences(words("But", "actually", "90", "percent", "of", "people", "miss", "the", "critical", "part.")).first()
        val signals = HighlightAnalyzer.signalsFor(sentence, listOf(sentence))
        assertTrue(signals.contrast > 0f)
        assertTrue(signals.hasNumbers)
        assertTrue(signals.importance > 0f)
    }

    @Test
    fun `density is normalized by words per second`() {
        // Gaps stay under the 800ms sentence-split threshold.
        val fast = HighlightAnalyzer.splitSentences(words("one", "two", "three", "four.", gap = 10)).first()
        val slow = HighlightAnalyzer.splitSentences(words("one", "two", "three", "four.", gap = 500)).first()
        assertTrue(HighlightAnalyzer.signalsFor(fast, listOf(fast)).density > HighlightAnalyzer.signalsFor(slow, listOf(slow)).density)
    }

    @Test
    fun `anchors found with separation in a long transcript`() {
        val text = listOf(
            "Here is the thing most people get wrong.",
            "Everyone believes practice makes perfect, right?",
            "But actually the opposite is true.",
            "Ninety percent of improvement comes from rest.",
            "This is the critical mistake beginners make.",
            "They train more and recover less.",
            "So remember that recovery drives growth.",
            "The secret is simple but uncomfortable.",
        )
        val allWords = text.flatMap { words(*it.split(" ").toTypedArray()) }
        val sentences = HighlightAnalyzer.splitSentences(allWords)
        val anchors = HighlightAnalyzer.findAnchors(sentences)
        assertTrue("expected anchors", anchors.isNotEmpty())
        // Separation: anchors must not sit on adjacent sentences (NMS).
        val sorted = anchors.map { it.sentenceIndex }.sorted()
        assertTrue(sorted.zipWithNext().all { (a, b) -> b - a >= 5 })
    }

    @Test
    fun `empty transcript yields no anchors`() {
        assertTrue(HighlightAnalyzer.splitSentences(emptyList()).isEmpty())
        assertTrue(HighlightAnalyzer.findAnchors(emptyList()).isEmpty())
    }
}
