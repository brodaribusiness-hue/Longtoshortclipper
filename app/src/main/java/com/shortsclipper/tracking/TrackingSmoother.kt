package com.shortsclipper.tracking

import com.shortsclipper.model.FacePoint
import com.shortsclipper.model.SmoothingPreset

/**
 * Moving-average smoothing of the automatic tracking path. Larger windows
 * remove jitter and small rapid movements but add reaction latency.
 */
object TrackingSmoother {

    /** Core sample spacing used by the detection pass. */
    const val SAMPLE_INTERVAL_MS = 250L

    fun smooth(points: List<FacePoint>, preset: SmoothingPreset): List<FacePoint> {
        if (points.size <= 2) return points
        val window = preset.windowSamples.coerceAtLeast(1)
        if (window == 1) return points
        val half = window / 2
        return points.mapIndexed { i, _ ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(points.size - 1)
            var sx = 0f
            var sy = 0f
            var sw = 0f
            for (j in from..to) {
                sx += points[j].xFrac
                sy += points[j].yFrac
                sw += points[j].wFrac
            }
            val n = (to - from + 1)
            val base = points[i]
            base.copy(xFrac = sx / n, yFrac = sy / n, wFrac = sw / n)
        }
    }

    /** Max per-sample movement after smoothing (normalized units) - jitter check helper for tests. */
    fun maxStep(points: List<FacePoint>): Float {
        if (points.size < 2) return 0f
        var max = 0f
        for (i in 1 until points.size) {
            val dx = points[i].xFrac - points[i - 1].xFrac
            val dy = points[i].yFrac - points[i - 1].yFrac
            val d = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (d > max) max = d
        }
        return max
    }
}
