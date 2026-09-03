package com.yourapp.clipper

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

sealed class ExportResult {
    data class Success(val outputFile: File) : ExportResult()
    data class Failure(val message: String) : ExportResult()
}

object FFmpegHelper {

    /**
     * Static crop export (Phase 1).
     */
    suspend fun exportClip(
        context: Context,
        inputFile: File,
        startMs: Long,
        endMs: Long,
        cropW: Int, cropH: Int, cropX: Int, cropY: Int,
        videoW: Int, videoH: Int,
        onProgress: (Int) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {

        val outFile = File(
            context.cacheDir,
            "clip_${System.currentTimeMillis()}.mp4"
        )

        val startSec = startMs / 1000.0
        val endSec = endMs / 1000.0

        val fullFrame = cropW >= videoW && cropH >= videoH && cropX <= 0 && cropY <= 0
        val filter = if (fullFrame) "" else "-filter:v crop=cropW:cropW:cropW:cropH:cropX:cropX:cropX:cropY"

        val cmd = buildString {
            append("-ss ").append(startSec)
            append(" -to ").append(endSec)
            append(" -i \"").append(inputFile.absolutePath).append("\" ")
            if (filter.isNotEmpty()) append(filter).append(" ")
            append("-c:v libx264 -crf 18 -preset ultrafast ")
            append("-c:a copy -movflags +faststart ")
            append("-y \"").append(outFile.absolutePath).append("\"")
        }

        val session = executeWithProgress(cmd, startSec, endSec, onProgress)

        return@withContext if (ReturnCode.isSuccess(session.returnCode)) {
            ExportResult.Success(outFile)
        } else {
            ExportResult.Failure(
                session.failStackTrace ?: "FFmpeg failed with code ${session.returnCode}"
            )
        }
    }

    /**
     * PHASE 2: Tracked export — crop x/y follows face over time via sendcmd.
     */
    suspend fun exportTrackedClip(
        context: Context,
        inputFile: File,
        startMs: Long,
        endMs: Long,
        cropW: Int, cropH: Int,
        videoW: Int, videoH: Int,
        trackPoints: List<TrackPoint>,
        onProgress: (Int) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {

        if (trackPoints.isEmpty()) {
            val cx = (videoW - cropW) / 2
            val cy = (videoH - cropH) / 2
            return@withContext exportClip(
                context, inputFile, startMs, endMs,
                cropW, cropH, cx, cy, videoW, videoH, onProgress
            )
        }

        // Generate sendcmd file
        val cmdFile = File(context.cacheDir, "track_${System.currentTimeMillis()}.txt")
        val sb = StringBuilder()
        trackPoints.forEach { tp ->
            val x = ((tp.centerX * videoW) - cropW / 2.0)
                .roundToInt().coerceIn(0, videoW - cropW)
            val y = ((tp.centerY * videoH) - cropH / 2.0)
                .roundToInt().coerceIn(0, videoH - cropH)
            val tSec = (tp.timeMs - startMs) / 1000.0
            sb.append(String.format("%.2f crop x %d, %.2f crop y %d;\n", tSec, x, tSec, y))
        }
        cmdFile.writeText(sb.toString())

        val outFile = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        val startSec = startMs / 1000.0
        val endSec = endMs / 1000.0

        val cmd = buildString {
            append("-ss ").append(startSec)
            append(" -to ").append(endSec)
            append(" -i \"").append(inputFile.absolutePath).append("\" ")
            append("-filter_complex \"")
            append("sendcmd=f='").append(cmdFile.absolutePath).append("'")
            append(",crop=cropW:cropW:cropW:cropH:0:0\" ")
            append("-c:v libx264 -crf 18 -preset ultrafast ")
            append("-c:a copy -movflags +faststart ")
            append("-y \"").append(outFile.absolutePath).append("\"")
        }

        val session = executeWithProgress(cmd, startSec, endSec, onProgress)
        cmdFile.delete()

        return@withContext if (ReturnCode.isSuccess(session.returnCode)) {
            ExportResult.Success(outFile)
        } else {
            ExportResult.Failure(
                session.failStackTrace ?: "Tracked export failed: ${session.returnCode}"
            )
        }
    }

    /** Async execution with statistics-based progress. */
    private suspend fun executeWithProgress(
        cmd: String,
        startSec: Double,
        endSec: Double,
        onProgress: (Int) -> Unit
    ): FFmpegSession = suspendCancellableCoroutine { cont ->
        FFmpegKit.executeAsync(
            cmd,
            { session -> if (cont.isActive) cont.resume(session) },
            null,
            { stats ->
                val clipSec = ((endSec - startSec).coerceAtLeast(0.1)).toInt()
                val done = (stats.time / 1000).toInt()
                onProgress(((done * 100) / clipSec).coerceIn(0, 99))
            }
        )
    }

    fun cancelCurrent() {
        FFmpegKit.cancel()
    }
}
