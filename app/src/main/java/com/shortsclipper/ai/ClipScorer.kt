package com.shortsclipper.ai

import com.shortsclipper.model.ScoreComponents
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Transparent candidate scoring. Produces the Content Potential Score
 * (0..10) from five readable components. This is an internal ranking signal
 * for ordering candidates - NOT a viral or engagement guarantee.
 */
object ClipScorer {

    private val LEADING_CONJUNCTIONS = setOf("and", "but", "so", "because", "which", "that", "also", "then", "or")

    data class Scored(
        val components: ScoreComponents,
        val potential: Float,
        val reasons: List<String>,
    )

    fun score(
        sentences: List<HighlightAnalyzer.Sentence>,
        startIndex: Int,
        endIndex: Int,
        targetDurationSecs: Int,
    ): Scored {
        if (sentences.isEmpty()) {
            return Scored(ScoreComponents(0f, 0f, 0f, 0f, 0f), 0f, emptyList())
        }
        val sIdx = startIndex.coerceIn(0, sentences.size - 1)
        val eIdx = endIndex.coerceIn(sIdx, sentences.size - 1)
        val clip = sentences.subList(sIdx, eIdx + 1)
        val first = clip.first()
        val last = clip.last()
        val firstSignals = HighlightAnalyzer.signalsFor(first, sentences)
        val durationMs = (last.endMs - first.startMs).coerceAtLeast(1L)
        val wordCount = clip.sumOf { it.wordCount }
        val wps = wordCount * 1000f / durationMs.coerceAtLeast(1L)

        // Hook: strength of the opening moment.
        var hook = 0.3f
        if (firstSignals.isQuestion) hook += 0.5f
        if (firstSignals.strongOpening) hook += 0.3f
        hook += firstSignals.curiosity * 0.3f
        hook += firstSignals.density * 0.2f
        val hook10 = (hook.coerceIn(0f, 1f) * 10f)

        // Context: does the clip stand on its own?
        val firstTokens = first.text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        val leadingConjunction = firstTokens.firstOrNull() in LEADING_CONJUNCTIONS
        var context = 0.7f
        if (firstSignals.gapBeforeMs >= 400) context += 0.15f
        if (!leadingConjunction) context += 0.15f else context -= 0.3f
        val context10 = (context.coerceIn(0f, 1f) * 10f)

        // Engagement signals.
        val engagementRaw = 0.3f * firstSignals.secondPerson +
            0.25f * (if (firstSignals.hasNumbers) 1f else 0f) +
            0.25f * firstSignals.curiosity +
            0.20f * clip.maxOf { HighlightAnalyzer.signalsFor(it, sentences).secondPerson }
        val engagement10 = (engagementRaw.coerceIn(0f, 1f) * 10f)

        // Completeness: full sentences, conclusion present, sensible duration.
        val lastSignals = HighlightAnalyzer.signalsFor(last, sentences)
        val durationRatio = durationMs.toFloat() / (targetDurationSecs * 1000f)
        val durationFit = (1f - kotlin.math.abs(1f - durationRatio) * 0.8f).coerceIn(0f, 1f)
        val completenessRaw = 0.5f * durationFit +
            0.2f * (if (lastSignals.conclusion > 0f) 1f else 0.5f) +
            0.15f * (if (firstSignals.gapBeforeMs >= 400) 1f else 0.6f) +
            0.15f * min(1f, wps / 2f)
        val completeness10 = (completenessRaw.coerceIn(0f, 1f) * 10f)

        // Information density (2.8+ words/sec saturates the scale).
        val density10 = (min(1f, wps / 2.8f) * 10f)

        val potential = (0.25f * hook10 + 0.15f * context10 + 0.20f * engagement10 +
            0.25f * completeness10 + 0.15f * density10) / 10f
        val potential10 = (potential * 10f).roundToInt() / 10f

        val reasons = ArrayList<String>()
        if (firstSignals.isQuestion) reasons.add("Starts with a question")
        if (firstSignals.strongOpening) reasons.add("Strong opening statement")
        if (firstSignals.curiosity > 0f) reasons.add("Curiosity-inducing wording")
        if (firstSignals.contrast > 0f) reasons.add("Contrast / expectation reversal")
        if (firstSignals.hasNumbers) reasons.add("Contains concrete numbers")
        if (firstSignals.secondPerson > 0f) reasons.add("Speaks directly to the viewer")
        if (lastSignals.conclusion > 0f) reasons.add("Reaches a conclusion")
        if (firstSignals.gapBeforeMs >= 400) reasons.add("Clean sentence start")
        if (density10 >= 6f) reasons.add("High information density")
        if (reasons.isEmpty()) reasons.add("Balanced segment")

        return Scored(
            components = ScoreComponents(
                hook = (hook10 * 10).roundToInt() / 10f,
                context = (context10 * 10).roundToInt() / 10f,
                engagement = (engagement10 * 10).roundToInt() / 10f,
                completeness = (completeness10 * 10).roundToInt() / 10f,
                density = (density10 * 10).roundToInt() / 10f,
            ),
            potential = potential10,
            reasons = reasons.take(3),
        )
    }
}
