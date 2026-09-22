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
        const val MAX_THREADS = 4
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
        maxThreads: Int = MAX_THREADS,
    ): Transcript = withContext(Dispatchers.Default) {
        if (!pcmFile.exists() || pcmFile.length() < 4) throw IllegalStateException("No audio to transcribe")

        val handle = WhisperNative.initModel(modelFile.absolutePath)
        if (handle == 0L) {
            throw IllegalStateException("Could not load the transcription model. Reinstall it in the model manager.")
        }
        try {
            val threads = min(Runtime.getRuntime().availableProcessors(), maxThreads).coerceAtLeast(1)
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
                    kotlinx.coroutines.yield()
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
                    val overlapMs = (OVERLAP_SECONDS * 1000).toLong()

                    for (seg in parsed.first) {
                        val shiftedTokens = seg.tokens.map {
                            it.copy(
                                startMs = it.startMs + windowStartMs,
                                endMs = it.endMs + windowStartMs,
                            )
                        }
                        val words = WordAssembler.assemble(shiftedTokens)
                        val lastAccepted = allWords.lastOrNull()?.endTimeMs ?: -1L
                        val kept = WordAssembler.wordsForWindow(words, windowStartMs, overlapMs, isFirst, lastAccepted)
                        allWords.addAll(kept)

                        val segStart = seg.startMs + windowStartMs
                        val segEnd = seg.endMs + windowStartMs
                        val segCutoff = if (isFirst) 0L else windowStartMs + overlapMs
                        if (segEnd > segCutoff || isFirst) {
                            allSegments.add(Segment(seg.text.trim(), segStart, segEnd))
                        }
                    }
                    onProgress((w + 1).toFloat() / totalWindows)
                }
            }

            // Deduplicate words that might have been captured across window boundaries
            fun normalizeWord(t: String): String = t.filter { it.isLetterOrDigit() }.lowercase()

            val deduplicatedWords = ArrayList<com.shortsclipper.model.Word>()
            for (word in allWords.sortedBy { it.startTimeMs }) {
                val prev = deduplicatedWords.lastOrNull()
                if (prev != null) {
                    val normPrev = normalizeWord(prev.text)
                    val normCurr = normalizeWord(word.text)
                    val sameNormalized = normPrev.isNotEmpty() && normPrev == normCurr
                    val exactMatch = prev.text.equals(word.text, ignoreCase = true)
                    val timeOverlap = word.startTimeMs < prev.endTimeMs + 300L ||
                        (word.startTimeMs >= prev.startTimeMs && kotlin.math.abs(word.startTimeMs - prev.startTimeMs) < 600L)

                    if ((sameNormalized || exactMatch) && timeOverlap) {
                        if (word.confidence > prev.confidence) {
                            deduplicatedWords[deduplicatedWords.size - 1] = word
                        }
                        continue
                    }
                    if (prev.endTimeMs > word.startTimeMs) {
                        val adjustedPrevEnd = maxOf(prev.startTimeMs + 40L, word.startTimeMs)
                        deduplicatedWords[deduplicatedWords.size - 1] = prev.copy(endTimeMs = adjustedPrevEnd)
                    }
                }
                val validWord = if (word.endTimeMs <= word.startTimeMs) {
                    word.copy(endTimeMs = word.startTimeMs + 100L)
                } else {
                    word
                }
                deduplicatedWords.add(validWord)
            }

            val deduplicatedSegments = ArrayList<Segment>()
            for (seg in allSegments.sortedBy { it.startTimeMs }) {
                val prev = deduplicatedSegments.lastOrNull()
                if (prev != null) {
                    val sameText = prev.text.trim().equals(seg.text.trim(), ignoreCase = true)
                    val timeOverlap = seg.startTimeMs < prev.endTimeMs + 500L
                    if (sameText && timeOverlap) {
                        continue
                    }
                }
                val validSeg = if (seg.endTimeMs <= seg.startTimeMs) {
                    seg.copy(endTimeMs = seg.startTimeMs + 200L)
                } else {
                    seg
                }
                deduplicatedSegments.add(validSeg)
            }

            Transcript(
                language = language.ifEmpty { "unknown" },
                words = deduplicatedWords,
                segments = deduplicatedSegments,
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
