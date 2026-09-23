package com.shortsclipper.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import com.shortsclipper.model.ClipSegment
import com.shortsclipper.model.ProjectState
import com.shortsclipper.model.QualityMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

class ExportCancelledException : Exception("Export cancelled")

/** Exception thrown when free space is clearly insufficient for the planned output. */
class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long) :
    IOException("Insufficient storage: need ~${requiredBytes / (1024 * 1024)} MB, have ${availableBytes / (1024 * 1024)} MB")

/**
 * High-quality export using AndroidX Media3 Transformer with the SAME
 * transformation state as the preview (CropCalculator). Video is re-encoded
 * with the 9:16 crop/pan/zoom applied per frame; audio is preserved.
 */
@OptIn(UnstableApi::class)
class ExportManager(private val context: Context) {

    data class ExportPlan(
        val outWidth: Int,
        val outHeight: Int,
        val videoMimeType: String,
        val videoBitrateBps: Int,
        val segments: List<ClipSegment>,
    ) {
        val estimatedBytes: Long
            get() = segments.sumOf { it.durationMs } * (videoBitrateBps.toLong() / 8) / 1000 + 1024 * 1024
    }

    companion object {
        const val GALLERY_FOLDER = "ShortsClipper"
        private val HEIGHT_TIERS = intArrayOf(2160, 1440, 1080, 720, 540, 480, 360)
    }

    /** Device-capability aware export plan. */
    fun computePlan(state: ProjectState): ExportPlan {
        val src = state.source ?: throw IllegalStateException("No source video selected")
        val segments = state.exportSegments
        if (segments.isEmpty()) throw IllegalStateException("Invalid timeline selection")
        val displayH = src.displayHeight.coerceAtLeast(2)

        val preferredH = when (state.exportQuality) {
            QualityMode.SAME_AS_ORIGINAL -> displayH
            QualityMode.HIGH_QUALITY -> minOf(displayH, 1920)
        }.coerceAtLeast(360)

        val mime = pickEncoderMime(src.videoMimeType)
        val targetH = pickSupportedHeight(mime, evenDown(preferredH))
        val targetW = evenDown((targetH * CropCalculator.TARGET_ASPECT).roundToInt()).coerceAtLeast(176)
        val fps = if (src.fps > 1f) src.fps else 30f
        val bitrate = (targetW * targetH * fps * 0.14f).roundToInt().coerceIn(4_000_000, 28_000_000)
        return ExportPlan(targetW, targetH, mime, bitrate, segments)
    }

    private fun pickEncoderMime(sourceMime: String?): String {
        val candidates = ArrayList<String>()
        if (sourceMime == "video/hevc" || sourceMime == "video/avc") candidates.add(sourceMime)
        candidates.add("video/avc")
        candidates.add("video/hevc")
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return candidates.firstOrNull { candidate ->
            list.codecInfos.any { info -> info.isEncoder && info.supportedTypes.contains(candidate) }
        } ?: "video/avc"
    }

    private fun pickSupportedHeight(mime: String, maxHeight: Int): Int {
        val tier = HEIGHT_TIERS.firstOrNull { it <= maxHeight && sizeSupported(mime, evenDown((it * CropCalculator.TARGET_ASPECT).roundToInt()), it) }
        return (tier ?: minOf(maxHeight, 360)).coerceAtMost(maxHeight)
    }

    private fun sizeSupported(mime: String, width: Int, height: Int): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.contains(mime) && runCatching {
                info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(width, height)
            }.getOrDefault(false)
        }
    }

    private fun evenDown(v: Int): Int = if (v % 2 == 0) v else v - 1

    /**
     * Runs the transformation and returns the output file. Never blocks the
     * main thread; reports 0..100 progress; supports cancellation.
     */
    suspend fun export(
        state: ProjectState,
        plan: ExportPlan,
        outFile: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): File = withContext(Dispatchers.Default) {
        val src = state.source!!
        val displayW = src.displayWidth
        val displayH = src.displayHeight

        val stat = StatFs(context.cacheDir.absolutePath)
        val available = stat.availableBytes
        if (available < plan.estimatedBytes * 12 / 10) {
            throw InsufficientStorageException(plan.estimatedBytes, available)
        }

        val result = CompletableDeferred<File>()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(plan.videoBitrateBps).build()
            )
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(plan.videoMimeType)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    result.complete(outFile)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (isCancelled()) {
                        result.completeExceptionally(ExportCancelledException())
                    } else {
                        result.completeExceptionally(exportException)
                    }
                }
            })
            .build()

        val items = plan.segments.map { segment ->
            val mediaItem = MediaItem.Builder()
                .setUri(src.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(segment.startMs)
                        .setEndPositionMs(segment.endMs)
                        .build()
                )
                .build()
            val matrix = GlMatrixTransformation { presentationTimeUs ->
                val sourceTimeMs = segment.startMs + presentationTimeUs / 1000
                val rect = CropCalculator.rectAt(state, sourceTimeMs)
                CropCalculator.toVertexMatrix(rect, displayW, displayH, plan.outWidth, plan.outHeight)
            }
            val presentation = Presentation.createForWidthAndHeight(
                plan.outWidth, plan.outHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
            EditedMediaItem.Builder(mediaItem)
                .setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(matrix, presentation)))
                .build()
        }

        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        transformer.start(composition, outFile.absolutePath)

        try {
            val holder = ProgressHolder()
            while (true) {
                if (isCancelled() && result.isActive) {
                    transformer.cancel()
                }
                val progressState = transformer.getProgress(holder)
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress.coerceIn(0, 100))
                }
                if (result.isCompleted) break
                delay(200)
            }
            result.await()
        } finally {
            if (result.isActive) transformer.cancel()
        }
    }

    /** Copies the exported file into the standard gallery via MediaStore. */
    fun saveToGallery(sourceFile: File, displayName: String): Uri {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreQ(resolver, sourceFile, displayName)
        } else {
            saveLegacy(sourceFile, displayName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStoreQ(
        resolver: android.content.ContentResolver,
        file: File,
        displayName: String,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$GALLERY_FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Could not open output stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) == 0) {
                throw IOException("Could not publish the exported video")
            }
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun saveLegacy(file: File, displayName: String): Uri {
        @Suppress("DEPRECATION")
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(moviesDir, GALLERY_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, displayName)
        try {
            file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DATA, dest.absolutePath)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        }
        @Suppress("DEPRECATION")
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: Uri.fromFile(dest)
    }
}
