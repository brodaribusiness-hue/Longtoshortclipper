package com.shortsclipper.model

import kotlinx.serialization.Serializable

/**
 * Export quality preference. There is no lossless promise after re-encoding:
 * SAME_AS_ORIGINAL preserves as much source display resolution as the
 * capability-checked 9:16 encoder supports; HIGH_QUALITY caps practical
 * output detail. AVC is preferred for broad playback compatibility.
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
