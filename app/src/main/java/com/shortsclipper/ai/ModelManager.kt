package com.shortsclipper.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Information about a whisper.cpp GGML model available to the editor. Official
 * entries are pinned to an immutable upstream revision and include the upstream
 * LFS size and SHA-256, so a redirect, error page or truncated response can
 * never become an installed model.
 */
data class ModelInfo(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String?,
    val approxSizeMB: Int,
    val description: String,
    val expectedSizeBytes: Long? = null,
    val sha256: String? = null,
    val imported: Boolean = false,
)

class ModelValidationException(message: String) : IOException(message)

private class HttpStatusException(val statusCode: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = statusCode == 408 || statusCode == 429 || statusCode >= 500
}

/**
 * Structural validation for the legacy GGML Whisper files consumed by the
 * vendored whisper.cpp version. This deliberately validates substantially more
 * than a magic byte: model hyperparameters, mel-filter bounds, vocabulary
 * offset and file length all have to be coherent before a model is accepted.
 */
object WhisperModelValidator {
    private const val GGML_FILE_MAGIC = 0x67676D6C
    private const val HEADER_INT_COUNT = 11
    private const val MIN_MODEL_BYTES = 20L * 1024L * 1024L
    private const val BASE_TOKEN_VOCABULARY = 50_257
    private val SUPPORTED_ARCHITECTURES = mapOf(
        4 to (384 to 6),   // tiny
        6 to (512 to 8),   // base
        12 to (768 to 12), // small
        24 to (1_024 to 16), // medium
        32 to (1_280 to 20), // large
    )
    // ggml_ftype_to_ggml_type() in the vendored native loader accepts these
    // legacy ftypes. Allowing another integer can hit a native GGML_ASSERT.
    private val SUPPORTED_BASE_FTYPES = setOf(0, 1, 2, 3) + (7..24)

    data class Header(
        val nVocab: Int,
        val nAudioContext: Int,
        val nAudioState: Int,
        val nAudioHeads: Int,
        val nAudioLayers: Int,
        val nTextContext: Int,
        val nTextState: Int,
        val nTextHeads: Int,
        val nTextLayers: Int,
        val nMels: Int,
        val ftype: Int,
        val nFft: Int,
    )

    fun minimumModelBytes(): Long = MIN_MODEL_BYTES

    fun inspect(file: File): Header {
        if (!file.isFile) throw ModelValidationException("Model file is missing")
        if (file.length() < MIN_MODEL_BYTES) {
            throw ModelValidationException("Model is too small to be a complete whisper.cpp model")
        }

        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 4L + HEADER_INT_COUNT * 4L + 8L + 4L) {
                    throw ModelValidationException("Model header is truncated")
                }
                val magic = readIntLe(raf)
                if (magic != GGML_FILE_MAGIC) {
                    throw ModelValidationException("Model has an invalid GGML magic header")
                }

                val values = IntArray(HEADER_INT_COUNT) { readIntLe(raf) }
                val header = Header(
                    nVocab = values[0],
                    nAudioContext = values[1],
                    nAudioState = values[2],
                    nAudioHeads = values[3],
                    nAudioLayers = values[4],
                    nTextContext = values[5],
                    nTextState = values[6],
                    nTextHeads = values[7],
                    nTextLayers = values[8],
                    nMels = values[9],
                    ftype = values[10],
                    nFft = 0,
                )
                validateHyperParameters(header)

                val nMels = readIntLe(raf)
                val nFft = readIntLe(raf)
                if (nMels != header.nMels || nMels != 80 || nFft != 201) {
                    throw ModelValidationException("Model mel-filter header is invalid")
                }
                val filterBytes = nMels.toLong() * nFft.toLong() * 4L
                val vocabularyOffset = raf.filePointer + filterBytes
                if (filterBytes <= 0L || vocabularyOffset + 4L > raf.length()) {
                    throw ModelValidationException("Model mel-filter data is truncated")
                }
                raf.seek(vocabularyOffset)
                val storedVocab = readIntLe(raf)
                // Legacy official whisper.cpp GGML files store only the base
                // tokenizer vocabulary (typically 50,257 entries), while the
                // model header includes generated language/timestamp tokens
                // (51,864/51,865). The vendored loader intentionally appends
                // that missing tail, so equality here would reject every
                // valid official legacy model. More entries than the embedding
                // header can never be represented safely by this loader.
                if (storedVocab !in BASE_TOKEN_VOCABULARY..header.nVocab) {
                    throw ModelValidationException("Model vocabulary header is invalid")
                }
                // Check every variable-length vocabulary entry before trusting
                // the following tensor stream. A valid magic/header alone can
                // otherwise be forged by an HTML/error/truncated payload.
                repeat(storedVocab) {
                    val tokenLength = readIntLe(raf)
                    if (tokenLength !in 0..1_048_576 || raf.filePointer + tokenLength > raf.length()) {
                        throw ModelValidationException("Model vocabulary data is truncated or malformed")
                    }
                    raf.seek(raf.filePointer + tokenLength)
                }
                if (raf.filePointer + 64L > raf.length()) {
                    throw ModelValidationException("Model contains no complete tensor data after its vocabulary")
                }
                return header.copy(nFft = nFft)
            }
        } catch (e: ModelValidationException) {
            throw e
        } catch (e: IOException) {
            throw ModelValidationException("Could not read model header: ${e.message ?: "I/O error"}")
        }
    }

    private fun validateHyperParameters(header: Header) {
        // This app embeds the legacy whisper.cpp loader, not a generic GGML
        // runtime. Restrict imports to the five architecture families that
        // loader recognizes, rather than letting crafted dimensions allocate
        // unbounded native buffers or trip its internal assertions.
        val expected = SUPPORTED_ARCHITECTURES[header.nAudioLayers]
        if (header.nVocab !in 51_864..51_866 ||
            header.nAudioContext != 1_500 ||
            header.nTextContext != 448 ||
            header.nMels != 80 ||
            expected == null ||
            header.nAudioState != expected.first ||
            header.nAudioHeads != expected.second ||
            header.nTextState != header.nAudioState ||
            header.nTextHeads != header.nAudioHeads ||
            header.nTextLayers != header.nAudioLayers
        ) {
            throw ModelValidationException("Model architecture is not compatible with the bundled Whisper GGML loader")
        }
        if (header.nAudioState % header.nAudioHeads != 0 || header.nTextState % header.nTextHeads != 0) {
            throw ModelValidationException("Model attention dimensions are inconsistent")
        }
        // whisper.cpp stores the quantization version in thousands. The
        // low-order value must map to a real native GGML type; unsupported
        // values would otherwise trip ggml_ftype_to_ggml_type() assertions.
        val baseFtype = header.ftype % 1_000
        val quantizationVersion = header.ftype / 1_000
        if (baseFtype !in SUPPORTED_BASE_FTYPES || quantizationVersion !in 0..8) {
            throw ModelValidationException("Model quantization header is unsupported")
        }
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }
}

/**
 * Controlled whisper.cpp model management. Downloads are explicit, cancellable
 * and serialized per model. A model only becomes visible as installed after its
 * exact size, checksum and Whisper GGML structure have all been verified.
 */
class ModelManager(private val context: Context) {

    @Serializable
    private data class InstalledModelRecord(
        val id: String,
        val label: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val imported: Boolean,
        val installedAtMs: Long,
    )

    @Serializable
    private data class InstalledModelManifest(val records: List<InstalledModelRecord> = emptyList())

    companion object {
        // Pinning the official commit makes the checksum and expected length
        // deterministic instead of trusting a mutable `main` URL.
        private const val OFFICIAL_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$OFFICIAL_REVISION/"
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 750L
        private const val COPY_BUFFER_BYTES = 64 * 1024

        val CATALOG = listOf(
            ModelInfo(
                id = "tiny",
                label = "Tiny",
                fileName = "ggml-tiny.bin",
                url = BASE_URL + "ggml-tiny.bin",
                approxSizeMB = 74,
                description = "Fastest, lower accuracy. Good on older devices. Multilingual.",
                expectedSizeBytes = 77_691_713L,
                sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            ),
            ModelInfo(
                id = "base",
                label = "Base",
                fileName = "ggml-base.bin",
                url = BASE_URL + "ggml-base.bin",
                approxSizeMB = 141,
                description = "Recommended balance of speed and accuracy. Multilingual.",
                expectedSizeBytes = 147_951_465L,
                sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            ),
            ModelInfo(
                id = "small",
                label = "Small",
                fileName = "ggml-small.bin",
                url = BASE_URL + "ggml-small.bin",
                approxSizeMB = 465,
                description = "Best accuracy, slowest. Needs a modern device.",
                expectedSizeBytes = 487_601_967L,
                sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val manifestLock = Any()
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val structuralValidationCache = ConcurrentHashMap<String, Boolean>()

    private fun lockFor(modelId: String): Mutex = downloadLocks.computeIfAbsent(modelId) { Mutex() }
    private fun validationCacheKey(file: File): String = "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    val modelsDir: File
        get() = File(context.filesDir, "whisper_models").apply { mkdirs() }

    private val manifestFile: File
        get() = File(modelsDir, "installed_models.json")

    fun modelFile(info: ModelInfo): File {
        require(isSafeModelFileName(info.fileName)) { "Unsafe model file name" }
        return File(modelsDir, info.fileName)
    }

    private fun isSafeModelFileName(fileName: String): Boolean =
        fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,118}\\.bin")) && !fileName.contains("..")

    private fun isSafeRecord(record: InstalledModelRecord): Boolean =
        record.id.matches(Regex("[A-Za-z0-9_-]{1,96}")) &&
            isSafeModelFileName(record.fileName) &&
            record.sizeBytes >= WhisperModelValidator.minimumModelBytes() &&
            record.sha256.matches(Regex("[A-Fa-f0-9]{64}"))

    /** Returns catalog models followed by locally imported, persisted models. */
    fun availableModels(): List<ModelInfo> {
        val imported = synchronized(manifestLock) { readManifestLocked().records }
            .filter { it.imported && it.id !in CATALOG.map { catalog -> catalog.id } }
            .map { record ->
                ModelInfo(
                    id = record.id,
                    label = record.label,
                    fileName = record.fileName,
                    url = null,
                    approxSizeMB = (record.sizeBytes / (1024L * 1024L)).toInt().coerceAtLeast(1),
                    description = "Imported local whisper.cpp GGML model.",
                    expectedSizeBytes = record.sizeBytes,
                    sha256 = record.sha256,
                    imported = true,
                )
            }
        return CATALOG + imported.sortedBy { it.label.lowercase() }
    }

    fun findModel(id: String): ModelInfo? = availableModels().firstOrNull { it.id == id }

    /**
     * Fast installed-state check for UI. It validates file length and the full
     * GGML structural header, but performs the expensive full SHA-256 check in
     * [validatedModelFile] immediately before native loading.
     */
    fun isInstalled(info: ModelInfo): Boolean {
        val file = modelFile(info)
        if (!file.isFile || file.length() < WhisperModelValidator.minimumModelBytes()) return false
        if (info.expectedSizeBytes != null && file.length() != info.expectedSizeBytes) return false
        val key = validationCacheKey(file)
        return structuralValidationCache[key] ?: runCatching { WhisperModelValidator.inspect(file) }
            .isSuccess
            .also { valid ->
                // Keep only the current file revision's result; file length or
                // mtime changes naturally invalidate this lightweight UI cache.
                structuralValidationCache.keys.removeAll { it.startsWith("${file.absolutePath}:") && it != key }
                structuralValidationCache[key] = valid
            }
    }

    fun installedSizeMB(info: ModelInfo): Int {
        val file = modelFile(info)
        return if (file.isFile) (file.length() / (1024L * 1024L)).toInt() else 0
    }

    fun freeSpaceMB(): Long {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes / (1024L * 1024L)
    }

    suspend fun delete(info: ModelInfo): Boolean = lockFor(info.id).withLock {
        withContext(Dispatchers.IO) {
            val file = modelFile(info)
            val part = partFile(info.fileName)
            val deleted = (!file.exists() || file.delete()) && (!part.exists() || part.delete())
            structuralValidationCache.keys.removeAll { it.startsWith("${file.absolutePath}:") }
            synchronized(manifestLock) {
                val manifest = readManifestLocked()
                if (manifest.records.any { it.id == info.id }) {
                    writeManifestLocked(manifest.copy(records = manifest.records.filterNot { it.id == info.id }))
                }
            }
            deleted
        }
    }

    /**
     * Revalidates a model on a worker thread before it is handed to JNI. This
     * protects against disk corruption after an earlier successful install.
     */
    suspend fun validatedModelFile(
        info: ModelInfo,
        isCancelled: () -> Boolean,
    ): File = lockFor(info.id).withLock {
        withContext(Dispatchers.IO) {
            ensureNotCancelled(isCancelled)
            val file = modelFile(info)
            try {
                validateFile(file, info.expectedSizeBytes, info.sha256, isCancelled)
                persistValidatedRecord(info, file, sha256(file, isCancelled))
                file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A corrupt model must not remain marked as usable. Official files
                // can always be downloaded again; imported files can be reselected.
                file.delete()
                removeRecord(info.id)
                throw ModelValidationException(
                    "${info.label} model is invalid and was removed. ${e.message ?: "Download or import it again."}",
                )
            }
        }
    }

    /**
     * Downloads an official model to a unique part file, validates it and only
     * then atomically replaces the installed file. Calls for the same model are
     * serialized so duplicate concurrent downloads cannot race.
     */
    suspend fun download(
        info: ModelInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        require(!info.imported && !info.url.isNullOrBlank()) { "Only official catalog models can be downloaded" }
        return lockFor(info.id).withLock {
            withContext(Dispatchers.IO) {
                ensureNotCancelled(isCancelled)
                val destination = modelFile(info)
                val existingModelIsValid = try {
                    validateFile(destination, info.expectedSizeBytes, info.sha256, isCancelled)
                    true
                } catch (e: CancellationException) {
                    // Never turn cooperative cancellation into a failed
                    // validation and continue with a new network transfer.
                    throw e
                } catch (_: Throwable) {
                    false
                }
                if (existingModelIsValid) {
                    ensureNotCancelled(isCancelled)
                    persistValidatedRecord(info, destination, info.sha256!!)
                    onProgress(destination.length(), info.expectedSizeBytes ?: destination.length())
                    return@withContext destination
                }

                var lastError: Throwable? = null
                for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
                    ensureNotCancelled(isCancelled)
                    try {
                        val part = partFile(info.fileName)
                        part.delete()
                        downloadAttempt(info, part, onProgress, isCancelled)
                        validateFile(part, info.expectedSizeBytes, info.sha256, isCancelled)
                        replaceAtomically(part, destination)
                        persistValidatedRecord(info, destination, info.sha256!!)
                        return@withContext destination
                    } catch (e: CancellationException) {
                        partFile(info.fileName).delete()
                        throw e
                    } catch (e: Throwable) {
                        partFile(info.fileName).delete()
                        lastError = e
                        val retryable = e !is HttpStatusException || e.retryable
                        if (!retryable || attempt == MAX_DOWNLOAD_ATTEMPTS) break
                        delay(RETRY_BASE_DELAY_MS * attempt)
                    }
                }
                throw ModelValidationException(
                    "Could not download ${info.label} after $MAX_DOWNLOAD_ATTEMPTS attempts: " +
                        (lastError?.message ?: "network error"),
                )
            }
        }
    }

    /**
     * Imports a local GGML file through SAF. The imported bytes are copied to a
     * part file, structurally and natively validated, and hashed before becoming selectable.
     */
    suspend fun importFromFile(
        uri: Uri,
        displayName: String,
        onProgress: (copiedBytes: Long) -> Unit = {},
        isCancelled: () -> Boolean,
    ): ModelInfo = withContext(Dispatchers.IO) {
        ensureNotCancelled(isCancelled)
        val importPart = File(modelsDir, ".import_${System.nanoTime()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { rawInput ->
                BufferedInputStream(rawInput, COPY_BUFFER_BYTES).use { input ->
                    FileOutputStream(importPart).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = 0L
                        while (true) {
                            ensureNotCancelled(isCancelled)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                            onProgress(copied)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            } ?: throw IOException("Cannot open the selected model file")

            WhisperModelValidator.inspect(importPart)
            // Structural inspection protects the parser, then a one-time
            // guarded native load proves this imported file has the complete
            // tensor set expected by the exact bundled whisper.cpp build.
            // It happens before any manifest record is written, so a partial
            // or incompatible local model is never shown as installed.
            verifyNativeLoadable(importPart, isCancelled)
            val digestHex = digest.digest().toHex()
            val sanitized = displayName
                .substringBeforeLast('.', displayName)
                .replace(Regex("[^A-Za-z0-9_-]+"), "_")
                .trim('_')
                .ifBlank { "imported" }
                .take(48)
            val info = ModelInfo(
                id = "imported_${digestHex.take(16)}",
                label = "Imported $sanitized",
                fileName = "${sanitized}_${digestHex.take(12)}.bin",
                url = null,
                approxSizeMB = (importPart.length() / (1024L * 1024L)).toInt().coerceAtLeast(1),
                description = "Imported local whisper.cpp GGML model.",
                expectedSizeBytes = importPart.length(),
                sha256 = digestHex,
                imported = true,
            )
            lockFor(info.id).withLock {
                val destination = modelFile(info)
                if (!destination.exists()) {
                    replaceAtomically(importPart, destination)
                } else {
                    importPart.delete()
                    validateFile(destination, info.expectedSizeBytes, info.sha256, isCancelled)
                }
                persistValidatedRecord(info, destination, digestHex)
            }
            info
        } catch (e: CancellationException) {
            importPart.delete()
            throw e
        } catch (e: Throwable) {
            importPart.delete()
            throw if (e is ModelValidationException) e else ModelValidationException(
                "Selected file is not a valid whisper.cpp GGML model: ${e.message ?: "invalid file"}",
            )
        }
    }

    private suspend fun downloadAttempt(
        info: ModelInfo,
        part: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            connectionRef.get()?.disconnect()
        }
        try {
            ensureNotCancelled(isCancelled)
            val activeConnection = (URL(requireNotNull(info.url)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "ShortsClipper/1.0")
            }
            connectionRef.set(activeConnection)
            val responseCode = activeConnection.responseCode
            if (responseCode !in 200..299) {
                val detail = runCatching { activeConnection.errorStream?.bufferedReader()?.use { it.readText().take(160) } }.getOrNull()
                throw HttpStatusException(responseCode, "Model server returned HTTP $responseCode${detail?.let { ": $it" } ?: ""}")
            }
            val contentType = activeConnection.contentType?.lowercase().orEmpty()
            if (contentType.startsWith("text/") ||
                contentType.contains("text/html") ||
                contentType.contains("application/json") ||
                contentType.contains("xml")
            ) {
                throw ModelValidationException("Model server returned $contentType instead of a binary model file")
            }
            val total = activeConnection.contentLengthLong
            val expected = requireNotNull(info.expectedSizeBytes)
            if (total >= 0L && total != expected) {
                throw ModelValidationException(
                    "Model server reported ${total / (1024L * 1024L)} MB; expected ${expected / (1024L * 1024L)} MB",
                )
            }
            onProgress(0L, if (total >= 0L) total else expected)

            activeConnection.inputStream.use { rawInput ->
                BufferedInputStream(rawInput, COPY_BUFFER_BYTES).use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var downloaded = 0L
                        while (true) {
                            ensureNotCancelled(isCancelled)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            onProgress(downloaded, if (total >= 0L) total else expected)
                        }
                        output.flush()
                        output.fd.sync()
                        if (downloaded != expected) {
                            throw ModelValidationException(
                                "Download was incomplete (${downloaded / (1024L * 1024L)} MB of ${expected / (1024L * 1024L)} MB)",
                            )
                        }
                    }
                }
            }
        } finally {
            completionHandle?.dispose()
            connectionRef.getAndSet(null)?.disconnect()
        }
    }

    private suspend fun verifyNativeLoadable(file: File, isCancelled: () -> Boolean) {
        ensureNotCancelled(isCancelled)
        if (!WhisperNative.isAvailable) {
            throw ModelValidationException(
                WhisperNative.unavailableReason ?: "Native model validation is unavailable on this device",
            )
        }
        val handle = try {
            WhisperNative.initModel(file.absolutePath)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw ModelValidationException("The bundled Whisper engine could not load this model: ${t.message ?: "native load failed"}")
        }
        if (handle == 0L) {
            ensureNotCancelled(isCancelled)
            throw ModelValidationException("The selected file is incomplete or incompatible with the bundled Whisper engine")
        }
        try {
            ensureNotCancelled(isCancelled)
        } finally {
            WhisperNative.freeModel(handle)
        }
    }

    private suspend fun validateFile(
        file: File,
        expectedSizeBytes: Long?,
        expectedSha256: String?,
        isCancelled: () -> Boolean,
    ) {
        ensureNotCancelled(isCancelled)
        if (!file.isFile) throw ModelValidationException("Model file is missing")
        if (expectedSizeBytes != null && file.length() != expectedSizeBytes) {
            throw ModelValidationException(
                "Model size is ${file.length()} bytes but expected $expectedSizeBytes bytes",
            )
        }
        WhisperModelValidator.inspect(file)
        if (expectedSha256 != null) {
            val actualHash = sha256(file, isCancelled)
            if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                throw ModelValidationException("Model checksum does not match the official file")
            }
        }
    }

    private suspend fun sha256(file: File, isCancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { raw ->
            BufferedInputStream(raw, COPY_BUFFER_BYTES).use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    ensureNotCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().toHex()
    }

    private suspend fun ensureNotCancelled(isCancelled: () -> Boolean) {
        coroutineContext.ensureActive()
        if (isCancelled()) throw CancellationException("Model operation cancelled")
    }

    private fun partFile(fileName: String): File = File(modelsDir, "$fileName.part")

    /** Replaces a file only after validation, preserving an existing valid copy on failure. */
    private fun replaceAtomically(source: File, destination: File) {
        val backup = File(destination.parentFile, destination.name + ".previous")
        backup.delete()
        val hadDestination = destination.exists()
        if (hadDestination && !destination.renameTo(backup)) {
            throw IOException("Could not safely replace the existing model")
        }
        if (!source.renameTo(destination)) {
            if (hadDestination) backup.renameTo(destination)
            throw IOException("Could not finalize the model file")
        }
        backup.delete()
    }

    private fun persistValidatedRecord(info: ModelInfo, file: File, hash: String) {
        synchronized(manifestLock) {
            val existing = readManifestLocked()
            val record = InstalledModelRecord(
                id = info.id,
                label = info.label,
                fileName = info.fileName,
                sizeBytes = file.length(),
                sha256 = hash,
                imported = info.imported,
                installedAtMs = System.currentTimeMillis(),
            )
            writeManifestLocked(existing.copy(records = (existing.records.filterNot { it.id == info.id } + record)))
        }
    }

    private fun removeRecord(id: String) {
        synchronized(manifestLock) {
            val existing = readManifestLocked()
            if (existing.records.any { it.id == id }) {
                writeManifestLocked(existing.copy(records = existing.records.filterNot { it.id == id }))
            }
        }
    }

    private fun readManifestLocked(): InstalledModelManifest = try {
        recoverManifestLocked()
        if (!manifestFile.isFile) {
            InstalledModelManifest()
        } else {
            val decoded = json.decodeFromString(InstalledModelManifest.serializer(), manifestFile.readText())
            // Treat every manifest field as untrusted persisted input. In
            // particular, never turn a crafted file name into a path outside
            // the app-private models directory.
            decoded.copy(records = decoded.records.filter(::isSafeRecord))
        }
    } catch (_: Throwable) {
        // A malformed manifest never makes a model trusted: the physical model
        // is still structurally/checksum validated before transcription.
        InstalledModelManifest()
    }

    /** Restores a complete manifest if the app stopped between atomic rename steps. */
    private fun recoverManifestLocked() {
        val target = manifestFile
        val backup = File(modelsDir, target.name + ".previous")
        if (!target.exists() && backup.isFile) {
            backup.renameTo(target)
        } else if (target.isFile && backup.exists()) {
            backup.delete()
        }
    }

    private fun writeManifestLocked(manifest: InstalledModelManifest) {
        val target = manifestFile
        val temp = File(modelsDir, target.name + ".tmp")
        FileOutputStream(temp).use { output ->
            output.write(json.encodeToString(InstalledModelManifest.serializer(), manifest).toByteArray())
            output.fd.sync()
        }
        try {
            replaceAtomically(temp, target)
        } catch (t: Throwable) {
            temp.delete()
            throw IOException("Could not finalize installed-model state", t)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
