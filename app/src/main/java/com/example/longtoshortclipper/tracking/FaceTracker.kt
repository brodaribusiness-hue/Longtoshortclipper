package com.example.longtoshortclipper.tracking

import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

object FaceTracker {

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    suspend fun detectFace(bitmap: android.graphics.Bitmap): Rect? =
        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(bitmap, 0)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        cont.resume(faces.firstOrNull()?.boundingBox)
                    }
                    .addOnFailureListener { cont.resume(null) }
            }
        }

    class MovingAverage(private val windowSize: Int = 5) {
        private val values = ArrayDeque<Float>()
        fun add(value: Float): Float {
            values.addLast(value)
            if (values.size > windowSize) values.removeFirst()
            return values.average().toFloat()
        }
    }

    fun faceCenter(rect: Rect): Pair<Int, Int> {
        val cx = ((rect.left + rect.right) / 2f).roundToInt()
        val cy = ((rect.top + rect.bottom) / 2f).roundToInt()
        return Pair(cx, cy)
    }

    fun release() {
        detector.close()
    }
}
