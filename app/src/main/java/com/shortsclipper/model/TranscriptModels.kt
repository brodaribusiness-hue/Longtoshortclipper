package com.shortsclipper.model

import kotlinx.serialization.Serializable

/** A single transcribed word with local word-level timestamps. */
@Serializable
data class Word(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    /** Average token probability 0..1 where available. */
    val confidence: Float,
)

/** A Whisper transcription segment (sentence-ish unit). */
@Serializable
data class Segment(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

/**
 * Full transcript for the analyzed (selected) audio range.
 * Word timestamps power the clip analysis engine.
 */
@Serializable
data class Transcript(
    val language: String,
    val words: List<Word>,
    val segments: List<Segment>,
) {
    val startMs: Long get() = words.firstOrNull()?.startTimeMs ?: 0L
    val endMs: Long get() = words.lastOrNull()?.endTimeMs ?: 0L

    fun wordsBetween(startMs: Long, endMs: Long): List<Word> =
        words.filter { it.endTimeMs > startMs && it.startTimeMs < endMs }
}
