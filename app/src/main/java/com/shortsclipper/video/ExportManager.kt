package com.shortsclipper.video

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.shortsclipper.model.ClipSegment
import com.shortsclipper.model.ProjectState
import com.shortsclipper.model.QualityMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import com.google.common.collect.ImmutableList
import kotlin.math.roundToInt

class ExportCancelledException : Exception("Export cancelled")

/** Exception thrown when free space is clearly insufficient for the planned output. */
class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long) :
    IOException("Insufficient storage: need ~${requiredBytes / (1024 * 1024)} MB, have ${availableBytes / (1024 * 1024)} MB")

/**
 * Media3 Transformer export. The encoded source is decoded in display
 * orientation by Media3; CropCalculator therefore receives the same upright
 * dimensions and source-relative time basis as the source preview.
 */
@OptIn(UnstableApi::class)
class ExportManager(private val context: Context) {

    data class ExportPlan(
        val outWidth: Int,
        val outHeight: Int,
        /** AVC whenever the device supports it; HEVC only as a fallback. */
        val videoMimeType: String,
        val videoBitrateBps: Int,
        val segments: List<ClipSegment>,
    ) {
        val estimatedBytes: Long
            get() = segments.sumOf { it.durationMs } * (videoBitrateBps.toLong() / 8L) / 1_000L + 2L * 1024L * 1024L
    }

    companion object {
        const val GALLERY_FOLDER = "ShortsClipper"
        // 9:16 output heights. Include 1920 so an upright 1080x1920 source
        // can remain full resolution when the AVC encoder supports it.
        private val HEIGHT_TIERS = intArrayOf(2160, 1920, 1440, 1280, 1080, 960, 720, 640, 540, 480, 360)
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }

    /** Capability-checked, broadly compatible output plan. */
    fun computePlan(state: ProjectState): ExportPlan {
        val source = state.source ?: throw IllegalStateException("No source video selected")
        require(source.durationMs > 0L && source.displayWidth > 0 && source.displayHeight > 0) {
            "Source video metadata is incomplete"
        }
        val segments = state.exportSegments
        if (segments.isEmpty()) throw IllegalStateException("Choose a non-empty clip before exporting")
        require(segments.all { it.startMs >= 0L && it.endMs > it.startMs && it.endMs <= source.durationMs }) {
            "Selected clip contains invalid source time ranges"
        }

        val preferredHeight = when (state.exportQuality) {
            QualityMode.SAME_AS_ORIGINAL -> source.displayHeight
            QualityMode.HIGH_QUALITY -> minOf(source.displayHeight, 1_920)
        }.coerceAtLeast(2)

        val (mime, width, height) = selectSupportedOutput(preferredHeight)
        val fps = source.fps.takeIf { it.isFinite() && it >= 12f }?.coerceAtMost(60f) ?: 30f
        val bitrate = (width * height * fps * 0.14f).roundToInt().coerceIn(2_000_000, 28_000_000)
        return ExportPlan(width, height, mime, bitrate, segments)
    }

    private fun selectSupportedOutput(maxHeight: Int): Triple<String, Int, Int> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // AVC is deliberately first even when the input happens to be HEVC.
        // It has the broadest Gallery/social/device decode compatibility.
        val candidateMimes = listOf("video/avc", "video/hevc")
        for (mime in candidateMimes) {
            if (!hasEncoder(codecList, mime)) continue
            val heights = (HEIGHT_TIERS.filter { it <= maxHeight } + evenDown(maxHeight)).distinct()
            for (height in heights) {
                val width = evenDown((height * CropCalculator.TARGET_ASPECT).roundToInt()).coerceAtLeast(2)
                if (sizeSupported(codecList, mime, width, height)) return Triple(mime, width, height)
            }
        }
        throw IllegalStateException("This device has no AVC/HEVC encoder supporting a 9:16 export size")
    }

    private fun hasEncoder(codecList: MediaCodecList, mime: String): Boolean =
        codecList.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    private fun sizeSupported(codecList: MediaCodecList, mime: String, width: Int, height: Int): Boolean =
        codecList.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && runCatching {
                info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true
            }.getOrDefault(false)
        }

    private fun evenDown(value: Int): Int = when {
        value <= 2 -> 2
        value % 2 == 0 -> value
        else -> value - 1
    }

    /**
     * Runs Transformer on the main application looper (required by Media3's
     * listener/transformer threading model) while actual decode/encode remains
     * asynchronous. Partial/cancelled outputs are deleted before return.
     */
    suspend fun export(
        state: ProjectState,
        plan: ExportPlan,
        outFile: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): File = withContext(Dispatchers.Main.immediate) {
        val source = state.source ?: throw IllegalStateException("No source video selected")
        if (isCancelled()) throw ExportCancelledException()
        outFile.parentFile?.mkdirs()
        outFile.delete()

        val availableBytes = StatFs(context.cacheDir.absolutePath).availableBytes
        if (availableBytes < plan.estimatedBytes * 12L / 10L) {
            throw InsufficientStorageException(plan.estimatedBytes, availableBytes)
        }

        val completion = CompletableDeferred<File>()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(plan.videoBitrateBps).build(),
            )
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(plan.videoMimeType)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (isCancelled()) completion.completeExceptionally(ExportCancelledException())
                    else completion.complete(outFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    completion.completeExceptionally(
                        if (isCancelled()) ExportCancelledException() else exportException,
                    )
                }
            })
            .build()

        try {
            val captionSegments = if (state.captionsEnabled) state.resolvedCaptionSegments() else emptyList()
            val presentation = Presentation.createForWidthAndHeight(
                plan.outWidth,
                plan.outHeight,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
            )
            val items = plan.segments.map { segment ->
                val mediaItem = MediaItem.Builder()
                    .setUri(source.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(segment.startMs)
                            .setEndPositionMs(segment.endMs)
                            .build(),
                    )
                    .build()
                val matrix = GlMatrixTransformation { presentationTimeUs ->
                    val sourceTimeMs = segment.startMs + presentationTimeUs / 1_000L
                    val rect = CropCalculator.rectAt(state, sourceTimeMs)
                    CropCalculator.toVertexMatrix(
                        rect,
                        source.displayWidth,
                        source.displayHeight,
                        plan.outWidth,
                        plan.outHeight,
                    )
                }
                val videoEffects = ArrayList<Effect>(3).apply {
                    add(matrix)
                    add(presentation)
                    if (captionSegments.isNotEmpty()) {
                        add(
                            OverlayEffect(
                                ImmutableList.of(
                                    TimedCaptionOverlay(
                                        segments = captionSegments,
                                        sourceSegmentStartMs = segment.startMs,
                                        sourceSegmentEndMs = segment.endMs,
                                    ),
                                ),
                            ),
                        )
                    }
                }
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(androidx.media3.transformer.Effects(emptyList(), videoEffects))
                    .build()
            }
            val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
            transformer.start(composition, outFile.absolutePath)
            onProgress(0)

            val progressHolder = ProgressHolder()
            while (!completion.isCompleted) {
                if (isCancelled()) {
                    transformer.cancel()
                    completion.completeExceptionally(ExportCancelledException())
                    break
                }
                when (transformer.getProgress(progressHolder)) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> onProgress(progressHolder.progress.coerceIn(0, 99))
                }
                delay(200)
            }
            val completedFile = completion.await()
            if (isCancelled()) throw ExportCancelledException()
            withContext(Dispatchers.IO) {
                validateCompletedOutput(
                    file = completedFile,
                    expectedDurationMs = plan.segments.sumOf { it.durationMs },
                    expectedWidth = plan.outWidth,
                    expectedHeight = plan.outHeight,
                    requireAudioTrack = source.hasAudio,
                )
            }
            onProgress(100)
            completedFile
        } catch (e: ExportCancelledException) {
            outFile.delete()
            throw e
        } catch (e: CancellationException) {
            outFile.delete()
            throw ExportCancelledException()
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        } finally {
            if (!completion.isCompleted) transformer.cancel()
            if (isCancelled()) outFile.delete()
        }
    }

    /** Rejects zero-byte, partial or structurally wrong files before Gallery insertion. */
    private fun validateCompletedOutput(
        file: File,
        expectedDurationMs: Long? = null,
        expectedWidth: Int? = null,
        expectedHeight: Int? = null,
        requireAudioTrack: Boolean = false,
    ) {
        if (!file.isFile || file.length() < 1_024L) {
            throw IOException("Exporter did not produce a complete video file")
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val videoFormat = formats.firstOrNull {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("Exported file has no video track")
            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                runCatching { videoFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            } else 0L
            if (durationUs <= 0L) throw IOException("Exported video has no duration")
            val expectedUs = expectedDurationMs?.times(1_000L)
            if (expectedUs != null) {
                // Transformer may trim a few boundary frames, but a cancelled
                // mux is never allowed to masquerade as a completed short.
                val minimumExpectedUs = maxOf(250_000L, expectedUs * 75L / 100L - 750_000L)
                if (durationUs < minimumExpectedUs) {
                    throw IOException("Exported video duration is incomplete")
                }
            }

            if (expectedWidth != null && expectedHeight != null) {
                val encodedWidth = videoFormat.integerOrZero(MediaFormat.KEY_WIDTH)
                val encodedHeight = videoFormat.integerOrZero(MediaFormat.KEY_HEIGHT)
                val rotation = videoFormat.integerOrZero(MediaFormat.KEY_ROTATION).normalizedRotation()
                val displayWidth = if (rotation == 90 || rotation == 270) encodedHeight else encodedWidth
                val displayHeight = if (rotation == 90 || rotation == 270) encodedWidth else encodedHeight
                if (displayWidth != expectedWidth || displayHeight != expectedHeight) {
                    throw IOException(
                        "Exported video dimensions are ${displayWidth}x${displayHeight}; " +
                            "expected ${expectedWidth}x${expectedHeight}",
                    )
                }
            }

            val hasAudioTrack = formats.any { format ->
                format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (requireAudioTrack && !hasAudioTrack) {
                throw IOException("Exported video is missing its audio track")
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * Copies a validated completed export into Gallery. If cancellation or an
     * I/O failure happens during the copy, the pending MediaStore row/legacy
     * temp file is removed so no corrupt gallery entry is exposed.
     */
    fun saveToGallery(
        sourceFile: File,
        displayName: String,
        isCancelled: () -> Boolean,
    ): Uri {
        if (isCancelled()) throw ExportCancelledException()
        validateCompletedOutput(sourceFile)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreQ(sourceFile, displayName, isCancelled)
        } else {
            saveLegacy(sourceFile, displayName, isCancelled)
        }
    }

    /** Best-effort rollback for a save that completed just as its job was cancelled. */
    fun deleteGalleryOutput(uri: Uri) {
        runCatching {
            when (uri.scheme?.lowercase()) {
                "content" -> context.contentResolver.delete(uri, null, null)
                "file" -> uri.path?.let(::File)?.delete()
                else -> Unit
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStoreQ(file: File, displayName: String, isCancelled: () -> Boolean): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName.ensureMp4Name())
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$GALLERY_FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var uri: Uri? = null
        try {
            uri = resolver.insert(collection, values) ?: throw IOException("MediaStore could not create a gallery entry")
            resolver.openOutputStream(uri, "w")?.use { output ->
                copyFileCancellable(file, output, isCancelled)
            } ?: throw IOException("Could not open the gallery output stream")
            if (isCancelled()) throw ExportCancelledException()
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not finalize the gallery entry")
            // Cancellation can arrive in the tiny window after MediaStore has
            // exposed the row. Delete it before returning so a cancelled export
            // never appears as a completed gallery video.
            if (isCancelled()) throw ExportCancelledException()
            return uri
        } catch (t: Throwable) {
            uri?.let { resolver.delete(it, null, null) }
            throw t
        }
    }

    private fun saveLegacy(file: File, displayName: String, isCancelled: () -> Boolean): Uri {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Storage permission is required to save on Android 9 and below")
        }
        @Suppress("DEPRECATION")
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val directory = File(moviesDir, GALLERY_FOLDER)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create Movies/$GALLERY_FOLDER")
        val requestedName = displayName.ensureMp4Name()
        val stem = requestedName.substringBeforeLast('.', requestedName)
        val extension = requestedName.substringAfterLast('.', "mp4")
        var suffix = 0
        var destination = File(directory, requestedName)
        while (destination.exists()) {
            suffix++
            destination = File(directory, "${stem}_$suffix.$extension")
        }
        val partial = File(directory, ".${destination.name}.part")
        partial.delete()
        var finalized = false
        try {
            FileOutputStream(partial).use { stream ->
                BufferedOutputStream(stream, COPY_BUFFER_BYTES).use { output ->
                    FileInputStream(file).use { input ->
                        copyStreamCancellable(input, output, isCancelled)
                    }
                    output.flush()
                    stream.fd.sync()
                }
            }
            if (isCancelled()) throw ExportCancelledException()
            if (!partial.renameTo(destination)) throw IOException("Could not finalize the gallery export")
            finalized = true
            // Keep the cancellation guard on both sides of the final rename;
            // a valid-but-cancelled legacy file must not be left in Movies.
            if (isCancelled()) throw ExportCancelledException()
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf("video/mp4"), null)
            if (isCancelled()) throw ExportCancelledException()
            return Uri.fromFile(destination)
        } catch (t: Throwable) {
            partial.delete()
            if (finalized) destination.delete()
            throw t
        }
    }

    private fun copyFileCancellable(file: File, output: java.io.OutputStream, isCancelled: () -> Boolean) {
        FileInputStream(file).use { input -> copyStreamCancellable(input, output, isCancelled) }
    }

    private fun copyStreamCancellable(input: java.io.InputStream, output: java.io.OutputStream, isCancelled: () -> Boolean) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            if (isCancelled()) throw ExportCancelledException()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    private fun MediaFormat.integerOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    private fun Int.normalizedRotation(): Int {
        val normalized = ((this % 360) + 360) % 360
        return if (normalized == 0 || normalized == 90 || normalized == 180 || normalized == 270) normalized else 0
    }

    private fun String.ensureMp4Name(): String = if (endsWith(".mp4", ignoreCase = true)) this else "$this.mp4"
}
