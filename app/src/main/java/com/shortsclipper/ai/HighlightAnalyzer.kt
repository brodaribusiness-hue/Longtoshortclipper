package com.shortsclipper.ai

import com.shortsclipper.model.Transcript
import com.shortsclipper.model.Word
import kotlin.math.abs
import kotlin.math.min

/**
 * Deterministic, local content analysis over the word-level transcript.
 * Produces "anchors": candidate interesting moments with transparent text
 * signals (questions, contrast, importance, curiosity, density, conclusions).
 * This is heuristic content analysis - not emotion recognition and not a
 * virality prediction.
 */
object HighlightAnalyzer {

    data class Sentence(
        val index: Int,
        val words: List<Word>,
        val startMs: Long,
        val endMs: Long,
    ) {
        val text: String get() = words.joinToString(" ") { it.text }
        val wordCount: Int get() = words.size
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1)
        val wordsPerSecond: Float get() = wordCount * 1000f / durationMs
    }

    data class Signals(
        val isQuestion: Boolean,
        val hasNumbers: Boolean,
        val contrast: Float,
        val importance: Float,
        val curiosity: Float,
        val conclusion: Float,
        val strongOpening: Boolean,
        val secondPerson: Float,
        val density: Float,
        val gapBeforeMs: Long,
    )

    data class Anchor(
        val sentenceIndex: Int,
        val score: Float,
        val signals: Signals,
    )

    private val QUESTION_STARTERS = setOf(
        "who", "what", "when", "where", "why", "how", "is", "are", "do", "does",
        "did", "can", "could", "would", "will", "should", "have", "has", "was", "were",
    )
    private val CONTRAST = setOf(
        "but", "however", "instead", "although", "though", "yet", "nevertheless",
        "actually", "whereas", "contrary", "despite", "unless", "except",
    )
    private val IMPORTANCE = setOf(
        "important", "key", "critical", "essential", "crucial", "biggest", "most",
        "best", "worst", "never", "always", "remember", "secret", "powerful", "fundamental",
    )
    private val CURIOSITY = setOf(
        "secret", "nobody", "everyone", "surprise", "surprising", "shocking", "truth",
        "myth", "mistake", "wrong", "reason", "imagine", "honestly", "literally",
        "crazy", "insane", "weird", "funny", "believe",
    )
    private val CONCLUSION = setOf(
        "so", "therefore", "conclusion", "ultimately", "summary", "result",
        "finally", "bottom", "that's", "thus", "hence",
    )
    private val STRONG_OPENINGS = setOf(
        "here's", "here", "listen", "imagine", "stop", "never", "look", "watch",
        "this", "the", "your", "you", "i", "my",
    )
    private val SECOND_PERSON = setOf("you", "your", "yourself", "you're", "you've", "you'll")

    private val SENTENCE_ENDERS = setOf(".", "!", "?", "?!", "!?", "…")
    private val MAX_SENTENCE_WORDS = 40
    private val SENTENCE_GAP_MS = 800L

    /** Splits a word stream into sentence-like units at punctuation or pauses. */
    fun splitSentences(words: List<Word>): List<Sentence> {
        val sentences = ArrayList<Sentence>()
        var current = ArrayList<Word>()
        var lastEndMs = -1L

        fun flush() {
            if (current.isNotEmpty()) {
                sentences.add(
                    Sentence(
                        index = sentences.size,
                        words = current.toList(),
                        startMs = current.first().startTimeMs,
                        endMs = current.last().endTimeMs,
                    )
                )
                current = ArrayList()
            }
        }

        for (word in words) {
            val gap = if (lastEndMs >= 0) word.startTimeMs - lastEndMs else 0L
            if (gap > SENTENCE_GAP_MS) flush()
            current.add(word)
            val trimmed = word.text.trim()
            val endsSentence = trimmed.isNotEmpty() && ".!?…".contains(trimmed.last())
            if ((endsSentence && current.size >= 3) || current.size >= MAX_SENTENCE_WORDS) {
                flush()
            }
            lastEndMs = word.endTimeMs
        }
        flush()
        // Re-index defensively.
        return sentences.mapIndexed { i, s -> s.copy(index = i) }
    }

    /** Textual signals for one sentence. */
    fun signalsFor(sentence: Sentence, allSentences: List<Sentence>): Signals {
        val lower = sentence.text.lowercase()
        val tokens = lower.split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
        val first = tokens.firstOrNull() ?: ""

        val isQuestion = sentence.text.trim().endsWith("?") || (first in QUESTION_STARTERS && lower.contains("?"))
        val questionStarter = first in QUESTION_STARTERS

        fun markerCount(set: Set<String>): Int = tokens.count { it in set }
        val contrast = min(1f, markerCount(CONTRAST) / 2f)
        val importance = min(1f, markerCount(IMPORTANCE) / 2f)
        val curiosity = min(1f, markerCount(CURIOSITY) / 2f)
        val conclusion = min(1f, markerCount(CONCLUSION) / 2f)
        val secondPerson = min(1f, markerCount(SECOND_PERSON) / 3f)
        val hasNumbers = tokens.any { it.any { c -> c.isDigit() } } || tokens.contains("percent") || tokens.contains("million") ||
            tokens.contains("billion") || tokens.contains("thousand") || tokens.contains("%")

        val strongOpening = sentence.index == 0 || sentence.startMs <= 3000L

        val gapBefore = if (sentence.index == 0) 0L else sentence.startMs - allSentences[sentence.index - 1].endMs
        val density = min(1f, sentence.wordsPerSecond / 3.5f)

        return Signals(
            isQuestion = isQuestion || (questionStarter && sentence.wordCount in 3..25),
            hasNumbers = hasNumbers,
            contrast = contrast,
            importance = importance,
            curiosity = curiosity,
            conclusion = conclusion,
            strongOpening = strongOpening && (first in STRONG_OPENINGS || questionStarter),
            secondPerson = secondPerson,
            density = density,
            gapBeforeMs = gapBefore,
        )
    }

    /** Weighted base score of a sentence as an interesting moment. */
    fun baseScore(signals: Signals): Float =
        0.22f * maxOf(if (signals.isQuestion) 1f else 0f, if (signals.strongOpening) 0.9f else 0f, signals.curiosity * 0.8f) +
            0.20f * signals.importance +
            0.13f * signals.contrast +
            0.13f * signals.density +
            0.10f * (if (signals.hasNumbers) 1f else 0f) +
            0.12f * signals.secondPerson +
            0.10f * signals.conclusion

    /**
     * Finds anchor sentences: local score peaks (with neighbor smoothing),
     * filtered by threshold and separated by NMS so they seed distinct clips.
     */
    fun findAnchors(sentences: List<Sentence>, maxAnchors: Int = 10): List<Anchor> {
        if (sentences.isEmpty()) return emptyList()
        val signals = sentences.map { signalsFor(it, sentences) }
        val scores = signals.map { baseScore(it) }

        val anchors = ArrayList<Anchor>()
        for (i in scores.indices) {
            val neighborAvg = scores
                .slice((i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(scores.size - 1))
                .average().toFloat()
            val previousScore = scores.getOrNull(i - 1) ?: Float.NEGATIVE_INFINITY
            val combined = scores[i] * 0.7f + neighborAvg * 0.3f
            val isLocalPeak = scores[i] + 0.001f >= previousScore
            if (isLocalPeak && combined >= 0.32f) {
                anchors.add(Anchor(i, combined, signals[i]))
            }
        }

        // Non-maximum suppression by sentence distance.
        val separated = ArrayList<Anchor>()
        val minSeparation = 5
        for (anchor in anchors.sortedByDescending { it.score }) {
            if (separated.none { abs(it.sentenceIndex - anchor.sentenceIndex) < minSeparation }) {
                separated.add(anchor)
                if (separated.size >= maxAnchors) break
            }
        }
        return separated
    }
}
