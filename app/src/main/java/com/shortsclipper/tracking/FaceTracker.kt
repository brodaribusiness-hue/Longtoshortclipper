package com.shortsclipper.tracking

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.shortsclipper.model.FaceBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device face tracking pass (ML Kit, bundled models, no cloud):
 * sparsely samples downscaled frames over the selected range, detects faces
 * per frame and hands the samples to TrackingEngine.associateTracks().
 */
class FaceTracker(private val context: Context) {

    data class PassResult(
        /** Bitmap of the first sampled frame (for target selection UI), or null. */
        val firstFrameBitmap: Bitmap?,
        /** timeMs -> detections on that frame (faceId is -1, assigned by association). */
        val samples: List<Pair<Long, List<FaceBox>>>,
    ) {
        val hasFaces: Boolean get() = samples.any { it.second.isNotEmpty() }
    }

    suspend fun detectOverRange(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        sampleIntervalMs: Long = TrackingSmoother.SAMPLE_INTERVAL_MS,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PassResult = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
        val detector = FaceDetection.getClient(options)
        var firstFrame: Bitmap? = null
        try {
            retriever.setDataSource(context, uri)
            val samples = ArrayList<Pair<Long, List<FaceBox>>>()
            var time = startMs.coerceAtLeast(0)
            val safeEnd = maxOf(endMs, startMs + sampleIntervalMs)
            while (time <= safeEnd) {
                if (isCancelled()) throw InterruptedException("Face tracking cancelled")
                val bitmap = extractFrame(retriever, time * 1000, maxDim = 480)
                if (bitmap != null) {
                    if (firstFrame == null) firstFrame = bitmap
                    val faces = detectOnBitmap(detector, bitmap)
                    val bw = bitmap.width.toFloat().coerceAtLeast(1f)
                    val bh = bitmap.height.toFloat().coerceAtLeast(1f)
                    val boxes = faces.mapNotNull { face ->
                        val r = face.boundingBox
                        val leftFrac = (r.left / bw).coerceIn(0f, 1f)
                        val topFrac = (r.top / bh).coerceIn(0f, 1f)
                        val rightFrac = (r.right / bw).coerceIn(0f, 1f)
                        val bottomFrac = (r.bottom / bh).coerceIn(0f, 1f)
                        val wFrac = rightFrac - leftFrac
                        val hFrac = bottomFrac - topFrac
                        if (wFrac > 0.02f && hFrac > 0.02f) {
                            FaceBox(
                                faceId = -1,
                                xFrac = leftFrac,
                                yFrac = topFrac,
                                wFrac = wFrac,
                                hFrac = hFrac,
                                timeMs = time,
                            )
                        } else null
                    }
                    samples.add(Pair(time, boxes))
                    bitmap.recycleUnless(firstFrame)
                    onProgress(((time - startMs).toFloat() / (safeEnd - startMs)).coerceIn(0f, 1f))
                }
                time += sampleIntervalMs
            }
            PassResult(firstFrame, samples)
        } catch (t: Throwable) {
            firstFrame?.recycle()
            firstFrame = null
            throw t
        } finally {
            detector.close()
            retriever.release()
        }
    }

    private fun Bitmap.recycleUnless(kept: Bitmap?) {
        if (this !== kept) recycle()
    }

    private suspend fun detectOnBitmap(
        detector: com.google.mlkit.vision.face.FaceDetector,
        bitmap: Bitmap,
    ): List<com.google.mlkit.vision.face.Face> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (cont.isActive) cont.resume(faces)
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    private fun extractFrame(retriever: MediaMetadataRetriever, timeUs: Long, maxDim: Int): Bitmap? {
        val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val rotated = rot == 90 || rot == 270
        val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 } ?: 1280
        val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 } ?: 720
        val dispW = if (rotated) rawH else rawW
        val dispH = if (rotated) rawW else rawH

        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val scale = maxDim.toFloat() / maxOf(dispW, dispH)
            val sw = (dispW * scale).toInt().coerceAtLeast(64)
            val sh = (dispH * scale).toInt().coerceAtLeast(64)
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, sw, sh)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?.let { full ->
                    val scale = maxDim.toFloat() / maxOf(full.width, full.height)
                    val scaled = Bitmap.createScaledBitmap(
                        full,
                        (full.width * scale).toInt().coerceAtLeast(64),
                        (full.height * scale).toInt().coerceAtLeast(64),
                        true,
                    )
                    if (scaled !== full) full.recycle()
                    if (rot != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                        val rotBmp = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
                        if (rotBmp !== scaled) scaled.recycle()
                        rotBmp
                    } else {
                        scaled
                    }
                }
        }
        return frame
    }
}
