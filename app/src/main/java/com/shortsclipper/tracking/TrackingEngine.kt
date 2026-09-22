package com.shortsclipper.tracking

import com.shortsclipper.model.FaceBox
import com.shortsclipper.model.FacePoint
import com.shortsclipper.model.TrackingState
import com.shortsclipper.model.TransformKeyframe
import com.shortsclipper.video.CropTarget
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pure crop and track-association logic. ML Kit tracking IDs are the primary
 * identity signal; motion, overlap and bounded-gap matching provide a fallback
 * when a detector ID is temporarily absent or changes.
 */
object TrackingEngine {

    /** Base normalized center distance for fallback association. */
    const val ASSOC_MAX_DIST = 0.35f
    /** Do not connect a reappearing face after a long absence. */
    const val MAX_TRACK_GAP_MS = 1_500L
    /** Interpolate only short detector gaps; longer gaps hold then ease in. */
    const val MAX_INTERPOLATION_GAP_MS = 1_200L
    /** Manual keyframes correct the automatic path within this window. */
    const val MANUAL_BLEND_MS = 800L
    /** Default crop zoom while following an automatic face path. */
    const val AUTO_FACE_ZOOM = 1.25f

    private data class MutableTrack(
        val id: Int,
        val points: ArrayList<FaceBox>,
        var mlTrackingId: Int?,
    )

    /**
     * Associates sequential ML Kit detections into stable application track
     * IDs. This is a safety layer around ML Kit's built-in tracker, not a
     * sparse-frame replacement for it: FaceTracker feeds ML Kit continuous
     * decoder frames and retains a compact path for export.
     */
    fun associateTracks(samples: List<Pair<Long, List<FaceBox>>>): List<List<FaceBox>> {
        val tracks = ArrayList<MutableTrack>()
        // Only tracks observed within MAX_TRACK_GAP_MS can ever be matched.
        // Keeping this small active set avoids rescanning every historical
        // appearance transition on a long continuous tracking pass.
        val activeTrackIndices = ArrayList<Int>()
        var nextId = 0

        for ((timeMs, rawBoxes) in samples.sortedBy { it.first }) {
            activeTrackIndices.removeAll { trackIndex ->
                timeMs - tracks[trackIndex].points.last().timeMs > MAX_TRACK_GAP_MS
            }
            val boxes = rawBoxes
                .filter { it.wFrac > 0.01f && it.hFrac > 0.01f }
                .sortedByDescending { it.wFrac * it.hFrac }
            if (boxes.isEmpty()) continue

            val assignedTrackIndices = HashSet<Int>()
            val assignmentForBox = IntArray(boxes.size) { -1 }

            // First honor ML Kit's per-detector tracking ID where it is still
            // fresh. This prevents swaps when faces cross each other.
            for (boxIndex in boxes.indices) {
                val mlId = boxes[boxIndex].faceId.takeIf { it >= 0 } ?: continue
                val match = activeTrackIndices
                    .asSequence()
                    .filter { it !in assignedTrackIndices }
                    .filter { trackIndex ->
                        val track = tracks[trackIndex]
                        track.mlTrackingId == mlId && timeMs - track.points.last().timeMs <= MAX_TRACK_GAP_MS
                    }
                    .minByOrNull { trackIndex -> distance(predictedCenter(tracks[trackIndex], timeMs), boxes[boxIndex]) }
                if (match != null) {
                    assignmentForBox[boxIndex] = match
                    assignedTrackIndices += match
                }
            }

            // For missing/restarted ML IDs, perform one-to-one global fallback
            // matching using velocity prediction, overlap and face-size change.
            data class Candidate(val boxIndex: Int, val trackIndex: Int, val cost: Float)
            val candidates = ArrayList<Candidate>()
            for (boxIndex in boxes.indices) {
                if (assignmentForBox[boxIndex] >= 0) continue
                for (trackIndex in activeTrackIndices) {
                    if (trackIndex in assignedTrackIndices) continue
                    val track = tracks[trackIndex]
                    val gap = timeMs - track.points.last().timeMs
                    if (gap !in 0..MAX_TRACK_GAP_MS) continue
                    val box = boxes[boxIndex]
                    val predicted = predictedCenter(track, timeMs)
                    val distance = distance(predicted, box)
                    val threshold = ASSOC_MAX_DIST + (gap.toFloat() / MAX_TRACK_GAP_MS) * 0.12f
                    val overlap = iou(track.points.last(), box)
                    if (distance > threshold && overlap < 0.04f) continue
                    val lastArea = track.points.last().wFrac * track.points.last().hFrac
                    val area = box.wFrac * box.hFrac
                    val areaPenalty = abs(area - lastArea) / max(lastArea, area).coerceAtLeast(0.0001f)
                    candidates += Candidate(boxIndex, trackIndex, distance + (1f - overlap) * 0.12f + areaPenalty * 0.08f)
                }
            }
            for (candidate in candidates.sortedBy { it.cost }) {
                if (assignmentForBox[candidate.boxIndex] >= 0 || candidate.trackIndex in assignedTrackIndices) continue
                assignmentForBox[candidate.boxIndex] = candidate.trackIndex
                assignedTrackIndices += candidate.trackIndex
            }

            for (boxIndex in boxes.indices) {
                val raw = boxes[boxIndex]
                val trackIndex = assignmentForBox[boxIndex]
                if (trackIndex >= 0) {
                    val track = tracks[trackIndex]
                    val assigned = raw.copy(faceId = track.id, timeMs = timeMs)
                    track.points += assigned
                    if (raw.faceId >= 0) track.mlTrackingId = raw.faceId
                } else {
                    val assigned = raw.copy(faceId = nextId, timeMs = timeMs)
                    tracks += MutableTrack(
                        id = nextId,
                        points = arrayListOf(assigned),
                        mlTrackingId = raw.faceId.takeIf { it >= 0 },
                    )
                    activeTrackIndices += tracks.lastIndex
                    nextId++
                }
            }
        }
        return tracks.map { it.points.toList() }
    }

    fun trackById(tracks: List<List<FaceBox>>, faceId: Int): List<FaceBox>? =
        tracks.firstOrNull { it.firstOrNull()?.faceId == faceId }

    /**
     * Stable primary-target choice: prefer a face that is both visually large
     * and present for a meaningful part of the range, rather than whichever
     * happened to be largest in one sampled frame.
     */
    fun largestTrack(tracks: List<List<FaceBox>>): List<FaceBox>? =
        tracks.maxByOrNull { track ->
            if (track.isEmpty()) 0f else {
                val meanArea = track.sumOf { (it.wFrac * it.hFrac).toDouble() }.toFloat() / track.size
                meanArea * (1f + min(track.size, 30) / 30f)
            }
        }

    fun toPath(track: List<FaceBox>): List<FacePoint> = track
        .sortedBy { it.timeMs }
        .distinctBy { it.timeMs }
        .map { face ->
            FacePoint(
                timeMs = face.timeMs,
                xFrac = face.centerX.coerceIn(0f, 1f),
                yFrac = face.centerY.coerceIn(0f, 1f),
                wFrac = face.wFrac.coerceIn(0f, 1f),
            )
        }

    /** Evaluates a smoothed automatic crop path in O(log n) time. */
    fun autoAt(path: List<FacePoint>, timeMs: Long): CropTarget? {
        if (path.isEmpty()) return null
        // Do not steer toward a face before it was actually observed. This
        // prevents a future detection from reframing an earlier no-face shot.
        if (timeMs < path.first().timeMs) return null
        if (path.size == 1 || timeMs == path.first().timeMs) return path.first().toAutoTarget()
        if (timeMs >= path.last().timeMs) return path.last().toAutoTarget()

        var low = 0
        var high = path.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (path[mid].timeMs <= timeMs) low = mid + 1 else high = mid - 1
        }
        val left = path[high.coerceIn(0, path.lastIndex)]
        val right = path[low.coerceIn(0, path.lastIndex)]
        val span = (right.timeMs - left.timeMs).coerceAtLeast(1L)
        val rawFraction = ((timeMs - left.timeMs).toFloat() / span).coerceIn(0f, 1f)

        if (span > MAX_INTERPOLATION_GAP_MS) {
            // The target was absent longer than a temporary occlusion. Hold its
            // last known crop and ease into the recovered position shortly
            // before it is detected again instead of sweeping across a scene.
            val recoveryWindowMs = min(300L, span)
            val recoveryStart = right.timeMs - recoveryWindowMs
            return if (timeMs < recoveryStart) {
                left.toAutoTarget()
            } else {
                blend(left.toAutoTarget(), right.toAutoTarget(), smoothstep((timeMs - recoveryStart).toFloat() / recoveryWindowMs))
            }
        }
        return blend(left.toAutoTarget(), right.toAutoTarget(), smoothstep(rawFraction))
    }

    /** Smoothstep interpolation between manual keyframes, also O(log n). */
    fun manualAt(keyframes: List<TransformKeyframe>, timeMs: Long): CropTarget? {
        if (keyframes.isEmpty()) return null
        val sorted = keyframes.sortedBy { it.timeMs }
        if (timeMs <= sorted.first().timeMs) return sorted.first().toTarget()
        if (timeMs >= sorted.last().timeMs) return sorted.last().toTarget()
        var low = 0
        var high = sorted.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sorted[mid].timeMs <= timeMs) low = mid + 1 else high = mid - 1
        }
        val a = sorted[high.coerceIn(0, sorted.lastIndex)]
        val b = sorted[low.coerceIn(0, sorted.lastIndex)]
        val fraction = smoothstep((timeMs - a.timeMs).toFloat() / (b.timeMs - a.timeMs).coerceAtLeast(1L))
        return blend(a.toTarget(), b.toTarget(), fraction)
    }

    /** Final crop target including automatic path and local manual correction. */
    fun evaluateAt(tracking: TrackingState, timeMs: Long): CropTarget {
        val automatic = if (tracking.autoEnabled && tracking.autoPath.isNotEmpty()) autoAt(tracking.autoPath, timeMs) else null
        val manual = manualAt(tracking.manualKeyframes, timeMs)
        return when {
            automatic == null && manual == null -> CropTarget(0.5f, 0.5f, 1f)
            manual != null && automatic == null -> manual
            automatic != null && manual == null -> automatic
            else -> blend(automatic!!, manual!!, manualWeightAt(tracking.manualKeyframes, timeMs))
        }
    }

    /** Blend weight of the nearest manual correction. */
    fun manualWeightAt(keyframes: List<TransformKeyframe>, timeMs: Long): Float {
        if (keyframes.isEmpty()) return 0f
        val nearest = keyframes.minByOrNull { abs(it.timeMs - timeMs) } ?: return 0f
        return smoothstep((1f - abs(nearest.timeMs - timeMs).toFloat() / MANUAL_BLEND_MS).coerceIn(0f, 1f))
    }

    fun blend(automatic: CropTarget, manual: CropTarget, weight: Float): CropTarget = CropTarget(
        centerX = automatic.centerX + (manual.centerX - automatic.centerX) * weight,
        centerY = automatic.centerY + (manual.centerY - automatic.centerY) * weight,
        zoom = automatic.zoom + (manual.zoom - automatic.zoom) * weight,
    )

    fun smoothstep(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    private fun predictedCenter(track: MutableTrack, timeMs: Long): Pair<Float, Float> {
        val last = track.points.last()
        if (track.points.size < 2) return last.centerX to last.centerY
        val previous = track.points[track.points.lastIndex - 1]
        val elapsed = (last.timeMs - previous.timeMs).coerceAtLeast(1L)
        val gap = (timeMs - last.timeMs).coerceAtLeast(0L)
        val vx = (last.centerX - previous.centerX) / elapsed
        val vy = (last.centerY - previous.centerY) / elapsed
        return (last.centerX + vx * gap).coerceIn(0f, 1f) to (last.centerY + vy * gap).coerceIn(0f, 1f)
    }

    private fun distance(predicted: Pair<Float, Float>, face: FaceBox): Float =
        hypot((predicted.first - face.centerX).toDouble(), (predicted.second - face.centerY).toDouble()).toFloat()

    private fun iou(a: FaceBox, b: FaceBox): Float {
        val left = max(a.xFrac, b.xFrac)
        val top = max(a.yFrac, b.yFrac)
        val right = min(a.xFrac + a.wFrac, b.xFrac + b.wFrac)
        val bottom = min(a.yFrac + a.hFrac, b.yFrac + b.hFrac)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = a.wFrac * a.hFrac + b.wFrac * b.hFrac - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun FacePoint.toAutoTarget() = CropTarget(xFrac, yFrac, AUTO_FACE_ZOOM)
    private fun TransformKeyframe.toTarget() = CropTarget(centerXFrac, centerYFrac, zoom)
}
