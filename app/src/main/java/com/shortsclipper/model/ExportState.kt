package com.shortsclipper.model

import kotlinx.serialization.Serializable

/**
 * Export quality preference. There is no lossless promise after re-encoding:
 * SAME_AS_ORIGINAL keeps original resolution/codec when the device encoder
 * supports it; HIGH_QUALITY caps at 1080p.
 */
@Serializable
enum class QualityMode {
    SAME_AS_ORIGINAL,
    HIGH_QUALITY,
}

enum class ExportPhase { IDLE, PREPARING, TRANSFORMING, SAVING, DONE, FAILED, CANCELLED }

/** Runtime export progress state (not persisted, not part of undo history). */
data class ExportState(
    val phase: ExportPhase = ExportPhase.IDLE,
    val progressPercent: Int = 0,
    val elapsedMs: Long = 0L,
    val message: String? = null,
    val outputUri: String? = null,
) {
    val isBusy: Boolean
        get() = phase == ExportPhase.PREPARING || phase == ExportPhase.TRANSFORMING || phase == ExportPhase.SAVING
}

enum class AnalysisPhase { IDLE, RUNNING, DONE, FAILED }

enum class AnalysisStep(val label: String) {
    PREPARE_AUDIO("Preparing audio"),
    TRANSCRIBE("Transcribing"),
    WORD_TIMESTAMPS("Generating word timestamps"),
    ANALYZE_CONTENT("Analyzing content"),
    FIND_MOMENTS("Finding candidate moments"),
    SCORE("Scoring candidates"),
    GENERATE("Generating clips"),
}

enum class StepState { PENDING, RUNNING, DONE, FAILED }

/** Runtime AI-analysis pipeline progress state. */
data class AnalysisState(
    val phase: AnalysisPhase = AnalysisPhase.IDLE,
    val stepStates: Map<AnalysisStep, StepState> = emptyMap(),
    val stepProgress: Map<AnalysisStep, Float> = emptyMap(),
    val activeStep: AnalysisStep? = null,
    val error: String? = null,
) {
    val isRunning: Boolean get() = phase == AnalysisPhase.RUNNING
}
