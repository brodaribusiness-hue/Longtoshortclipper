package com.shortsclipper

import com.shortsclipper.ai.WordAssembler
import com.shortsclipper.ai.TranscriptionEngine
import com.shortsclipper.model.Transcript
import com.shortsclipper.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionTest {

    private fun token(text: String, s: Long, e: Long, p: Float = 0.9f) = WordAssembler.Token(text, s, e, p)

    @Test
    fun `tokens group into words on space prefix`() {
        val tokens = listOf(
            token("▁This", 0, 200),
            token("▁is", 220, 300),
            token("a", 310, 350),
            token("▁test", 360, 600),
        )
        val words = WordAssembler.assemble(tokens)
        assertEquals(listOf("This", "is", "a", "test"), words.map { it.text })
        assertEquals(0L, words[0].startTimeMs)
        assertEquals(600L, words[3].endTimeMs)
    }

    @Test
    fun `punctuation attaches to the previous word`() {
        val tokens = listOf(
            token("▁Hello", 0, 300),
            token(",", 300, 320),
            token("▁world", 400, 700),
            token(".", 700, 720),
        )
        val words = WordAssembler.assemble(tokens)
        assertEquals(listOf("Hello,", "world."), words.map { it.text })
    }

    @Test
    fun `special and blank tokens are skipped`() {
        val tokens = listOf(
            token("[_BRIEF_NOISE_]", 0, 10),
            token("", 10, 20),
            token("▁Word", 100, 300),
        )
        val words = WordAssembler.assemble(tokens)
        assertEquals(listOf("Word"), words.map { it.text })
    }

    @Test
    fun `confidence is averaged over word tokens`() {
        val words = WordAssembler.assemble(
            listOf(
                token("▁test", 0, 100, p = 0.5f),
                token("ing", 100, 200, p = 1.0f),
            )
        )
        assertEquals(0.75f, words[0].confidence, 0.001f)
    }

    @Test
    fun `overlapping windows do not duplicate words`() {
        val windowWords = listOf(
            Word("keep", 0, 300, 0.9f),
            Word("duplicate", 1_000, 1_400, 0.9f), // inside overlap zone
            Word("new", 2_100, 2_400, 0.9f), // after overlap
        )
        val kept = WordAssembler.wordsForWindow(windowWords, windowStartMs = 500, overlapMs = 1_500, isFirstWindow = false)
        assertEquals(listOf("new"), kept.map { it.text })
        val first = WordAssembler.wordsForWindow(windowWords, 0, 1_500, isFirstWindow = true)
        assertEquals(3, first.size)
    }

    @Test
    fun `parses native whisper JSON transcript`() {
        val json = """
            {"language":"en","segments":[
              {"s":100,"e":900,"text":" This is fine.","tokens":[
                 {"x":"▁This","s":100,"e":200,"p":0.95},
                 {"x":"▁is","s":210,"e":260,"p":0.93},
                 {"x":"▁fine","s":270,"e":400,"p":0.91},
                 {"x":".","s":400,"e":420,"p":0.99}
              ]}
            ]}
        """.trimIndent()
        val (segments, language) = TranscriptionEngine().parse(json)
        assertEquals("en", language)
        assertEquals(1, segments.size)
        assertEquals(4, segments[0].tokens.size)
        assertEquals(100L, segments[0].startMs)
        assertEquals(" This is fine.", segments[0].text)

        val words = WordAssembler.assemble(segments[0].tokens)
        assertEquals(listOf("This", "is", "fine."), words.map { it.text })
        assertTrue(words.all { it.confidence > 0.5f })
    }

    @Test
    fun `transcript word range queries work`() {
        val transcript = Transcript(
            language = "en",
            words = listOf(
                Word("one", 0, 100, 1f),
                Word("two", 1_000, 1_100, 1f),
                Word("three", 2_000, 2_100, 1f),
            ),
            segments = emptyList(),
        )
        assertEquals(2, transcript.wordsBetween(500, 2_050).size)
        assertEquals(0, transcript.wordsBetween(3_000, 4_000).size)
    }
}
