package com.shortsclipper.model

import kotlinx.serialization.Serializable

/** Candidate clip target durations. AUTO derives a suitable target per moment. */
@Serializable
enum class TargetDuration(val seconds: Int) {
    T15(15),
    T30(30),
    T60(60),
    T90(90),
    AUTO(0),
}

/**
 * Transparent score components (each 0..10). The weighted combination is the
 * "Content Potential Score" - an internal content-quality ranking signal.
 * This is NOT a viral/engagement guarantee.
 */
@Serializable
data class ScoreComponents(
    val hook: Float,
    val context: Float,
    val engagement: Float,
    val completeness: Float,
    val density: Float,
)

/** A generated candidate short clip. */
@Serializable
data class ClipCandidate(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val score: ScoreComponents,
    /** Weighted Content Potential Score 0..10 (one decimal). */
    val potential: Float,
    /** Human-readable signals that led to this candidate. */
    val reasons: List<String>,
    val title: String,
)
