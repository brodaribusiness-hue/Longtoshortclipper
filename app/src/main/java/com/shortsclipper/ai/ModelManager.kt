package com.shortsclipper.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Controlled whisper.cpp model management. Models are NEVER downloaded
 * silently: the user explicitly picks an official model (ggerganov/whisper.cpp
 * releases on Hugging Face) or imports a local .bin file. Progress, failure
 * and storage state are surfaced to the UI.
 */
class ModelManager(private val context: Context) {

    data class ModelInfo(
        val id: String,
        val label: String,
        val fileName: String,
        val url: String,
        val approxSizeMB: Int,
        val description: String,
    )

    companion object {
        // Official whisper.cpp model repository (open source, MIT licensed models).
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        val CATALOG = listOf(
            ModelInfo(
                id = "tiny", label = "Tiny", fileName = "ggml-tiny.bin",
                url = BASE_URL + "ggml-tiny.bin", approxSizeMB = 78,
                description = "Fastest, lower accuracy. Good on older devices. Multilingual.",
            ),
            ModelInfo(
                id = "base", label = "Base", fileName = "ggml-base.bin",
                url = BASE_URL + "ggml-base.bin", approxSizeMB = 148,
                description = "Recommended balance of speed and accuracy. Multilingual.",
            ),
            ModelInfo(
                id = "small", label = "Small", fileName = "ggml-small.bin",
                url = BASE_URL + "ggml-small.bin", approxSizeMB = 488,
                description = "Best accuracy, slowest. Needs a modern device.",
            ),
        )

        private const val MIN_VALID_BYTES = 20L * 1024 * 1024
    }

    fun isLowRamDevice(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return am.isLowRamDevice || memInfo.totalMem <= 3L * 1024 * 1024 * 1024
    }

    val modelsDir: File
        get() = File(context.filesDir, "whisper_models").apply { mkdirs() }

    fun modelFile(info: ModelInfo): File = File(modelsDir, info.fileName)

    fun isInstalled(info: ModelInfo): Boolean {
        val f = modelFile(info)
        if (f.exists() && f.length() >= MIN_VALID_BYTES && looksLikeGgml(f)) return true
        return unpackFromAssetsIfAvailable(info)
    }

    /** Unpacks a bundled model from APK assets into the local models directory if present. */
    fun unpackFromAssetsIfAvailable(info: ModelInfo): Boolean {
        val dest = modelFile(info)
        if (dest.exists() && dest.length() >= MIN_VALID_BYTES && looksLikeGgml(dest)) return true
        val assetPaths = listOf("models/${info.fileName}", info.fileName)
        for (assetPath in assetPaths) {
            try {
                context.assets.open(assetPath).use { input ->
                    val part = File(modelsDir, info.fileName + ".part")
                    part.outputStream().use { output -> input.copyTo(output) }
                    if (part.length() >= MIN_VALID_BYTES && looksLikeGgml(part)) {
                        if (dest.exists()) dest.delete()
                        if (!part.renameTo(dest)) {
                            part.copyTo(dest, overwrite = true)
                            part.delete()
                        }
                        return true
                    } else {
                        part.delete()
                    }
                }
            } catch (ignored: IOException) {
                // Asset not present in APK
            }
        }
        return false
    }

    fun installedSizeMB(info: ModelInfo): Int {
        val f = modelFile(info)
        return if (f.exists()) (f.length() / (1024 * 1024)).toInt() else 0
    }

    fun freeSpaceMB(): Long {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }

    fun delete(info: ModelInfo): Boolean {
        val f = modelFile(info)
        return if (f.exists()) f.delete() else true
    }

    /** Looks for the ggml file magic ('g' at offset 0) as a lightweight sanity check. */
    private fun looksLikeGgml(f: File): Boolean = try {
        f.inputStream().use { s ->
            val b = ByteArray(4)
            val n = s.read(b)
            n == 4 && b[0] == 'g'.code.toByte()
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * Downloads a model with explicit progress. Writes to a .part file and
     * renames on success so partial downloads can never look installed.
     */
    suspend fun download(
        info: ModelInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): File = withContext(Dispatchers.IO) {
        val dest = modelFile(info)
        if (unpackFromAssetsIfAvailable(info)) {
            onProgress(dest.length(), dest.length())
            return@withContext dest
        }

        val freeMb = freeSpaceMB()
        if (freeMb < info.approxSizeMB + 50) {
            throw IOException("Insufficient storage: requires at least ${info.approxSizeMB + 50} MB free, only $freeMb MB available.")
        }

        val part = File(modelsDir, info.fileName + ".part")
        part.parentFile?.mkdirs()
        var connection: HttpURLConnection? = null
        try {
            val url = URL(info.url)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Model download failed: HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var downloaded = 0L
                    while (true) {
                        if (isCancelled()) throw IOException("Download cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            if (part.length() < MIN_VALID_BYTES || !looksLikeGgml(part)) {
                part.delete()
                throw IOException("Downloaded model file is not valid")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            dest
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    /** Imports a user-picked .bin file (SAF uri) into the local model folder. */
    suspend fun importFromFile(uri: Uri, displayName: String): File = withContext(Dispatchers.IO) {
        val safeName = displayName.trim().ifBlank { "imported_model.bin" }
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dest = File(modelsDir, safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Cannot open selected file")
        if (dest.length() < MIN_VALID_BYTES || !looksLikeGgml(dest)) {
            dest.delete()
            throw IOException("Selected file is not a valid whisper ggml model (too small or invalid header)")
        }
        dest
    }
}
