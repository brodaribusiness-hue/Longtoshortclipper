package com.yourapp.clipper

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class TrackPoint(
    val timeMs: Long,
    /** Normalized 0..1 center of face in the VIDEO frame */
    val centerX: Float,
    val centerY: Float
)

enum class TrackState { IDLE, SCANNING, DONE, FAILED }

class FaceTracker {

    private val scanDispatcher = Executors.newSingleThreadExecutor()
        .asCoroutineDispatcher()

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build()
        )
    }

    private val smoothingWindow = 5
    private val recentX = ArrayDeque<Float>()
    private val recentY = ArrayDeque<Float>()

    suspend fun scanRange(
        context: Context,
        videoUri: Uri,
        startMs: Long,
        endMs: Long,
        videoW: Int,
        videoH: Int,
        onProgress: (Int) -> Unit
    ): List<TrackPoint> = withContext(scanDispatcher) {

        recentX.clear(); recentY.clear()
        val points = mutableListOf<TrackPoint>()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, videoUri)
            val stepMs = 100L
            val total = (((endMs - startMs) / stepMs).toInt()).coerceAtLeast(1)
            var done = 0

            var t = startMs
            while (t <= endMs) {
                val bmp = retriever.getFrameAtTime(
                    t * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (bmp != null) {
                    val center = detectCenter(bmp, videoW, videoH)
                    if (center != null) {
                        points.add(TrackPoint(t, center.first, center.second))
                    }
                    bmp.recycle()
                }
                done++
                onProgress((done * 100) / total)
                t += stepMs
            }
        } finally {
            retriever.release()
        }

        return@withContext fillGaps(points)
    }

    private suspend fun detectCenter(
        bmp: Bitmap,
        videoW: Int,
        videoH: Int
    ): Pair<Float, Float>? {
        return try {
            val faces = detector.process(InputImage.fromBitmap(bmp, 0)).await().faces
            val box = faces.firstOrNull()?.boundingBox ?: return null
            val nx = (box.exactCenterX() / videoW).coerceIn(0f, 1f)
            val ny = (box.exactCenterY() / videoH).coerceIn(0f, 1f)
            recentX.addLast(nx); recentY.addLast(ny)
            if (recentX.size > smoothingWindow) {
                recentX.removeFirst(); recentY.removeFirst()
            }
            val sx = recentX.average().toFloat()
            val sy = recentY.average().toFloat()
            sx to sy
        } catch (e: Exception) {
            null
        }
    }

    private fun fillGaps(points: List<TrackPoint>): List<TrackPoint> {
        if (points.isEmpty()) return emptyList()
        val out = mutableListOf<TrackPoint>()
        var last = points.first()

        for (p in points) {
            val gap = p.timeMs - last.timeMs
            if (gap > 100L && gap <= 2000L) {
                val steps = (gap / 100L).toInt()
                for (i in 1 until steps) {
                    val f = i.toFloat() / steps
                    out.add(
                        TrackPoint(
                            last.timeMs + (gap * i / steps),
                            last.centerX + (p.centerX - last.centerX) * f,
                            last.centerY + (p.centerY - last.centerY) * f
                        )
                    )
                }
            }
            out.add(p)
            last = p
        }
        return out
    }

    fun close() {
        try { detector.close() } catch (_: Exception) {}
        scanDispatcher.close()
    }
}
