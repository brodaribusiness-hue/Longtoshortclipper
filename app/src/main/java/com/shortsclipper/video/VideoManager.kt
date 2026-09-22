package com.shortsclipper.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.shortsclipper.model.VideoSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Drops target-rate samples that overlap data already written for an earlier
 * decoder output buffer. It preserves streaming behavior: samples are never
 * collected solely to trim a timestamp overlap.
 */
internal class TargetTimelineOverlapTrimmer(overlapSamples: Long) {
    private var remainingSamples = overlapSamples.coerceAtLeast(0L)

    fun emit(sample: Float, consumer: (Float) -> Unit) {
        if (remainingSamples > 0L) {
            remainingSamples--
        } else {
            consumer(sample)
        }
    }

    companion object {
        fun overlapSamples(writtenTargetSamples: Long, targetStartSample: Long): Long =
            (writtenTargetSamples - targetStartSample).coerceAtLeast(0L)
    }
}

/**
 * URI-based video access. Metadata and decoded media are streamed; a long
 * source is never loaded into memory as a whole.
 */
object VideoManager {

    const val TARGET_SAMPLE_RATE = 16_000
    private const val ENVELOPE_STEP_MS = 100
    private const val PCM_FLOAT_BYTES = 4
    private const val AUDIO_PTS_TOLERANCE_US = 2_000_000L
    private const val MAX_PTS_OVERLAP_US = 500_000L
    // A duration-less malformed stream must not turn one bogus timestamp into
    // gigabytes of synthesized silence. Normal tracks use KEY_DURATION instead.
    private const val MAX_UNKNOWN_AUDIO_TIMELINE_US = 6L * 60L * 60L * 1_000_000L
    private const val SILENCE_WRITE_CHUNK_SAMPLES = TARGET_SAMPLE_RATE

    data class AudioDecodeResult(
        val pcmFile: File,
        /** RMS loudness per ProjectState.envelopeStepMs, normalized 0..1. */
        val envelope: List<Float>,
        /** Source-video timestamp of the first decoded PCM buffer. */
        val startTimeMs: Long,
        val sampleRate: Int,
        /** PCM duration, independent of a leading source A/V offset. */
        val decodedDurationMs: Long,
    )

    fun readMetadata(context: Context, uri: Uri, displayName: String): VideoSource {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            var durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val metadataHasAudioValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val metadataHasAudio = metadataHasAudioValue.equals("yes", ignoreCase = true) || metadataHasAudioValue == "1"
            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
            val fallbackWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val fallbackHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val fallbackRotation = normalizeRotation(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
            )

            var videoMime: String? = null
            var audioMime: String? = null
            var fps = captureFps
            var width = 0
            var height = 0
            var rotation = 0
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoMime == null) {
                        videoMime = mime
                        width = format.intOrZero(MediaFormat.KEY_WIDTH)
                        height = format.intOrZero(MediaFormat.KEY_HEIGHT)
                        rotation = normalizeRotation(format.intOrZero(MediaFormat.KEY_ROTATION))
                        if (fps <= 0f) fps = format.numberAsFloat(MediaFormat.KEY_FRAME_RATE)
                        if (durationMs <= 0L) durationMs = format.longOrZero(MediaFormat.KEY_DURATION) / 1_000L
                    } else if (mime.startsWith("audio/") && audioMime == null) {
                        audioMime = mime
                        if (durationMs <= 0L) durationMs = format.longOrZero(MediaFormat.KEY_DURATION) / 1_000L
                    }
                }
            } finally {
                extractor.release()
            }

            if (width <= 0 || height <= 0) {
                width = fallbackWidth
                height = fallbackHeight
                rotation = fallbackRotation
            }
            if (rotation == 0 && fallbackRotation != 0) rotation = fallbackRotation
            if (fps <= 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && durationMs > 0L) {
                val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                if (frameCount > 0) fps = frameCount * 1_000f / durationMs
            }

            val isQuarterTurn = rotation == 90 || rotation == 270
            return VideoSource(
                uri = uri.toString(),
                displayName = displayName,
                durationMs = durationMs.coerceAtLeast(0L),
                width = width.coerceAtLeast(0),
                height = height.coerceAtLeast(0),
                rotationDegrees = rotation,
                displayWidth = if (isQuarterTurn) height else width,
                displayHeight = if (isQuarterTurn) width else height,
                fps = fps.takeIf { it.isFinite() && it > 0f } ?: 0f,
                hasAudio = metadataHasAudio || audioMime != null,
                videoMimeType = videoMime,
                audioMimeType = audioMime,
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Extracts one upright, bounded-size frame for thumbnails. The retriever is
     * always released, and encoded frames are physically rotated only when the
     * retriever has not already applied the stream's rotation metadata.
     */
    fun extractFrame(
        context: Context,
        uri: Uri,
        timeMs: Long,
        maxDim: Int = 512,
        accurate: Boolean = true,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val option = if (accurate) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val timeUs = timeMs.coerceAtLeast(0L) * 1_000L
            val encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            // getFrameAtTime may allocate a full 4K/8K bitmap before it can be
            // reduced. Use the platform decoder's bounded extraction API where
            // available and retain the old path only for API 24-26.
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                maxDim > 0 && encodedWidth > 0 && encodedHeight > 0
            ) {
                val scale = min(1f, maxDim.toFloat() / max(encodedWidth, encodedHeight))
                val targetWidth = (encodedWidth * scale).toInt().coerceAtLeast(2)
                val targetHeight = (encodedHeight * scale).toInt().coerceAtLeast(2)
                runCatching {
                    retriever.getScaledFrameAtTime(timeUs, option, targetWidth, targetHeight)
                }.getOrNull() ?: retriever.getFrameAtTime(timeUs, option)
            } else {
                retriever.getFrameAtTime(timeUs, option)
            } ?: return null
            val upright = rotateFrameIfNeeded(retriever, frame)
            scaleBitmapDown(upright, maxDim)
        } finally {
            retriever.release()
        }
    }

    /**
     * Streams the first audio track through MediaCodec into 16 kHz mono float
     * PCM. Loudness is computed after resampling, so waveform/silence timing is
     * correct regardless of the source sample rate.
     */
    fun decodeAudio16kMono(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean,
    ): AudioDecodeResult? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var output: DataOutputStreamLittleEndian? = null
        var pcmFile: File? = null
        var cancelled = false
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    inputFormat = candidate
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) return null
            extractor.selectTrack(trackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Audio track has no MIME type")
            var sourceSampleRate = inputFormat.intOrZero(MediaFormat.KEY_SAMPLE_RATE)
            var sourceChannels = inputFormat.intOrZero(MediaFormat.KEY_CHANNEL_COUNT)
            if (sourceSampleRate <= 0 || sourceChannels <= 0) {
                throw IllegalStateException("Audio track has invalid sample-rate or channel metadata")
            }

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            pcmFile = File(context.cacheDir, "audio_${System.nanoTime()}.pcm")
            val pcmOutput = DataOutputStreamLittleEndian(BufferedOutputStream(FileOutputStream(pcmFile), 1 shl 16))
            output = pcmOutput
            var resampler = StreamResampler(sourceSampleRate, TARGET_SAMPLE_RATE)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val envelope = ArrayList<Float>()
            var windowSumSquares = 0.0
            var windowCount = 0
            var writtenTargetSamples = 0L
            val envelopeWindowSamples = TARGET_SAMPLE_RATE * ENVELOPE_STEP_MS / 1_000
            val durationUs = inputFormat.longOrZero(MediaFormat.KEY_DURATION)
            val maxTimelineUs = if (durationUs > 0L) {
                if (durationUs > Long.MAX_VALUE - AUDIO_PTS_TOLERANCE_US) Long.MAX_VALUE
                else durationUs + AUDIO_PTS_TOLERANCE_US
            } else {
                MAX_UNKNOWN_AUDIO_TIMELINE_US
            }
            val maxTargetSamples = microsToTargetSamples(maxTimelineUs)
            val maxOverlapSamples = microsToTargetSamples(MAX_PTS_OVERLAP_US)

            fun finishEnvelopeWindow() {
                if (windowCount > 0) {
                    envelope.add(sqrt(windowSumSquares / windowCount).toFloat())
                    windowSumSquares = 0.0
                    windowCount = 0
                }
            }

            fun requireWritableSamples(count: Long) {
                if (count < 0L || count > maxTargetSamples - writtenTargetSamples) {
                    throw IllegalStateException("Audio decoder produced a timeline longer than its declared duration")
                }
            }

            fun writeResampled(sample: Float) {
                requireWritableSamples(1L)
                val safeSample = sample.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
                pcmOutput.writeFloatLe(safeSample)
                writtenTargetSamples++
                windowSumSquares += (safeSample * safeSample).toDouble()
                windowCount++
                if (windowCount >= envelopeWindowSamples) finishEnvelopeWindow()
            }

            fun writeSilentSamples(sampleCount: Long): Boolean {
                if (sampleCount <= 0L) return true
                requireWritableSamples(sampleCount)
                var remaining = sampleCount
                while (remaining > 0L) {
                    if (isCancelled()) {
                        cancelled = true
                        return false
                    }
                    val chunkSamples = min(remaining, SILENCE_WRITE_CHUNK_SAMPLES.toLong()).toInt()
                    pcmOutput.writeZeroFloats(chunkSamples)
                    writtenTargetSamples += chunkSamples
                    var envelopeSamples = chunkSamples
                    if (windowCount > 0) {
                        val untilWindowEnd = min(envelopeSamples, envelopeWindowSamples - windowCount)
                        windowCount += untilWindowEnd
                        envelopeSamples -= untilWindowEnd
                        if (windowCount == envelopeWindowSamples) finishEnvelopeWindow()
                    }
                    while (envelopeSamples >= envelopeWindowSamples) {
                        envelope.add(0f)
                        envelopeSamples -= envelopeWindowSamples
                    }
                    if (envelopeSamples > 0) windowCount += envelopeSamples
                    remaining -= chunkSamples
                }
                return true
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var decodedUs = 0L
            var firstDecodedUs: Long? = null

            while (!outputEos) {
                if (isCancelled()) {
                    cancelled = true
                    return null
                }
                if (!inputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Audio decoder returned a null input buffer")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            if (sampleSize > inputBuffer.capacity()) {
                                throw IllegalStateException("Audio sample exceeds decoder input buffer capacity")
                            }
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sourceSampleRate = outputFormat.intOrZero(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 } ?: sourceSampleRate
                        sourceChannels = outputFormat.intOrZero(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 } ?: sourceChannels
                        pcmEncoding = outputFormat.intOrZero(MediaFormat.KEY_PCM_ENCODING)
                            .takeIf { it != 0 } ?: AudioFormat.ENCODING_PCM_16BIT
                        resampler = StreamResampler(sourceSampleRate, TARGET_SAMPLE_RATE)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        try {
                            if (bufferInfo.size > 0) {
                                // PCM is written as a timeline, not merely a
                                // concatenation of decoder buffers. Preserve a
                                // genuine PTS gap as silence so transcript,
                                // waveform and silence-removal timestamps stay
                                // aligned with the source video. Bound malformed
                                // PTS values before they can create a gigantic
                                // cache file of invented silence.
                                val rawPresentationUs = bufferInfo.presentationTimeUs
                                if (rawPresentationUs < -AUDIO_PTS_TOLERANCE_US) {
                                    throw IllegalStateException("Audio decoder returned an invalid negative timestamp")
                                }
                                val presentationUs = rawPresentationUs.coerceAtLeast(0L)
                                if (presentationUs > maxTimelineUs) {
                                    throw IllegalStateException("Audio decoder timestamp exceeds track duration")
                                }
                                if (firstDecodedUs == null) firstDecodedUs = presentationUs
                                val relativePresentationUs = (presentationUs - requireNotNull(firstDecodedUs)).coerceAtLeast(0L)
                                val targetStartSample = microsToTargetSamples(relativePresentationUs)
                                val overlapSamples = TargetTimelineOverlapTrimmer.overlapSamples(
                                    writtenTargetSamples = writtenTargetSamples,
                                    targetStartSample = targetStartSample,
                                )
                                if (overlapSamples > maxOverlapSamples) {
                                    throw IllegalStateException("Audio decoder returned a discontinuous timestamp")
                                }
                                val missingSamples = (targetStartSample - writtenTargetSamples).coerceAtLeast(0L)
                                if (!writeSilentSamples(missingSamples)) return null

                                val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                    ?: throw IllegalStateException("Audio decoder returned a null output buffer")
                                // Decoder output timestamps identify the start
                                // of each PCM buffer. Some codecs emit small
                                // overlapping buffers; appending their entire
                                // payload would duplicate audio and shift the
                                // waveform/transcript timeline. Advance the
                                // resampler through every source sample, but
                                // drop only the already-written target samples.
                                val overlapTrimmer = TargetTimelineOverlapTrimmer(overlapSamples)
                                consumePcm(
                                    outputBuffer = outputBuffer,
                                    offset = bufferInfo.offset,
                                    size = bufferInfo.size,
                                    channels = sourceChannels,
                                    pcmEncoding = pcmEncoding,
                                    onMonoSample = { mono ->
                                        resampler.push(mono) { resampled ->
                                            overlapTrimmer.emit(resampled, ::writeResampled)
                                        }
                                    },
                                )
                                decodedUs = max(decodedUs, presentationUs)
                                if (durationUs > 0L) onProgress((decodedUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                }
            }

            if (windowCount > 0) envelope.add(sqrt(windowSumSquares / windowCount).toFloat())
            pcmOutput.close()
            output = null
            onProgress(1f)
            val firstUs = firstDecodedUs ?: 0L
            return AudioDecodeResult(
                pcmFile = requireNotNull(pcmFile),
                envelope = envelope,
                startTimeMs = firstUs / 1_000L,
                sampleRate = TARGET_SAMPLE_RATE,
                // PTS marks the beginning of a decoder buffer, so derive the
                // duration from the completed resampled PCM instead of losing
                // the final buffer's duration.
                decodedDurationMs = requireNotNull(pcmFile).length() / PCM_FLOAT_BYTES * 1_000L / TARGET_SAMPLE_RATE,
            )
        } catch (t: Throwable) {
            pcmFile?.delete()
            throw t
        } finally {
            runCatching { output?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            if (cancelled) pcmFile?.delete()
        }
    }

    private fun consumePcm(
        outputBuffer: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        pcmEncoding: Int,
        onMonoSample: (Float) -> Unit,
    ) {
        require(channels in 1..32) { "Audio decoder returned an unsupported channel count" }
        val buffer = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val end = offset.toLong() + size.toLong()
        if (offset < 0 || size < 0 || end > buffer.capacity()) {
            throw IllegalStateException("Audio decoder returned invalid buffer bounds")
        }
        buffer.position(offset)
        buffer.limit(end.toInt())
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2 // MediaCodec PCM output defaults to signed 16-bit.
        }
        val bytesPerFrame = bytesPerSample * channels
        while (buffer.remaining() >= bytesPerFrame) {
            var mono = 0f
            repeat(channels) {
                mono += when (pcmEncoding) {
                    // Do not let one malformed floating PCM value poison the
                    // streaming resampler's previous-sample state and turn the
                    // remainder of an otherwise valid track into silence.
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                        .takeIf { it.isFinite() }
                        ?.coerceIn(-1f, 1f)
                        ?: 0f
                    AudioFormat.ENCODING_PCM_32BIT -> (buffer.int / 2_147_483_648.0f).coerceIn(-1f, 1f)
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val b0 = buffer.get().toInt() and 0xff
                        val b1 = buffer.get().toInt() and 0xff
                        val b2 = buffer.get().toInt()
                        ((b0 or (b1 shl 8) or (b2 shl 16)) / 8_388_608f).coerceIn(-1f, 1f)
                    }
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                    else -> buffer.short / 32_768f
                }
            }
            onMonoSample(
                (mono / channels)
                    .takeIf { it.isFinite() }
                    ?.coerceIn(-1f, 1f)
                    ?: 0f,
            )
        }
    }

    /**
     * Streaming linear resampler with a callback output API, avoiding the
     * per-source-sample List allocation in the previous implementation.
     */
    class StreamResampler(private val sourceRate: Int, private val targetRate: Int) {
        private val sourceSamplesPerOutput = sourceRate.toDouble() / targetRate.toDouble()
        private var sourceIndex = 0L
        private var nextOutputPosition = 0.0
        private var hasPrevious = false
        private var previous = 0f

        init {
            require(sourceRate > 0 && targetRate > 0)
        }

        fun push(sample: Float, emit: (Float) -> Unit) {
            if (!hasPrevious) {
                hasPrevious = true
                previous = sample
                emit(sample)
                nextOutputPosition = sourceSamplesPerOutput
                return
            }
            val currentIndex = sourceIndex + 1L
            while (nextOutputPosition <= currentIndex.toDouble()) {
                val fraction = (nextOutputPosition - sourceIndex.toDouble()).toFloat().coerceIn(0f, 1f)
                emit(previous + (sample - previous) * fraction)
                nextOutputPosition += sourceSamplesPerOutput
            }
            previous = sample
            sourceIndex = currentIndex
        }
    }

    private fun rotateFrameIfNeeded(retriever: MediaMetadataRetriever, frame: Bitmap): Bitmap {
        val rotation = normalizeRotation(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
        )
        if (rotation == 0) return frame
        val encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        // Some platform retrievers already return an upright bitmap. Compare
        // aspect ratios rather than exact dimensions because getScaledFrameAtTime
        // intentionally returns a bounded thumbnail.
        val leftProduct = frame.width.toLong() * encodedHeight
        val rightProduct = frame.height.toLong() * encodedWidth
        val ratioDelta = abs(leftProduct - rightProduct)
        val ratioTolerance = max(leftProduct, rightProduct).coerceAtLeast(1L) / 50L // 2%
        val matchesEncodedOrientation = encodedWidth > 0 && encodedHeight > 0 && ratioDelta <= ratioTolerance
        if (!matchesEncodedOrientation) return frame
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { if (it !== frame) frame.recycle() }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (maxDim <= 0) return bitmap
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(16),
            (bitmap.height * scale).toInt().coerceAtLeast(16),
            true,
        ).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Converts microseconds without overflowing an intermediate multiplication. */
    private fun microsToTargetSamples(micros: Long): Long {
        val safeMicros = micros.coerceAtLeast(0L)
        return (safeMicros / 1_000_000L) * TARGET_SAMPLE_RATE +
            (safeMicros % 1_000_000L) * TARGET_SAMPLE_RATE / 1_000_000L
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    private fun MediaFormat.longOrZero(key: String): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(0L) else 0L

    private fun MediaFormat.numberAsFloat(key: String): Float =
        if (containsKey(key)) runCatching { getFloat(key) }.getOrElse {
            runCatching { getInteger(key).toFloat() }.getOrDefault(0f)
        } else 0f

    private fun normalizeRotation(value: Int): Int {
        val normalized = ((value % 360) + 360) % 360
        return when (normalized) {
            0, 90, 180, 270 -> normalized
            else -> 0
        }
    }

    private class DataOutputStreamLittleEndian(private val output: BufferedOutputStream) : AutoCloseable {
        private val bytes = ByteBuffer.allocate(VideoManager.PCM_FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        private val zeros = ByteArray(VideoManager.SILENCE_WRITE_CHUNK_SAMPLES * VideoManager.PCM_FLOAT_BYTES)

        fun writeFloatLe(value: Float) {
            bytes.clear()
            bytes.putFloat(value)
            output.write(bytes.array(), 0, VideoManager.PCM_FLOAT_BYTES)
        }

        /** Writes float PCM silence in blocks instead of one JVM call per sample. */
        fun writeZeroFloats(sampleCount: Int) {
            require(sampleCount >= 0)
            var remaining = sampleCount
            val samplesPerBlock = zeros.size / VideoManager.PCM_FLOAT_BYTES
            while (remaining > 0) {
                val blockSamples = min(remaining, samplesPerBlock)
                output.write(zeros, 0, blockSamples * VideoManager.PCM_FLOAT_BYTES)
                remaining -= blockSamples
            }
        }

        override fun close() = output.close()
    }
}
