package com.shortsclipper.video

import com.shortsclipper.model.ProjectState
import com.shortsclipper.tracking.TrackingEngine
import kotlin.math.max
import kotlin.math.min

/**
 * A normalized 9:16 crop window (fractions of the display frame, top-left origin).
 * This is the single source of truth for the transformation: preview rendering
 * and the export vertex matrix are both derived from it.
 */
data class CropRect(
    val centerX: Float,
    val centerY: Float,
    val widthFrac: Float,
    val heightFrac: Float,
)

/** Raw crop target (center + zoom) produced by the tracking engine. */
data class CropTarget(val centerX: Float, val centerY: Float, val zoom: Float)

/** graphicsLayer parameters used to render the exact 9:16 crop in the preview. */
data class PreviewTransform(
    val scale: Float,
    val translationXPx: Float,
    val translationYPx: Float,
)

/**
 * Shared transformation math used by BOTH the live preview and the exporter,
 * guaranteeing that what the user sees is what gets exported.
 *
 * Export chain: MatrixTransformation (content crop -> visible NDC region)
 * followed by Presentation(outW x outH, SCALE_TO_FIT_WITH_CROP), which crops
 * the effect canvas down to the 9:16 output.
 */
object CropCalculator {

    const val TARGET_ASPECT = 9f / 16f
    const val MAX_ZOOM = 4f

    /** Largest 9:16 rectangle that fits the display frame, in pixels. */
    fun baseRectSize(displayW: Int, displayH: Int): Pair<Float, Float> {
        require(displayW > 0 && displayH > 0) { "Display dimensions must be positive" }
        val frameAspect = displayW.toFloat() / displayH
        return if (frameAspect >= TARGET_ASPECT) {
            Pair(displayH * TARGET_ASPECT, displayH.toFloat())
        } else {
            Pair(displayW.toFloat(), displayW / TARGET_ASPECT)
        }
    }

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(1f, MAX_ZOOM)

    /** Keeps the crop window fully inside the frame. */
    fun clampCenter(centerX: Float, centerY: Float, zoom: Float, displayW: Int, displayH: Int): CropTarget {
        val (baseW, baseH) = baseRectSize(displayW, displayH)
        val halfXFrac = (baseW / zoom / 2f) / displayW
        val halfYFrac = (baseH / zoom / 2f) / displayH
        val x = if (halfXFrac * 2f >= 1f) 0.5f else centerX.coerceIn(halfXFrac, 1f - halfXFrac)
        val y = if (halfYFrac * 2f >= 1f) 0.5f else centerY.coerceIn(halfYFrac, 1f - halfYFrac)
        return CropTarget(x, y, clampZoom(zoom))
    }

    /** Evaluates tracking at a source time and returns the final clamped crop rect. */
    fun rectAt(state: ProjectState, sourceTimeMs: Long): CropRect {
        val source = state.source ?: return CropRect(0.5f, 0.5f, 1f, 1f)
        val raw = TrackingEngine.evaluateAt(state.tracking, sourceTimeMs)
        val clamped = clampCenter(raw.centerX, raw.centerY, raw.zoom, source.displayWidth, source.displayHeight)
        return rectFor(clamped, source.displayWidth, source.displayHeight)
    }

    fun rectFor(target: CropTarget, displayW: Int, displayH: Int): CropRect {
        val (baseW, baseH) = baseRectSize(displayW, displayH)
        val cropW = baseW / target.zoom
        val cropH = baseH / target.zoom
        return CropRect(target.centerX, target.centerY, cropW / displayW, cropH / displayH)
    }

    /**
     * Vertex matrix for Media3's MatrixTransformation (normalized device
     * coordinates, y up). Maps the crop rect onto the NDC region that survives
     * the following Presentation SCALE_TO_FIT_WITH_CROP, so the output frame
     * contains exactly the crop rect content.
     */
    fun toVertexMatrix(rect: CropRect, displayW: Int, displayH: Int, outW: Int, outH: Int): FloatArray {
        val s = max(outW.toFloat() / displayW, outH.toFloat() / displayH)
        val visibleX = outW / (s * displayW) // visible NDC half-extent on x
        val visibleY = outH / (s * displayH)
        val centerNdcX = 2f * rect.centerX - 1f
        val centerNdcY = 1f - 2f * rect.centerY
        val sx = visibleX / rect.widthFrac
        val sy = visibleY / rect.heightFrac
        return floatArrayOf(
            sx, 0f, 0f, 0f,
            0f, sy, 0f, 0f,
            0f, 0f, 1f, 0f,
            -centerNdcX * sx, -centerNdcY * sy, 0f, 1f,
        )
    }

    /**
     * graphicsLayer transform that renders the identical crop inside a fixed
     * 9:16 preview box: the fitted video is scaled so the crop rect exactly
     * fills the box, then translated so the crop center lands in the middle.
     */
    fun previewTransform(
        rect: CropRect,
        boxWidthPx: Float,
        boxHeightPx: Float,
        displayW: Int,
        displayH: Int,
    ): PreviewTransform {
        val fitScale = min(boxWidthPx / displayW, boxHeightPx / displayH)
        val contentW = displayW * fitScale
        val contentH = displayH * fitScale
        val scale = boxWidthPx / (rect.widthFrac * contentW)
        val dx = (rect.centerX - 0.5f) * contentW
        val dy = (rect.centerY - 0.5f) * contentH
        return PreviewTransform(scale, -dx * scale, -dy * scale)
    }
}
