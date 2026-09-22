package com.shortsclipper.ai

import com.shortsclipper.model.SilenceEdit
import kotlin.math.log10

/**
 * Detects unnecessary pauses from the precomputed loudness envelope
 * (RMS per 100 ms). Conservative defaults: short natural pauses are kept.
 */
object SilenceDetector {

    const val DEFAULT_THRESHOLD_DB = -38f
    const val DEFAULT_MIN_SILENCE_MS = 600
    const val STEP_MS = 100

    data class Config(
        val thresholdDb: Float = DEFAULT_THRESHOLD_DB,
        val minSilenceMs: Int = DEFAULT_MIN_SILENCE_MS,
    )

    fun toDb(rms: Float): Float {
        val safeRms = rms.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        return 20f * log10((safeRms + 1e-6).toDouble()).toFloat()
    }

    /** Noise floor: the quietest envelope level (robust even for very short pauses). */
    fun noiseFloorDb(envelope: List<Float>): Float {
        if (envelope.isEmpty()) return -60f
        return envelope.minOf { toDb(it) }
    }

    fun detect(envelope: List<Float>, config: Config = Config(), stepMs: Int = STEP_MS): List<SilenceEdit> {
        require(stepMs > 0) { "Envelope step must be positive" }
        if (envelope.isEmpty()) return emptyList()
        val dbs = envelope.map { toDb(it) }
        val floor = dbs.min()
        // Without dynamic range (e.g. continuous speech at one level) there is
        // no distinguishable silence - anything would be a false positive.
        if (dbs.max() - floor < 12f) return emptyList()
        // Persisted/caller-provided settings are untrusted; a NaN threshold
        // would make every comparison false and silently disable detection.
        val requestedThreshold = config.thresholdDb.takeIf { it.isFinite() } ?: DEFAULT_THRESHOLD_DB
        // The effective threshold never sits below the noise floor + 6 dB,
        // so very quiet recordings still produce sane results.
        val threshold = maxOf(requestedThreshold, floor + 6f)
        val minimumSilenceMs = config.minSilenceMs.coerceAtLeast(1).toLong()
        val minRun = ((minimumSilenceMs + stepMs.toLong() - 1L) / stepMs)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

        val silences = ArrayList<SilenceEdit>()
        var runStart = -1
        for (i in envelope.indices) {
            val quiet = toDb(envelope[i]) < threshold
            if (quiet && runStart < 0) {
                runStart = i
            } else if (!quiet && runStart >= 0) {
                val runLength = i - runStart
                if (runLength >= minRun) {
                    silences.add(SilenceEdit(runStart.toLong() * stepMs, i.toLong() * stepMs))
                }
                runStart = -1
            }
        }
        if (runStart >= 0) {
            val runLength = envelope.size - runStart
            if (runLength >= minRun) {
                silences.add(SilenceEdit(runStart.toLong() * stepMs, envelope.size.toLong() * stepMs))
            }
        }
        return silences
    }
}
