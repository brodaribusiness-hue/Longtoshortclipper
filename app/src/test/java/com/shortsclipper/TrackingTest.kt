package com.shortsclipper

import com.shortsclipper.model.FaceBox
import com.shortsclipper.model.SmoothingPreset
import com.shortsclipper.model.TransformKeyframe
import com.shortsclipper.model.TrackingState
import com.shortsclipper.tracking.TrackingEngine
import com.shortsclipper.tracking.TrackingSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingTest {

    private fun box(id: Int, cx: Float, cy: Float, timeMs: Long, w: Float = 0.2f, h: Float = 0.25f) =
        FaceBox(id, cx - w / 2, cy - h / 2, w, h, timeMs)

    // ---------------------------------------------------------- association

    @Test
    fun `no faces produce no tracks`() {
        val tracks = TrackingEngine.associateTracks(emptyList())
        assertTrue(tracks.isEmpty())
    }

    @Test
    fun `single moving face becomes one track`() {
        val samples = listOf(
            Pair(0L, listOf(box(-1, 0.3f, 0.4f, 0L))),
            Pair(250L, listOf(box(-1, 0.33f, 0.41f, 250L))),
            Pair(500L, listOf(box(-1, 0.36f, 0.42f, 500L))),
        )
        val tracks = TrackingEngine.associateTracks(samples)
        assertEquals(1, tracks.size)
        assertEquals(3, tracks[0].size)
        assertEquals(0, tracks[0].first().faceId)
    }

    @Test
    fun `two distant faces stay separate tracks`() {
        val samples = listOf(
            Pair(0L, listOf(box(-1, 0.2f, 0.3f, 0L), box(-1, 0.8f, 0.3f, 0L))),
            Pair(250L, listOf(box(-1, 0.22f, 0.3f, 250L), box(-1, 0.82f, 0.3f, 250L))),
        )
        val tracks = TrackingEngine.associateTracks(samples)
        assertEquals(2, tracks.size)
        // The left face must never leak into the right track.
        val left = tracks.first { it.first().centerX < 0.5f }
        val right = tracks.first { it.first().centerX >= 0.5f }
        assertEquals(2, left.size)
        assertEquals(2, right.size)
        assertTrue(left.all { it.centerX < 0.5f })
        assertTrue(right.all { it.centerX >= 0.5f })
    }

    @Test
    fun `target selection returns requested track`() {
        val samples = listOf(
            Pair(0L, listOf(box(-1, 0.2f, 0.3f, 0L, w = 0.3f), box(-1, 0.8f, 0.3f, 0L))),
        )
        val tracks = TrackingEngine.associateTracks(samples)
        // Largest face is the sensible default target.
        assertEquals(0, TrackingEngine.largestTrack(tracks)!!.first().faceId)
        assertNotNull(TrackingEngine.trackById(tracks, 1))
        assertNull(TrackingEngine.trackById(tracks, 99))
    }

    // ------------------------------------------------------------- smoother

    @Test
    fun `smoothing reduces jitter`() {
        val jittery = (0 until 30).map { i ->
            FacePoint(i * 250L, 0.5f + if (i % 2 == 0) 0.02f else -0.02f, 0.5f, 0.2f)
        }
        val smooth = TrackingSmoother.smooth(jittery, SmoothingPreset.BALANCED)
        assertTrue(TrackingSmoother.maxStep(smooth) < TrackingSmoother.maxStep(jittery))
        // Movement is monotonic-ish: after smoothing the alternating jitter is gone.
        val alternating = (1 until smooth.size).count { i ->
            (smooth[i].xFrac - smooth[i - 1].xFrac) * (smooth[i - 1].xFrac - smooth[if (i >= 2) i - 2 else i - 1].xFrac) < 0
        }
        assertTrue("jitter should be reduced", alternating <= 2)
    }

    @Test
    fun `smooth preset reacts slower than responsive`() {
        val path = listOf(
            FacePoint(0L, 0.2f, 0.5f, 0.2f),
            FacePoint(250L, 0.2f, 0.5f, 0.2f),
            FacePoint(500L, 0.2f, 0.5f, 0.2f),
            FacePoint(750L, 0.2f, 0.5f, 0.2f),
            FacePoint(1000L, 0.8f, 0.5f, 0.2f),
            FacePoint(1250L, 0.8f, 0.5f, 0.2f),
            FacePoint(1500L, 0.8f, 0.5f, 0.2f),
            FacePoint(1750L, 0.8f, 0.5f, 0.2f),
        )
        val responsive = TrackingSmoother.smooth(path, SmoothingPreset.RESPONSIVE)
        val smooth = TrackingSmoother.smooth(path, SmoothingPreset.SMOOTH)
        // At the midpoint (index 4/5), SMOOTH lags further behind the jump.
        assertTrue(smooth[5].xFrac < responsive[5].xFrac)
    }

    // -------------------------------------------------------- manual + hybrid

    @Test
    fun `manual keyframes interpolate smoothly between times`() {
        val kfs = listOf(
            TransformKeyframe(0, 0.2f, 0.5f, 1f),
            TransformKeyframe(10_000, 0.8f, 0.5f, 1f),
        )
        val start = TrackingEngine.manualAt(kfs, 0)!!
        val mid = TrackingEngine.manualAt(kfs, 5_000)!!
        val end = TrackingEngine.manualAt(kfs, 10_000)!!
        assertEquals(0.2f, start.centerX, 0.001f)
        assertEquals(0.8f, end.centerX, 0.001f)
        // smoothstep easing -> midpoint exactly halfway, but not linear before/after
        assertEquals(0.5f, mid.centerX, 0.001f)
        val quarter = TrackingEngine.manualAt(kfs, 2_500)!!
        assertTrue("easing slows the start", quarter.centerX < 0.35f)
    }

    @Test
    fun `manual path holds constant outside keyframe range`() {
        val kfs = listOf(TransformKeyframe(5_000, 0.3f, 0.5f, 1.2f))
        assertEquals(0.3f, TrackingEngine.manualAt(kfs, 0)!!.centerX, 0.001f)
        assertEquals(0.3f, TrackingEngine.manualAt(kfs, 999_999)!!.centerX, 0.001f)
        assertEquals(1.2f, TrackingEngine.manualAt(kfs, 999_999)!!.zoom, 0.001f)
    }

    @Test
    fun `hybrid blends manual correction over automatic path`() {
        val autoPath = (0..20).map { FacePoint(it * 500L, 0.5f, 0.5f, 0.2f) }
        val tracking = TrackingState(
            autoEnabled = true,
            autoPath = autoPath,
            manualKeyframes = listOf(TransformKeyframe(5_000, 0.2f, 0.5f, 1.5f)),
        )
        // Far from the keyframe: pure auto.
        val far = TrackingEngine.evaluateAt(tracking, 0)
        assertEquals(0.5f, far.centerX, 0.001f)
        assertEquals(TrackingEngine.AUTO_FACE_ZOOM, far.zoom, 0.001f)
        // At the keyframe: full manual correction.
        val at = TrackingEngine.evaluateAt(tracking, 5_000)
        assertEquals(0.2f, at.centerX, 0.01f)
        assertEquals(1.5f, at.zoom, 0.01f)
        // Just outside the blend window: back to auto.
        val after = TrackingEngine.evaluateAt(tracking, 5_000 + TrackingEngine.MANUAL_BLEND_MS * 2)
        assertEquals(0.5f, after.centerX, 0.02f)
    }

    @Test
    fun `evaluate without anything is centered`() {
        val result = TrackingEngine.evaluateAt(TrackingState(), 1_000)
        assertEquals(0.5f, result.centerX, 0.001f)
        assertEquals(0.5f, result.centerY, 0.001f)
        assertEquals(1f, result.zoom, 0.001f)
    }
}
