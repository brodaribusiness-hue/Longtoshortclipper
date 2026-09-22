package com.shortsclipper.tracking

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.shortsclipper.model.FaceBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Continuous, sequential video face tracking. A MediaExtractor/MediaCodec
 * pipeline decodes frames in timeline order into a bounded ImageReader; one ML
 * Kit detector instance with enableTracking() sees every decoded frame. Only a
 * compact path is retained, so long videos do not accumulate bitmaps or full
 * decoder frames in memory.
 */
class FaceTracker(private val context: Context) {

    data class PassResult(
        /** Compact time -> detections. faceId is ML Kit's tracker ID until associated by TrackingEngine. */
        val samples: List<Pair<Long, List<FaceBox>>>,
        val decodedFrameCount: Int,
    ) {
        val hasFaces: Boolean get() = samples.any { it.second.isNotEmpty() }
    }

    suspend fun detectOverRange(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        rotationDegrees: Int = 0,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean,
    ): PassResult = withContext(Dispatchers.Default) {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = endMs.coerceAtLeast(safeStart)
        ensureNotCancelled(isCancelled)

        val dimensions = readVideoDimensions(uri)
        val analysisDimensions = downscaledDimensions(dimensions.first, dimensions.second)
        try {
            runTrackingPass(
                uri = uri,
                startMs = safeStart,
                endMs = safeEnd,
                rotationDegrees = rotationDegrees,
                outputWidth = analysisDimensions.first,
                outputHeight = analysisDimensions.second,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
        } catch (firstFailure: Throwable) {
            // Some vendor decoders reject a scaled ImageReader surface. Retry
            // once with the coded surface size only if decoding never began;
            // this preserves continuous tracking and avoids duplicate paths.
            if ((analysisDimensions.first != dimensions.first || analysisDimensions.second != dimensions.second) &&
                firstFailure is DecoderSetupException
            ) {
                runTrackingPass(
                    uri = uri,
                    startMs = safeStart,
                    endMs = safeEnd,
                    rotationDegrees = rotationDegrees,
                    outputWidth = dimensions.first,
                    outputHeight = dimensions.second,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
            } else {
                throw firstFailure
            }
        }
    }

    private suspend fun runTrackingPass(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        rotationDegrees: Int,
        outputWidth: Int,
        outputHeight: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): PassResult {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.10f)
                .enableTracking()
                .build(),
        )
        return try {
            val samples = ArrayList<Pair<Long, List<FaceBox>>>()
            var lastStoredTime = Long.MIN_VALUE
            var hadFaces: Boolean? = null
            var decodedFrames = 0
            var lastPublishedProgress = -1f
            val range = (endMs - startMs).coerceAtLeast(1L)

            decodeSequentialFrames(
                uri = uri,
                startMs = startMs,
                endMs = endMs,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                isCancelled = isCancelled,
            ) { image, presentationTimeUs ->
                ensureNotCancelled(isCancelled)
                val timeMs = (presentationTimeUs / 1_000L).coerceIn(startMs, endMs)
                val faces = detectOnImage(detector, image, rotationDegrees)
                // Keep the Image alive until ML Kit finishes, then honor a
                // cancellation that arrived while the detector was using it.
                ensureNotCancelled(isCancelled)
                val boxes = faces.mapNotNull { face ->
                    face.toFaceBox(image, rotationDegrees, timeMs)
                }
                decodedFrames++

                // ML Kit processes every frame. Persist only a compact point
                // every 100 ms, plus appearance/disappearance transitions.
                val hasFacesNow = boxes.isNotEmpty()
                if (timeMs - lastStoredTime >= TrackingSmoother.PATH_POINT_INTERVAL_MS ||
                    hadFaces == null || hadFaces != hasFacesNow
                ) {
                    samples += timeMs to boxes
                    lastStoredTime = timeMs
                    hadFaces = hasFacesNow
                }

                val progress = ((timeMs - startMs).toFloat() / range).coerceIn(0f, 1f)
                if (progress - lastPublishedProgress >= 0.01f || progress >= 1f) {
                    lastPublishedProgress = progress
                    onProgress(progress)
                }
            }
            onProgress(1f)
            PassResult(samples, decodedFrames)
        } finally {
            detector.close()
        }
    }

    private suspend fun decodeSequentialFrames(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        outputWidth: Int,
        outputHeight: Int,
        isCancelled: () -> Boolean,
        onFrame: suspend (Image, Long) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var imageReader: ImageReader? = null
        var callbackThread: HandlerThread? = null
        val queuedImages = LinkedBlockingQueue<Image>(2)
        var initialized = false
        // Used to distinguish a scaled ImageReader/decoder startup rejection
        // from an error after genuine tracking already began. The former gets
        // one coded-size retry in detectOverRange for vendor compatibility.
        var deliveredFrames = 0
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("The selected clip has no decodable video track")
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Video track has no MIME type")
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // This decoder renders into an ImageReader rather than a View.
            // On Android Q+ the default for such a Surface may drop frames when
            // it considers the consumer too slow. Tracking must receive every
            // decoded frame in order so ML Kit's tracker IDs remain continuous.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
            }

            val handlerThread = HandlerThread("ShortsClipperFaceFrames").also { it.start() }
            callbackThread = handlerThread
            val reader = ImageReader.newInstance(
                outputWidth.coerceAtLeast(2),
                outputHeight.coerceAtLeast(2),
                ImageFormat.YUV_420_888,
                2,
            )
            imageReader = reader
            reader.setOnImageAvailableListener({ availableReader ->
                val image = runCatching { availableReader.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if (!queuedImages.offer(image)) image.close()
            }, Handler(handlerThread.looper))

            val decoder = try {
                MediaCodec.createDecoderByType(mime).also { createdDecoder ->
                    codec = createdDecoder
                    createdDecoder.configure(format, reader.surface, null, 0)
                    createdDecoder.start()
                }
            } catch (t: Throwable) {
                throw DecoderSetupException("This device could not configure a sequential video decoder", t)
            }
            initialized = true

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var reachedEnd = false
            while (!outputEnded && !reachedEnd) {
                ensureNotCancelled(isCancelled)
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Video decoder returned a null input buffer")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (sampleSize > inputBuffer.capacity()) {
                                throw IllegalStateException("Video sample exceeds decoder input buffer capacity")
                            }
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val presentationTimeUs = bufferInfo.presentationTimeUs
                        val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        when {
                            presentationTimeUs < startMs * 1_000L || bufferInfo.size <= 0 -> {
                                decoder.releaseOutputBuffer(outputIndex, false)
                            }
                            presentationTimeUs > endMs * 1_000L -> {
                                decoder.releaseOutputBuffer(outputIndex, false)
                                reachedEnd = true
                            }
                            else -> {
                                decoder.releaseOutputBuffer(outputIndex, true)
                                val image = awaitImage(queuedImages, isCancelled)
                                try {
                                    deliveredFrames++
                                    onFrame(image, presentationTimeUs)
                                } finally {
                                    image.close()
                                }
                            }
                        }
                        if (isEos) outputEnded = true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecoderSetupException) {
            throw e
        } catch (t: Throwable) {
            if (!initialized || deliveredFrames == 0) {
                throw DecoderSetupException("Could not start sequential video tracking", t)
            }
            throw IllegalStateException("Face tracking stopped while decoding video: ${t.message ?: "decoder error"}", t)
        } finally {
            // Stop callbacks before draining: otherwise a just-dispatched
            // ImageReader callback can enqueue an Image after the drain.
            runCatching { imageReader?.setOnImageAvailableListener(null, null) }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { imageReader?.close() }
            while (true) {
                val pendingImage = queuedImages.poll() ?: break
                pendingImage.close()
            }
            callbackThread?.quitSafely()
            // Do not leave a HandlerThread carrying references to the closed
            // reader across repeated tracking passes.
            runCatching { callbackThread?.join(500L) }
            runCatching { extractor.release() }
        }
    }

    private suspend fun awaitImage(queue: LinkedBlockingQueue<Image>, isCancelled: () -> Boolean): Image {
        var waitedMs = 0L
        while (waitedMs < 3_000L) {
            ensureNotCancelled(isCancelled)
            queue.poll(100L, TimeUnit.MILLISECONDS)?.let { return it }
            waitedMs += 100L
        }
        throw IllegalStateException("Video decoder did not provide a tracking frame")
    }

    private suspend fun detectOnImage(detector: FaceDetector, image: Image, rotationDegrees: Int): List<Face> =
        // ML Kit may still read the Image while its Task is pending. Do not
        // close it early if the outer coroutine is cancelled; wait for this
        // one bounded detector operation, then propagate cancellation.
        withContext(NonCancellable) {
            suspendCancellableCoroutine { continuation ->
                val input = InputImage.fromMediaImage(image, normalizeRotation(rotationDegrees))
                detector.process(input)
                    .addOnSuccessListener { faces ->
                        if (continuation.isActive) continuation.resume(faces)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        }

    private fun Face.toFaceBox(image: Image, rotationDegrees: Int, timeMs: Long): FaceBox? {
        val rect = boundingBox
        val normalizedRotation = normalizeRotation(rotationDegrees)
        // ML Kit reports bounds in the upright InputImage coordinate space when
        // the rotation is supplied to fromMediaImage(). That is the same display
        // coordinate system used by ExoPlayer and Media3 Transformer.
        val orientedWidth = if (normalizedRotation == 90 || normalizedRotation == 270) image.height else image.width
        val orientedHeight = if (normalizedRotation == 90 || normalizedRotation == 270) image.width else image.height
        if (orientedWidth <= 0 || orientedHeight <= 0) return null
        val left = (rect.left.toFloat() / orientedWidth).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / orientedHeight).coerceIn(0f, 1f)
        val right = (rect.right.toFloat() / orientedWidth).coerceIn(0f, 1f)
        val bottom = (rect.bottom.toFloat() / orientedHeight).coerceIn(0f, 1f)
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        if (width < 0.015f || height < 0.015f) return null
        return FaceBox(
            faceId = trackingId ?: -1,
            xFrac = left,
            yFrac = top,
            wFrac = width,
            hFrac = height,
            timeMs = timeMs,
        )
    }

    private fun readVideoDimensions(uri: Uri): Pair<Int, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (width > 0 && height > 0) return width to height
                }
            }
            throw IllegalStateException("Video dimensions are unavailable")
        } finally {
            extractor.release()
        }
    }

    private fun downscaledDimensions(width: Int, height: Int): Pair<Int, Int> {
        val largest = maxOf(width, height).coerceAtLeast(1)
        val scale = minOf(1f, 640f / largest)
        val scaledWidth = ((width * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        val scaledHeight = ((height * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        return scaledWidth to scaledHeight
    }

    private suspend fun ensureNotCancelled(isCancelled: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (isCancelled()) throw CancellationException("Face tracking cancelled")
    }

    private fun normalizeRotation(value: Int): Int {
        val normalized = ((value % 360) + 360) % 360
        return if (normalized == 0 || normalized == 90 || normalized == 180 || normalized == 270) normalized else 0
    }

    private class DecoderSetupException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
}
