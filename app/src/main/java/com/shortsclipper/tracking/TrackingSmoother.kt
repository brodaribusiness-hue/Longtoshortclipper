package com.shortsclipper.tracking

import com.shortsclipper.model.FacePoint
import com.shortsclipper.model.SmoothingPreset
import kotlin.math.hypot

/**
 * Offline path smoothing for automatic tracks. Detection runs continuously;
 * this class smooths only the compact persisted path and never bridges a long
 * target disappearance as if it were motion.
 */
object TrackingSmoother {

    /** Retained-path spacing. ML Kit still processes every sequential decoded frame. */
    const val PATH_POINT_INTERVAL_MS = 100L
    /** Compatibility alias for callers/tests that used the old sampling name. */
    const val SAMPLE_INTERVAL_MS = PATH_POINT_INTERVAL_MS

    fun smooth(points: List<FacePoint>, preset: SmoothingPreset): List<FacePoint> {
        if (points.size <= 2) return points.sortedBy { it.timeMs }
        val sorted = points.sortedBy { it.timeMs }
        val halfWindow = (preset.windowSamples.coerceAtLeast(1) - 1) / 2
        if (halfWindow == 0) return sorted

        // Precompute continuous-section bounds once. The former inner
        // sameContinuousSection scan made smoothing O(n * window²), which is
        // needlessly expensive for a long retained tracking path. Restricting
        // each triangular window to these bounds is exactly equivalent while
        // keeping the operation O(n * window).
        val sectionStart = IntArray(sorted.size)
        var currentStart = 0
        for (index in sorted.indices) {
            if (index > 0 && sorted[index].timeMs - sorted[index - 1].timeMs > TrackingEngine.MAX_TRACK_GAP_MS) {
                currentStart = index
            }
            sectionStart[index] = currentStart
        }
        val sectionEnd = IntArray(sorted.size)
        var currentEnd = sorted.lastIndex
        for (index in sorted.lastIndex downTo 0) {
            if (index < sorted.lastIndex && sorted[index + 1].timeMs - sorted[index].timeMs > TrackingEngine.MAX_TRACK_GAP_MS) {
                currentEnd = index
            }
            sectionEnd[index] = currentEnd
        }

        return sorted.mapIndexed { index, base ->
            var weightedX = 0f
            var weightedY = 0f
            var weightedW = 0f
            var totalWeight = 0f
            val start = maxOf(sectionStart[index], index - halfWindow)
            val end = minOf(sectionEnd[index], index + halfWindow)
            for (candidateIndex in start..end) {
                val candidate = sorted[candidateIndex]
                // Triangular weights preserve movement response better than a
                // flat mean while still removing detector jitter.
                val weight = (halfWindow + 1 - kotlin.math.abs(candidateIndex - index)).toFloat()
                weightedX += candidate.xFrac * weight
                weightedY += candidate.yFrac * weight
                weightedW += candidate.wFrac * weight
                totalWeight += weight
            }
            if (totalWeight == 0f) base else base.copy(
                xFrac = weightedX / totalWeight,
                yFrac = weightedY / totalWeight,
                wFrac = weightedW / totalWeight,
            )
        }
    }

    /** Downsamples a continuous path for persistence without losing endpoints or scene re-entry. */
    fun compact(points: List<FacePoint>, minIntervalMs: Long = PATH_POINT_INTERVAL_MS): List<FacePoint> {
        if (points.size <= 2 || minIntervalMs <= 0L) return points.sortedBy { it.timeMs }
        val sorted = points.sortedBy { it.timeMs }
        val compacted = ArrayList<FacePoint>()
        var lastKept: FacePoint? = null
        for ((index, point) in sorted.withIndex()) {
            val previous = lastKept
            val gap = previous?.let { point.timeMs - it.timeMs } ?: Long.MAX_VALUE
            when {
                previous == null || gap >= minIntervalMs || gap > TrackingEngine.MAX_TRACK_GAP_MS -> {
                    compacted += point
                    lastKept = point
                }
                // Keep both source endpoints, even for a very short track.
                index == sorted.lastIndex -> {
                    compacted += point
                    lastKept = point
                }
                // Preserve the initial observation; replace only the most
                // recent non-endpoint point inside an interval.
                compacted.size == 1 -> {
                    compacted += point
                    lastKept = point
                }
                else -> {
                    // Keep the latest observation for the interval so the
                    // path remains current rather than lagging behind motion.
                    compacted[compacted.lastIndex] = point
                    lastKept = point
                }
            }
        }
        if (compacted.lastOrNull()?.timeMs != sorted.last().timeMs) compacted += sorted.last()
        return compacted
    }

    /** Max per-point movement helper used by unit tests and diagnostics. */
    fun maxStep(points: List<FacePoint>): Float {
        if (points.size < 2) return 0f
        var maximum = 0f
        for (index in 1 until points.size) {
            val dx = points[index].xFrac - points[index - 1].xFrac
            val dy = points[index].yFrac - points[index - 1].yFrac
            maximum = maxOf(maximum, hypot(dx.toDouble(), dy.toDouble()).toFloat())
        }
        return maximum
    }

}
