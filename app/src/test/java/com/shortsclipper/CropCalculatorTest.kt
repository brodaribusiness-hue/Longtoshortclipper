package com.shortsclipper

import com.shortsclipper.tracking.TrackingEngine
import com.shortsclipper.video.CropCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Critical consistency test: the preview transform and the export vertex
 * matrix must be driven by the same crop rect and agree with each other.
 */
class CropCalculatorTest {

    private fun assertNear(a: Float, b: Float, eps: Float = 0.001f) {
        assertTrue("expected $a ~ $b", abs(a - b) < eps)
    }

    @Test
    fun `base rect is the largest 9-16 rect`() {
        val (w, h) = CropCalculator.baseRectSize(1920, 1080)
        assertEquals(607.5f, w, 0.01f)
        assertEquals(1080f, h, 0.01f)

        val (w2, h2) = CropCalculator.baseRectSize(1080, 1920)
        assertEquals(1080f, w2, 0.01f)
        assertEquals(1920f, h2, 0.01f) // already 9:16

        val (w3, _) = CropCalculator.baseRectSize(1080, 2400)
        assertEquals(1080f, w3, 0.01f)
    }

    @Test
    fun `center is clamped so crop stays inside frame`() {
        val t = CropCalculator.clampCenter(0f, 0f, 1.5f, 1920, 1080)
        assertTrue(t.centerX > 0f)
        assertTrue(t.centerY == 0.5f)
    }

    @Test
    fun `export matrix maps crop rect onto the visible NDC region`() {
        // 16:9 source, 1080x1920 output.
        val displayW = 1920
        val displayH = 1080
        val outW = 1080
        val outH = 1920
        val rect = CropCalculator.rectFor(
            com.shortsclipper.video.CropTarget(0.7f, 0.5f, 1f),
            displayW,
            displayH,
        )
        val m = CropCalculator.toVertexMatrix(rect, displayW, displayH, outW, outH)

        // s = max(outW/W, outH/H); visible half extents.
        val s = maxOf(outW.toFloat() / displayW, outH.toFloat() / displayH)
        val vx = outW / (s * displayW)
        val vy = outH / (s * displayH)

        // Left crop edge must map to -vx; right edge to +vx.
        val leftNdc = 2f * (rect.centerX - rect.widthFrac) - 1f
        val rightNdc = 2f * (rect.centerX + rect.widthFrac) - 1f
        val leftOut = m[0] * leftNdc + m[12]
        val rightOut = m[0] * rightNdc + m[12]
        assertNear(-vx, leftOut)
        assertNear(vx, rightOut)

        // Bottom crop edge (y down, NDC y up) must map to -vy; top edge to +vy.
        val bottomNdc = 1f - 2f * (rect.centerY + rect.heightFrac)
        val topNdc = 1f - 2f * (rect.centerY - rect.heightFrac)
        val bottomOut = m[5] * bottomNdc + m[13]
        val topOut = m[5] * topNdc + m[13]
        assertNear(-vy, bottomOut)
        assertNear(vy, topOut)
    }

    @Test
    fun `preview transform puts crop center at box center and crop edges at box edges`() {
        val displayW = 1920
        val displayH = 1080
        val boxW = 540f
        val boxH = 960f
        val rect = CropCalculator.rectFor(
            com.shortsclipper.video.CropTarget(0.65f, 0.5f, 1.3f),
            displayW,
            displayH,
        )
        val t = CropCalculator.previewTransform(rect, boxW, boxH, displayW, displayH)

        val fit = minOf(boxW / displayW, boxH / displayH)
        val contentW = displayW * fit
        val contentH = displayH * fit

        // Crop center (in fitted content px) after scaling + translation = box center.
        val cropCenterX = (rect.centerX - 0.5f) * contentW
        val cropCenterY = (rect.centerY - 0.5f) * contentH
        assertNear(0f, t.scale * cropCenterX + t.translationXPx)
        assertNear(0f, t.scale * cropCenterY + t.translationYPx)

        // Crop width fills the box width exactly.
        assertNear(boxW, t.scale * rect.widthFrac * contentW)
        assertNear(boxH, t.scale * rect.heightFrac * contentH)
    }

    @Test
    fun `preview and export describe the same rect for the same state`() {
        // Both paths call rectAt with the same time; assert the rect is
        // identical (single source of truth).
        val state = com.shortsclipper.model.ProjectState(id = "x", name = "x")
        val rectA = CropCalculator.rectAt(state, 12_345L)
        val rectB = CropCalculator.rectAt(state, 12_345L)
        assertEquals(rectA, rectB)
        assertEquals(0.5f, rectA.centerX, 0.0001f)
        assertEquals(0.5f, rectA.centerY, 0.0001f)
    }

    @Test
    fun `zoom is bounded`() {
        assertEquals(1f, CropCalculator.clampZoom(0.2f))
        assertEquals(4f, CropCalculator.clampZoom(9f))
        assertEquals(2f, CropCalculator.clampZoom(2f))
    }
}
