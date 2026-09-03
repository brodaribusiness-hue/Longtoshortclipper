package com.example.longtoshortclipper.ffmpeg

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import java.io.File
import kotlin.math.roundToInt

object FFmpegHelper {

    /**
     * Video ki real resolution nikaalo (MediaMetadataRetriever se).
     */
    fun getVideoDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            Pair(w, h)
        } catch (e: Exception) {
            Pair(1920, 1080)
        } finally {
            retriever.release()
        }
    }

    /**
     * Video ki duration seconds me nikaalo (3 hours tak support).
     */
    fun getVideoDurationSeconds(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ((durStr?.toLongOrNull() ?: 0L) / 1000L).toInt()
        } catch (e: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * Clip export — original quality ke kareeb (CRF 18 + audio copy).
     * Command: -ss START -to END -i input -filter:v crop=w:h:x:y
     *          -c:v libx264 -crf 18 -preset ultrafast -c:a copy output.mp4
     *
     * onProgress: 0f..1f (Statistics callback se bytes processed ka estimate)
     */
    fun exportClip(
        inputFile: File,
        outputDir: File,
        startSeconds: Int,
        endSeconds: Int,
        cropW: Int,
        cropH: Int,
        cropX: Int,
        cropY: Int,
        onProgress: (Float) -> Unit
    ): String {
        val outputFile = File(outputDir, "clip_${System.currentTimeMillis()}.mp4")

        val cmd = listOf(
            "-ss", startSeconds.toString(),
            "-to", endSeconds.toString(),
            "-i", inputFile.absolutePath,
            "-filter:v", "crop=cropW:cropW:cropW:cropH:cropX:cropX:cropX:cropY",
            "-c:v", "libx264",
            "-crf", "18",
            "-preset", "ultrafast",
            "-c:a", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath
        ).joinToString(" ")

        return try {
            val session: FFmpegSession = FFmpegKit.execute(cmd)
            if (returnCodeIsSuccess(session)) {
                "✅ Export Done! File: ${outputFile.absolutePath}"
            } else {
                "❌ Export fail hua: ${session.failStackTrace ?: "Unknown error"}"
            }
        } catch (e: Exception) {
            "❌ Export error: ${e.message}"
        }
    }

    private fun returnCodeIsSuccess(session: FFmpegSession): Boolean {
        return ReturnCode.isSuccess(session.returnCode)
    }

    /**
     * Progress helper — FFmpeg statistics se estimate karta hai.
     * (Simple version: session complete hone par 1f set hota hai.
     *  Real-time progress ke liye StatisticsCallback use hota hai jab
     *  hum video ki total size jaante hain.)
     */
    fun exportClipWithProgress(
        inputFile: File,
        outputDir: File,
        startSeconds: Int,
        endSeconds: Int,
        cropW: Int,
        cropH: Int,
        cropX: Int,
        cropY: Int,
        totalOutputSizeEstimateBytes: Long,
        onProgress: (Float) -> Unit
    ): String {
        val outputFile = File(outputDir, "clip_${System.currentTimeMillis()}.mp4")

        val cmd = listOf(
            "-ss", startSeconds.toString(),
            "-to", endSeconds.toString(),
            "-i", inputFile.absolutePath,
            "-filter:v", "crop=cropW:cropW:cropW:cropH:cropX:cropX:cropX:cropY",
            "-c:v", "libx264", "-crf", "18", "-preset", "ultrafast",
            "-c:a", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath
        ).joinToString(" ")

        return try {
            val session = FFmpegKit.executeWithStatisticsCallback(cmd) { stats ->
                // time (ms) based progress
                val processedMs = stats.time.toDouble()
                val totalMs = (endSeconds - startSeconds) * 1000.0
                if (totalMs > 0) {
                    val p = (processedMs / totalMs).toFloat().coerceIn(0f, 1f)
                    onProgress(p)
                }
            }
            if (ReturnCode.isSuccess(session.returnCode)) {
                onProgress(1f)
                "✅ Export Done! File: ${outputFile.absolutePath}"
            } else {
                "❌ Export fail hua: ${session.failStackTrace ?: "Unknown error"}"
            }
        } catch (e: Exception) {
            "❌ Export error: ${e.message}"
        }
    }
}
