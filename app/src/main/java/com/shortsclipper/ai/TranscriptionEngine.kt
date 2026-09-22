package com.shortsclipper.ai

import com.shortsclipper.model.Segment
import com.shortsclipper.model.Transcript
import com.shortsclipper.model.Word
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/**
 * Local transcription pipeline. Audio is read from disk in bounded 30-second
 * windows; the native model is loaded once per analysis and released in all
 * success, error and cancellation paths.
 */
class TranscriptionEngine {

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SECONDS = 30
        private const val OVERLAP_SECONDS = 1.5
        private const val MAX_THREADS = 4
        private const val NO_HANDLE = 0L

        /** Keep native work within the actual CPU count, including single-core devices. */
        internal fun threadCountFor(availableProcessors: Int): Int =
            availableProcessors.coerceIn(1, MAX_THREADS)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val activeHandle = AtomicLong(NO_HANDLE)

    data class NativeSegment(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val tokens: List<WordAssembler.Token>,
    )

    /** Signals the currently running native window to stop at its next safe callback. */
    fun cancel() {
        val handle = activeHandle.get()
        if (handle != NO_HANDLE) WhisperNative.cancel(handle)
    }

    suspend fun transcribe(
        pcmFile: File,
        modelFile: File,
        language: String? = null,
        /** Source-video timestamp represented by PCM sample zero. */
        sourceStartMs: Long = 0L,
        /** Exclusive source-video bound; prevents A/V padding from leaking into captions. */
        sourceEndMs: Long = Long.MAX_VALUE,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean,
    ): Transcript = withContext(Dispatchers.Default) {
        if (!WhisperNative.isAvailable) {
            throw IllegalStateException(WhisperNative.unavailableReason ?: "Native transcription is unavailable")
        }
        if (!pcmFile.isFile || pcmFile.length() < 4L) throw IllegalStateException("No decoded audio is available to transcribe")
        if (!modelFile.isFile) throw IllegalStateException("The selected transcription model is missing")
        checkCancelled(isCancelled)
        val boundedSourceStartMs = sourceStartMs.coerceAtLeast(0L)
        val boundedSourceEndMs = sourceEndMs.coerceAtLeast(0L)
        if (boundedSourceEndMs <= boundedSourceStartMs) {
            return@withContext Transcript(language = "unknown", words = emptyList(), segments = emptyList())
        }

        val handle = WhisperNative.initModel(modelFile.absolutePath)
        if (handle == NO_HANDLE) {
            throw IllegalStateException("Could not load the transcription model. Validate or reinstall it in Model Manager.")
        }
        activeHandle.set(handle)
        try {
            val threads = threadCountFor(Runtime.getRuntime().availableProcessors())
            val windowSamples = WINDOW_SECONDS * SAMPLE_RATE
            val overlapSamples = (OVERLAP_SECONDS * SAMPLE_RATE).toInt()
            val hopSamples = windowSamples - overlapSamples
            val totalSamples = pcmFile.length() / 4L
            val totalWindows = numberOfWindows(totalSamples, windowSamples, hopSamples)
            val overlapMs = (OVERLAP_SECONDS * 1_000).toLong()
            val requestedLanguage = language?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it != "auto" }

            val allWords = ArrayList<Word>()
            val allSegments = ArrayList<Segment>()
            var detectedLanguage = requestedLanguage.orEmpty()
            var lastWordEndMs = Long.MIN_VALUE

            RandomAccessFile(pcmFile, "r").use { raf ->
                for (windowIndex in 0 until totalWindows) {
                    checkCancelled(isCancelled)
                    val windowStartSample = windowIndex.toLong() * hopSamples
                    val sampleCount = min(windowSamples.toLong(), totalSamples - windowStartSample).toInt()
                    if (sampleCount <= 0) break
                    val samples = readSamples(raf, windowStartSample, sampleCount)

                    val resultJson = WhisperNative.transcribe(handle, samples, threads, requestedLanguage)
                    checkCancelled(isCancelled)
                    if (resultJson == null) {
                        throw if (isCancelled()) {
                            CancellationException("Transcription cancelled")
                        } else {
                            IllegalStateException("whisper.cpp could not transcribe this audio window")
                        }
                    }

                    val (relativeSegments, nativeLanguage) = try {
                        parse(resultJson)
                    } catch (t: Throwable) {
                        throw IllegalStateException("whisper.cpp returned an invalid transcript: ${t.message ?: "parse error"}", t)
                    }
                    if (detectedLanguage.isBlank() && nativeLanguage.isNotBlank()) detectedLanguage = nativeLanguage

                    // whisper_full timestamps are relative to each 30-second
                    // input window. Offset every segment and token back into
                    // source-video time before overlap filtering.
                    val windowStartMs = boundedSourceStartMs + windowStartSample * 1_000L / SAMPLE_RATE
                    val discardUntilMs = if (windowIndex == 0) Long.MIN_VALUE else windowStartMs + overlapMs
                    for (relative in relativeSegments.sortedBy { it.startMs }) {
                        val absoluteTokens = relative.tokens.sortedBy { it.startMs }.map { token ->
                            token.copy(
                                startMs = (token.startMs + windowStartMs).coerceAtLeast(windowStartMs),
                                endMs = (token.endMs + windowStartMs).coerceAtLeast(windowStartMs),
                            )
                        }
                        val absoluteSegment = relative.copy(
                            startMs = (relative.startMs + windowStartMs).coerceAtLeast(windowStartMs),
                            endMs = (relative.endMs + windowStartMs).coerceAtLeast(windowStartMs),
                            tokens = absoluteTokens,
                        )
                        val retainedWords = ArrayList<Word>()
                        for (unboundedWord in WordAssembler.assemble(absoluteSegment.tokens)) {
                            val word = unboundedWord.copy(
                                startTimeMs = unboundedWord.startTimeMs.coerceAtLeast(boundedSourceStartMs),
                                endTimeMs = min(unboundedWord.endTimeMs, boundedSourceEndMs),
                            )
                            // Keep a word straddling the overlap boundary, but
                            // never append a full duplicate from the previous
                            // window or A/V padding beyond the source timeline.
                            if (word.endTimeMs <= word.startTimeMs || word.endTimeMs <= discardUntilMs) continue
                            if (word.endTimeMs <= lastWordEndMs && word.startTimeMs < lastWordEndMs) continue
                            allWords.add(word)
                            retainedWords.add(word)
                            lastWordEndMs = maxOf(lastWordEndMs, word.endTimeMs)
                        }
                        // Build captions from exactly the retained timed words.
                        // Native segment text can start inside a prior overlap,
                        // which otherwise produces duplicate on-screen captions.
                        if (retainedWords.isNotEmpty()) {
                            allSegments.add(
                                Segment(
                                    text = retainedWords.joinToString(" ") { it.text }.trim(),
                                    startTimeMs = retainedWords.first().startTimeMs,
                                    endTimeMs = retainedWords.last().endTimeMs,
                                ),
                            )
                        } else if (absoluteSegment.tokens.isEmpty() && absoluteSegment.text.isNotBlank() && absoluteSegment.endMs > discardUntilMs) {
                            val start = absoluteSegment.startMs
                                .coerceAtLeast(discardUntilMs.takeIf { it != Long.MIN_VALUE } ?: absoluteSegment.startMs)
                                .coerceAtLeast(boundedSourceStartMs)
                            val end = min(absoluteSegment.endMs, boundedSourceEndMs)
                            if (end > start) {
                                allSegments.add(
                                    Segment(
                                        text = absoluteSegment.text.trim(),
                                        startTimeMs = start,
                                        endTimeMs = end,
                                    ),
                                )
                            }
                        }
                    }
                    onProgress((windowIndex + 1).toFloat() / totalWindows.coerceAtLeast(1))
                }
            }

            Transcript(
                language = detectedLanguage.ifBlank { "unknown" },
                words = allWords.sortedBy { it.startTimeMs },
                segments = allSegments
                    .sortedBy { it.startTimeMs }
                    .fold(ArrayList<Segment>()) { deduplicated, segment ->
                        val previous = deduplicated.lastOrNull()
                        if (previous == null || segment.endTimeMs > previous.endTimeMs || segment.text != previous.text) {
                            deduplicated.add(segment)
                        }
                        deduplicated
                    },
            )
        } finally {
            activeHandle.compareAndSet(handle, NO_HANDLE)
            WhisperNative.freeModel(handle)
        }
    }

    private suspend fun checkCancelled(isCancelled: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (isCancelled()) {
            cancel()
            throw CancellationException("Transcription cancelled")
        }
    }

    private fun numberOfWindows(totalSamples: Long, windowSamples: Int, hopSamples: Int): Int {
        if (totalSamples <= 0L) return 0
        if (totalSamples <= windowSamples) return 1
        return (1 + ceil((totalSamples - windowSamples).toDouble() / hopSamples).toInt()).coerceAtLeast(1)
    }

    private fun readSamples(raf: RandomAccessFile, startSample: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 4)
        raf.seek(startSample * 4L)
        raf.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buffer.float }
    }

    /** Parses the JNI JSON transcript into native, window-relative segments + tokens. */
    fun parse(resultJson: String): Pair<List<NativeSegment>, String> {
        val root = json.parseToJsonElement(resultJson).jsonObject
        val language = root["language"]?.jsonPrimitive?.content ?: "unknown"
        val segments = ArrayList<NativeSegment>()
        val segmentArray = root["segments"]?.jsonArray ?: return Pair(emptyList(), language)
        for (segmentElement in segmentArray) {
            val segmentObject = segmentElement.jsonObject
            val tokens = ArrayList<WordAssembler.Token>()
            for (tokenElement in segmentObject["tokens"]?.jsonArray ?: emptyList()) {
                val tokenObject = tokenElement.jsonObject
                val text = tokenObject["x"]?.jsonPrimitive?.content ?: continue
                val start = tokenObject["s"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val end = tokenObject["e"]?.jsonPrimitive?.content?.toLongOrNull() ?: start
                tokens.add(
                    WordAssembler.Token(
                        text = text,
                        startMs = start.coerceAtLeast(0L),
                        endMs = end.coerceAtLeast(start),
                        confidence = (tokenObject["p"]?.jsonPrimitive?.content?.toFloatOrNull()
                            ?.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f),
                    ),
                )
            }
            val start = segmentObject["s"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val end = segmentObject["e"]?.jsonPrimitive?.content?.toLongOrNull() ?: start
            segments.add(
                NativeSegment(
                    startMs = start.coerceAtLeast(0L),
                    endMs = end.coerceAtLeast(start),
                    text = segmentObject["text"]?.jsonPrimitive?.content ?: "",
                    tokens = tokens,
                ),
            )
        }
        return Pair(segments, language)
    }
}
