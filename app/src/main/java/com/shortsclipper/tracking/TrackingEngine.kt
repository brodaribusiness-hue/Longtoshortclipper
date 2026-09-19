package com.shortsclipper.tracking

import com.shortsclipper.model.CropTarget
import com.shortsclipper.model.CropTarget
import com.shortsclipper.model.FaceBox
import com.shortsclipper.model.FacePoint
import com.shortsclipper.model.TransformKeyframe
import com.shortsclipper.model.TrackingState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure tracking logic (unit-tested): face-track association across frames,
 * manual keyframe interpolation, hybrid (auto + manual correction) blending.
 * Clamping to frame bounds happens in CropCalculator.
 */
object TrackingEngine {

    /** Max normalized center distance to associate consecutive detections of one face. */
    const val ASSOC_MAX_DIST = 0.35f

    /** Manual keyframes correct the automatic path within this window. */
    const val MANUAL_BLEND_MS = 800L

    /** Default crop zoom while following an automatic face path. */
    const val AUTO_FACE_ZOOM = 1.25f

    /**
     * Greedy nearest-neighbor association of per-frame face detections into
     * temporal tracks. Each returned FaceBox carries its track id.
     */
    fun associateTracks(samples: List<Pair<Long, List<FaceBox>>>): List<List<FaceBox>> {
        val tracks = ArrayList<ArrayList<FaceBox>>()
        var nextId = 0
        for ((timeMs, boxes) in samples.sortedBy { it.first }) {
            val usedTracks = HashSet<Int>()
            for (box in boxes.sortedByDescending { it.wFrac * it.hFrac }) {
                var bestTrack = -1
                var bestDist = ASSOC_MAX_DIST
                for (i in tracks.indices) {
                    if (i in usedTracks) continue
                    val last = tracks[i].last()
                    val d = hypot((last.centerX - box.centerX).toDouble(), (last.centerY - box.centerY).toDouble()).toFloat()
                    if (d < bestDist) {
                        bestDist = d
                        bestTrack = i
                    }
                }
                if (bestTrack >= 0) {
                    val assigned = box.copy(faceId = tracks[bestTrack].first().faceId, timeMs = timeMs)
                    tracks[bestTrack].add(assigned)
                    usedTracks.add(bestTrack)
                } else {
                    val newBox = box.copy(faceId = nextId, timeMs = timeMs)
                    tracks.add(arrayListOf(newBox))
                    nextId++
                    usedTracks.add(tracks.size - 1)
                }
            }
        }
        return tracks
    }

    fun trackById(tracks: List<List<FaceBox>>, faceId: Int): List<FaceBox>? =
        tracks.firstOrNull { it.firstOrNull()?.faceId == faceId }

    /** Largest visible face at selection time - sensible default tracking target. */
    fun largestTrack(tracks: List<List<FaceBox>>): List<FaceBox>? =
        tracks.maxByOrNull { track -> track.firstOrNull()?.let { it.wFrac * it.hFrac } ?: 0f }

    fun toPath(track: List<FaceBox>): List<FacePoint> = track
        .sortedBy { it.timeMs }
        .map { FacePoint(it.timeMs, it.centerX, it.centerY, it.wFrac) }

    /** Linear interpolation over the smoothed automatic path, held constant at the ends. */
    fun autoAt(path: List<FacePoint>, timeMs: Long): CropTarget? {
        if (path.isEmpty()) return null
        if (path.size == 1 || timeMs <= path.first().timeMs) {
            val p = path.first()
            return CropTarget(p.xFrac, p.yFrac, AUTO_FACE_ZOOM)
        }
        if (timeMs >= path.last().timeMs) {
            val p = path.last()
            return CropTarget(p.xFrac, p.yFrac, AUTO_FACE_ZOOM)
        }
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            if (timeMs in a.timeMs..b.timeMs) {
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                val f = (timeMs - a.timeMs).toFloat() / span
                return CropTarget(
                    centerX = a.xFrac + (b.xFrac - a.xFrac) * f,
                    centerY = a.yFrac + (b.yFrac - a.yFrac) * f,
                    zoom = AUTO_FACE_ZOOM,
                )
            }
        }
        val p = path.last()
        return CropTarget(p.xFrac, p.yFrac, AUTO_FACE_ZOOM)
    }

    /** Smooth (smoothstep-eased) interpolation between manual keyframes. */
    fun manualAt(keyframes: List<TransformKeyframe>, timeMs: Long): CropTarget? {
        if (keyframes.isEmpty()) return null
        val sorted = keyframes.sortedBy { it.timeMs }
        if (timeMs <= sorted.first().timeMs) return sorted.first().toTarget()
        if (timeMs >= sorted.last().timeMs) return sorted.last().toTarget()
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (timeMs in a.timeMs..b.timeMs) {
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                val f = smoothstep((timeMs - a.timeMs).toFloat() / span)
                val targetA = a.toTarget()
                val targetB = b.toTarget()
                return CropTarget(
                    centerX = targetA.centerX + (targetB.centerX - targetA.centerX) * f,
                    centerY = targetA.centerY + (targetB.centerY - targetA.centerY) * f,
                    zoom = targetA.zoom + (targetB.zoom - targetA.zoom) * f,
                )
            }
        }
        return sorted.last().toTarget()
    }

    /**
     * Final crop target at a time: manual keyframes only, automatic path only,
     * or hybrid blending of both (manual correction fades in/out around each
     * keyframe, the automatic path continues elsewhere).
     */
    fun evaluateAt(tracking: TrackingState, timeMs: Long): CropTarget {
        val auto = if (tracking.autoEnabled && tracking.autoPath.isNotEmpty()) autoAt(tracking.autoPath, timeMs) else null
        val manual = manualAt(tracking.manualKeyframes, timeMs)
        return when {
            auto == null && manual == null -> CropTarget(0.5f, 0.5f, 1f)
            manual != null && auto == null -> manual
            auto != null && manual == null -> auto
            else -> {
                val weight = manualWeightAt(tracking.manualKeyframes, timeMs)
                blend(auto!!, manual!!, weight)
            }
        }
    }

    /** Blend weight (0..1, smoothstep-eased) of manual keyframes at a time. */
    fun manualWeightAt(keyframes: List<TransformKeyframe>, timeMs: Long): Float {
        if (keyframes.isEmpty()) return 0f
        val nearest = keyframes.minByOrNull { abs(it.timeMs - timeMs) } ?: return 0f
        val dist = abs(nearest.timeMs - timeMs).toFloat()
        val w = (1f - dist / MANUAL_BLEND_MS).coerceIn(0f, 1f)
        return smoothstep(w)
    }

    fun blend(auto: CropTarget, manual: CropTarget, weight: Float): CropTarget = CropTarget(
        centerX = auto.centerX + (manual.centerX - auto.centerX) * weight,
        centerY = auto.centerY + (manual.centerY - auto.centerY) * weight,
        zoom = auto.zoom + (manual.zoom - auto.zoom) * weight,
    )

    fun smoothstep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun TransformKeyframe.toTarget() = CropTarget(centerXFrac, centerYFrac, zoom)
}
