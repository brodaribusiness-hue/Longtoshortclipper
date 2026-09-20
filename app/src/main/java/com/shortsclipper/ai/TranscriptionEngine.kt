package com.shortsclipper.ai

import com.shortsclipper.model.Segment
import com.shortsclipper.model.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

/**
 * Local transcription pipeline: streams the decoded 16 kHz PCM file through
 * whisper.cpp in bounded 30 s windows (with 1.5 s overlap), assembles
 * word-level timestamps and never keeps more than one window in RAM.
 */
class TranscriptionEngine {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SECONDS = 30
        private const val OVERLAP_SECONDS = 1.5
        private const val MAX_THREADS = 4
    }

    private val json = Json { ignoreUnknownKeys = true }

    data class NativeSegment(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val tokens: List<WordAssembler.Token>,
    )

    suspend fun transcribe(
        pcmFile: File,
        modelFile: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Transcript = withContext(Dispatchers.Default) {
        if (!pcmFile.exists() || pcmFile.length() < 4) throw IllegalStateException("No audio to transcribe")

        val handle = WhisperNative.initModel(modelFile.absolutePath)
        if (handle == 0L) {
            throw IllegalStateException("Could not load the transcription model. Reinstall it in the model manager.")
        }
        try {
            val threads = min(Runtime.getRuntime().availableProcessors(), MAX_THREADS).coerceAtLeast(2)
            val windowSamples = WINDOW_SECONDS * SAMPLE_RATE
            val overlapSamples = (OVERLAP_SECONDS * SAMPLE_RATE).toInt()
            val hopSamples = windowSamples - overlapSamples
            val totalSamples = pcmFile.length() / 4
            val totalWindows = ceil(totalSamples.toDouble() / hopSamples).toInt().coerceAtLeast(1)

            val allWords = ArrayList<com.shortsclipper.model.Word>()
            val allSegments = ArrayList<Segment>()
            var language = ""

            RandomAccessFile(pcmFile, "r").use { raf ->
                for (w in 0 until totalWindows) {
                    if (isCancelled()) throw InterruptedException("Transcription cancelled")
                    val windowStartSample = w.toLong() * hopSamples
                    val count = min(windowSamples.toLong(), totalSamples - windowStartSample).toInt()
                    if (count <= 0) break
                    val chunk = readSamples(raf, windowStartSample, count)

                    val resultJson = WhisperNative.transcribe(handle, chunk, threads, null)
                        ?: throw IllegalStateException("Transcription failed inside whisper.cpp")

                    val parsed = parse(resultJson)
                    if (language.isEmpty()) language = parsed.second

                    val windowStartMs = windowStartSample * 1000L / SAMPLE_RATE
                    val windowEndMs = (windowStartSample + count) * 1000L / SAMPLE_RATE
                    val isFirst = w == 0

                    for (seg in parsed.first) {
                        val words = WordAssembler.assemble(seg.tokens)
                        val kept = WordAssembler.wordsForWindow(words, windowStartMs, (OVERLAP_SECONDS * 1000).toLong(), isFirst)
                        allWords.addAll(kept)
            if (isFirst || seg.startMs >= windowStartMs + (OVERLAP_SECONDS * 1000).toLong()) {
                            allSegments.add(Segment(seg.text.trim(), seg.startMs, seg.endMs))
                        }
                    }
                    onProgress((w + 1).toFloat() / totalWindows)
                }
            }

            val sorted = allWords.sortedBy { it.startTimeMs }
            Transcript(
                language = language.ifEmpty { "unknown" },
                words = sorted,
                segments = allSegments.sortedBy { it.startTimeMs },
            )
        } finally {
            WhisperNative.freeModel(handle)
        }
    }

    private fun readSamples(raf: RandomAccessFile, startSample: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 4)
        raf.seek(startSample * 4L)
        raf.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = buffer.float
        return out
    }

    /** Parses the JNI JSON transcript into native segments + tokens. */
    fun parse(resultJson: String): Pair<List<NativeSegment>, String> {
        val root = json.parseToJsonElement(resultJson).jsonObject
        val language = root["language"]?.jsonPrimitive?.content ?: "unknown"
        val segments = ArrayList<NativeSegment>()
        val segArray = root["segments"]?.jsonArray ?: return Pair(emptyList(), language)
        for (s in segArray) {
            val obj = s.jsonObject
            val tokens = ArrayList<WordAssembler.Token>()
            for (t in obj["tokens"]?.jsonArray ?: emptyList()) {
                val to = t.jsonObject
                tokens.add(
                    WordAssembler.Token(
                        text = to["x"]?.jsonPrimitive?.content ?: continue,
                        startMs = to["s"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        endMs = to["e"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        confidence = to["p"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                    )
                )
            }
            segments.add(
                NativeSegment(
                    startMs = obj["s"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    endMs = obj["e"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    text = obj["text"]?.jsonPrimitive?.content ?: "",
                    tokens = tokens,
                )
            )
        }
        return Pair(segments, language)
    }
}
