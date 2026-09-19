package com.shortsclipper.ai

import com.shortsclipper.model.ClipCandidate
import com.shortsclipper.model.TargetDuration
import com.shortsclipper.model.Transcript
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Expands highlight anchors into candidate clips on sentence/word boundaries,
 * scores them and suppresses near-duplicates.
 */
object ClipGenerator {

    private const val MAX_CANDIDATES = 8
    private const val IOU_SUPPRESSION = 0.35f
    private const val START_PAD_MS = 120L
    private const val END_PAD_MS = 200L

    fun generate(
        transcript: Transcript,
        videoDurationMs: Long,
        target: TargetDuration,
    ): List<ClipCandidate> {
        if (videoDurationMs <= 0L) return emptyList()
        val sentences = HighlightAnalyzer.splitSentences(transcript.words)
        if (sentences.isEmpty()) return emptyList()

        val anchors = HighlightAnalyzer.findAnchors(sentences)
        if (anchors.isEmpty()) {
            // No strong signal: fall back to the densest sentences as anchors.
            val densest = sentences.sortedByDescending { it.wordsPerSecond }
                .take(3)
                .map { HighlightAnalyzer.Anchor(it.index, 0.32f, HighlightAnalyzer.signalsFor(it, sentences)) }
            if (densest.isEmpty()) return emptyList()
            return buildCandidates(sentences, densest, videoDurationMs, target, transcript)
        }
        return buildCandidates(sentences, anchors, videoDurationMs, target, transcript)
    }

    private fun buildCandidates(
        sentences: List<HighlightAnalyzer.Sentence>,
        anchors: List<HighlightAnalyzer.Anchor>,
        videoDurationMs: Long,
        target: TargetDuration,
        transcript: Transcript,
    ): List<ClipCandidate> {
        val raw = ArrayList<ClipCandidate>()
        for (anchor in anchors) {
            val targetSecs = if (target == TargetDuration.AUTO) {
                autoTargetFor(anchor)
            } else {
                target.seconds
            }
            val candidate = buildOne(sentences, anchor, targetSecs, videoDurationMs, transcript, raw.size)
            raw.add(candidate)
        }

        // Overlap suppression: keep the higher-scored clip of overlapping pairs.
        val kept = ArrayList<ClipCandidate>()
        for (candidate in raw.sortedByDescending { it.potential }) {
            if (kept.none { iou(it, candidate) > IOU_SUPPRESSION }) {
                kept.add(candidate)
                if (kept.size >= MAX_CANDIDATES) break
            }
        }
        return kept.sortedBy { it.startMs }
    }

    private fun autoTargetFor(anchor: HighlightAnalyzer.Anchor): Int {
        // Choose target from local content run length.
        return when {
            anchor.score >= 0.55f -> 60
            anchor.score >= 0.45f -> 30
            else -> 15
        }
    }

    private fun buildOne(
        sentences: List<HighlightAnalyzer.Sentence>,
        anchor: HighlightAnalyzer.Anchor,
        targetSecs: Int,
        videoDurationMs: Long,
        transcript: Transcript,
        index: Int,
    ): ClipCandidate {
        val targetMs = targetSecs * 1000L
        val minMs = (targetMs * 0.6f).toLong()
        val maxMs = (targetMs * 1.25f).toLong()

        val anchorSentence = sentences[anchor.sentenceIndex]
        var startIdx = anchor.sentenceIndex
        var endIdx = anchor.sentenceIndex

        // Span measured against the video-bounded end so candidates can never
        // silently shrink when transcript timing drifts past the container duration.
        fun spanMs(): Long = min(videoDurationMs, sentences[endIdx].endMs) - sentences[startIdx].startMs

        // Include one preceding sentence when it is tightly connected (context).
        val prev = sentences.getOrNull(startIdx - 1)
        if (prev != null && anchorSentence.startMs - prev.endMs <= 700L) {
            startIdx -= 1
        }

        // Extend forward to fill the target duration (never cutting mid-sentence).
        while (endIdx < sentences.size - 1 && spanMs() < minMs) {
            endIdx++
        }
        // Extend backward if still under 60% and there is room.
        while (startIdx > 0 && spanMs() < minMs) {
            startIdx--
        }
        // Trim forward if overshooting the max.
        while (endIdx > startIdx && spanMs() > maxMs) {
            endIdx--
        }

        val startMs = max(0L, sentences[startIdx].startMs - START_PAD_MS)
        val endMs = min(videoDurationMs, sentences[endIdx].endMs + END_PAD_MS)
        val scored = ClipScorer.score(sentences, startIdx, endIdx, targetSecs)

        val title = sentences[anchor.sentenceIndex].text
            .take(48)
            .trim()
            .ifEmpty { "Clip ${index + 1}" }

        return ClipCandidate(
            id = "cand_${startMs}_${endMs}",
            startMs = startMs,
            endMs = endMs,
            durationMs = endMs - startMs,
            score = scored.components,
            potential = scored.potential,
            reasons = scored.reasons,
            title = if (title.length == 48) "$title…" else title,
        )
    }

    fun iou(a: ClipCandidate, b: ClipCandidate): Float {
        val interStart = max(a.startMs, b.startMs)
        val interEnd = min(a.endMs, b.endMs)
        val inter = (interEnd - interStart).coerceAtLeast(0)
        val union = max(a.endMs, b.endMs) - min(a.startMs, b.startMs)
        if (union <= 0) return 0f
        return inter.toFloat() / union
    }
}
