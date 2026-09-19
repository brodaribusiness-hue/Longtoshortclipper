package com.shortsclipper.model

import kotlinx.serialization.Serializable

/**
 * Tracking smoothing strength. Larger windows remove more jitter but react
 * slower to fast motion. Default: BALANCED.
 */
@Serializable
enum class SmoothingPreset(val windowSamples: Int) {
    RESPONSIVE(3),
    BALANCED(7),
    SMOOTH(15),
}

/** A face detection result in normalized frame coordinates (0..1, top-left origin). */
@Serializable
data class FaceBox(
    val faceId: Int,
    val xFrac: Float,
    val yFrac: Float,
    val wFrac: Float,
    val hFrac: Float,
    val timeMs: Long,
) {
    val centerX: Float get() = xFrac + wFrac / 2f
    val centerY: Float get() = yFrac + hFrac / 2f
}

/** One point of the automatic tracking path (already associated to one target). */
@Serializable
data class FacePoint(
    val timeMs: Long,
    val xFrac: Float,
    val yFrac: Float,
    val wFrac: Float,
)

/** A manual correction keyframe for the 9:16 crop window. */
@Serializable
data class TransformKeyframe(
    val timeMs: Long,
    val centerXFrac: Float,
    val centerYFrac: Float,
    val zoom: Float,
)

/**
 * Tracking editor state. AUTO_FACE + manual keyframes = hybrid tracking:
 * the automatic path is blended towards nearby manual keyframes.
 */
@Serializable
data class TrackingState(
    val autoEnabled: Boolean = false,
    val targetFaceId: Int? = null,
    val smoothing: SmoothingPreset = SmoothingPreset.BALANCED,
    /** Faces from the selection frame, offered to the user for target selection. */
    val detectedFaces: List<FaceBox> = emptyList(),
    /** Smoothed automatic path of the selected target face. */
    val autoPath: List<FacePoint> = emptyList(),
    val manualKeyframes: List<TransformKeyframe> = emptyList(),
) {
    val hasManualKeyframes: Boolean get() = manualKeyframes.isNotEmpty()
}
