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

    fun toDb(rms: Float): Float = 20f * log10((rms + 1e-6).toDouble()).toFloat()

    /** Noise floor as the 10th percentile dB of the envelope. */
    fun noiseFloorDb(envelope: List<Float>): Float {
        if (envelope.isEmpty()) return -60f
        val dbs = envelope.map { toDb(it) }.sorted()
        val idx = ((dbs.size - 1) * 0.1f).toInt()
        return dbs[idx]
    }

    fun detect(envelope: List<Float>, config: Config = Config(), stepMs: Int = STEP_MS): List<SilenceEdit> {
        if (envelope.isEmpty()) return emptyList()
        val floor = noiseFloorDb(envelope)
        // The effective threshold never sits below the noise floor + 6 dB,
        // so very quiet recordings still produce sane results.
        val threshold = maxOf(config.thresholdDb, floor + 6f)
        val minRun = (config.minSilenceMs + stepMs - 1) / stepMs

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
