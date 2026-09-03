package com.yourapp.clipper

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoMeta(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrate: Int
)

object VideoUtils {

    fun readMeta(context: Context, uri: Uri): VideoMeta? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val duration = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return null
            val width = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val bitrate = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toIntOrNull() ?: 0

            val rotation = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val (w, h) = if (rotation == 90 || rotation == 270) height to width
            else width to height

            VideoMeta(duration, w, h, bitrate)
        } catch (e: Exception) {
            null
        } finally {
            mmr.release()
        }
    }

    fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(
            "%02d:%02d:%02d",
            totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60
        )
    }

    fun parseHmsToMs(input: String): Long? {
        val parts = input.trim().split(":")
        if (parts.size != 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = parts[2].toIntOrNull() ?: return null
        if (h < 0 || m !in 0..59 || s !in 0..59) return null
        return (h * 3600L + m * 60L + s) * 1000L
    }
}
