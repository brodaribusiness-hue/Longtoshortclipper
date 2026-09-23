package com.shortsclipper.video

import android.content.Context
import android.graphics.Bitmap
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * URI-based video access. Metadata is read via MediaMetadataRetriever +
 * MediaExtractor; audio is streamed through a decoder to a temp PCM file
 * (16 kHz mono float32) - a long video is NEVER loaded into RAM at once.
 */
class AudioDecodeCancelledException : Exception("Audio decode cancelled")

object VideoManager {

    const val TARGET_SAMPLE_RATE = 16000
    private const val ENVELOPE_STEP_MS = 100

    data class AudioDecodeResult(
        val pcmFile: File,
        /** RMS loudness per [ProjectState.envelopeStepMs], normalized 0..1. */
        val envelope: List<Float>,
        val sampleRate: Int,
        val decodedDurationMs: Long,
    )

    fun readMetadata(context: Context, uri: Uri, displayName: String): VideoSource {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
            var fallbackWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var fallbackHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            var fallbackRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            var videoMime: String? = null
            var audioMime: String? = null
            var fps = captureFps
            var width = 0
            var height = 0
            var rotation = 0
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && videoMime == null) {
                        videoMime = mime
                        if (format.containsKey(MediaFormat.KEY_WIDTH) && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            width = format.getInteger(MediaFormat.KEY_WIDTH)
                            height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        }
                        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                        }
                        if (fps <= 0f && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            fps = runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
                                .getOrElse {
                                    runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f)
                                }
                        }
                    } else if (mime.startsWith("audio/") && audioMime == null) {
                        audioMime = mime
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
            if (fps <= 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && durationMs > 0) {
                val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                if (frameCount > 0) fps = frameCount * 1000f / durationMs
            }

            val rotated = rotation == 90 || rotation == 270
            return VideoSource(
                uri = uri.toString(),
                displayName = displayName,
                durationMs = durationMs,
                width = width,
                height = height,
                rotationDegrees = rotation,
                displayWidth = if (rotated) height else width,
                displayHeight = if (rotated) width else height,
                fps = fps,
                hasAudio = hasAudio || audioMime != null,
                videoMimeType = videoMime,
                audioMimeType = audioMime,
            )
        } finally {
            retriever.release()
        }
    }

    fun extractFrame(context: Context, uri: Uri, timeMs: Long, maxDim: Int = 512, accurate: Boolean = true): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val option = if (accurate) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val frame = retriever.getFrameAtTime(timeMs * 1000, option) ?: return null
            val scale = maxDim.toFloat() / maxOf(frame.width, frame.height)
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(16),
                    (frame.height * scale).toInt().coerceAtLeast(16),
                    true,
                )
                if (scaled !== frame) frame.recycle()
                scaled
            } else frame
        } finally {
            retriever.release()
        }
    }

    /**
     * Streams the audio track through MediaCodec into 16 kHz mono float PCM on
     * disk, computing the loudness envelope along the way. Returns null when
     * the video has no audio track.
     */
    fun decodeAudio16kMono(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AudioDecodeResult? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var pcmFile: File? = null
        var out: DataOutputStreamLittleEndian? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            pcmFile = File(context.cacheDir, "audio_${System.nanoTime()}.pcm")
            val pcmOut = DataOutputStreamLittleEndian(BufferedOutputStream(FileOutputStream(pcmFile), 1 shl 16))
            out = pcmOut

            var srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            var resampler = StreamResampler(srcSampleRate, TARGET_SAMPLE_RATE)

            val envelope = ArrayList<Float>()
            var windowSumSquares = 0.0
            var windowCount = 0
            val windowSamples = TARGET_SAMPLE_RATE * ENVELOPE_STEP_MS / 1000

            fun consume(sample: Float) {
                windowSumSquares += (sample * sample).toDouble()
                windowCount++
                if (windowCount >= windowSamples) {
                    envelope.add(sqrt(windowSumSquares / windowCount).toFloat())
                    windowSumSquares = 0.0
                    windowCount = 0
                }
                for (o in resampler.push(sample)) pcmOut.writeFloatLe(o)
            }

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var decodedUs = 0L
            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

            while (!sawOutputEos) {
                if (isCancelled()) throw AudioDecodeCancelledException()
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        srcSampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        resampler = StreamResampler(srcSampleRate, TARGET_SAMPLE_RATE)
                    }
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && info.size > 0) {
                            outBuffer.order(ByteOrder.nativeOrder())
                            if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                                val samples = outBuffer.asFloatBuffer()
                                val start = info.offset / 4
                                val frames = (info.size / 4) / srcChannels
                                for (i in 0 until frames) {
                                    var mono = 0f
                                    val base = start + i * srcChannels
                                    for (c in 0 until srcChannels) mono += samples.get(base + c)
                                    consume(mono / srcChannels)
                                }
                            } else {
                                val samples = outBuffer.asShortBuffer()
                                val start = info.offset / 2
                                val frames = (info.size / 2) / srcChannels
                                for (i in 0 until frames) {
                                    var mono = 0f
                                    val base = start + i * srcChannels
                                    for (c in 0 until srcChannels) mono += samples.get(base + c)
                                    consume((mono / srcChannels) / 32768f)
                                }
                            }
                            decodedUs = max(decodedUs, info.presentationTimeUs)
                            if (durationUs > 0) onProgress(min(1f, decodedUs.toFloat() / durationUs))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
            if (windowCount > 0) envelope.add(sqrt(windowSumSquares / windowCount).toFloat())
            pcmOut.close()
            out = null

            return AudioDecodeResult(pcmFile, envelope, TARGET_SAMPLE_RATE, decodedUs / 1000)
        } catch (t: Throwable) {
            pcmFile?.delete()
            throw t
        } finally {
            runCatching { out?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Linear-interpolation resampler that streams sample-by-sample. */
    class StreamResampler(private val srcRate: Int, private val dstRate: Int) {
        private val step = srcRate.toFloat() / dstRate
        private var position = 0f
        private var hasPrev = false
        private var prev = 0f

        fun push(sample: Float): List<Float> {
            if (!hasPrev) {
                hasPrev = true
                prev = sample
                position = 0f
                return if (step <= 1f) listOf(sample) else emptyList()
            }
            val out = ArrayList<Float>(2)
            // Emit every dst sample position that falls between prev and sample.
            while (position < 1f) {
                val v = prev + (sample - prev) * position
                out.add(v)
                position += step
            }
            position -= 1f
            prev = sample
            return out
        }
    }

    private class DataOutputStreamLittleEndian(private val out: BufferedOutputStream) : AutoCloseable {
        private val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        fun writeFloatLe(v: Float) {
            buf.rewind()
            buf.putFloat(v)
            out.write(buf.array(), 0, 4)
        }

        override fun close() = out.close()
    }
}
