package com.shortsclipper.ai

import com.shortsclipper.model.Word

/**
 * Assembles whisper.cpp token stream (with token-level timestamps) into words.
 * Tokens starting with a space/ sentencepiece underscore begin a new word;
 * punctuation attaches to the current word.
 */
object WordAssembler {

    const val TOKEN_PREFIX = "▁"

    data class Token(val text: String, val startMs: Long, val endMs: Long, val confidence: Float)

    fun isWordStart(rawText: String): Boolean {
        val t = rawText.trim()
        if (t.isEmpty()) return false
        return rawText.startsWith(" ") || rawText.startsWith(TOKEN_PREFIX)
    }

    fun cleanToken(rawText: String): String = rawText
        .replace(TOKEN_PREFIX, " ")
        .trim()

    fun isSpecialToken(rawText: String): Boolean {
        val t = rawText.trim()
        return t.isEmpty() || (t.startsWith("[_") && t.endsWith("_]")) || t == "[BLANK_AUDIO]" || t == "[SILENCE]"
    }

    /** Groups tokens into words. Assumes tokens are time-ordered. */
    fun assemble(tokens: List<Token>): List<Word> {
        val words = ArrayList<Word>()
        var current = ArrayList<Token>()
        fun flush() {
            if (current.isEmpty()) return
            val text = current.joinToString("") { cleanToken(it.text) }.trim()
            if (text.isNotEmpty()) {
                words.add(
                    Word(
                        text = text,
                        startTimeMs = current.first().startMs,
                        endTimeMs = current.last().endMs,
                        confidence = current.map { it.confidence }.average().toFloat(),
                    )
                )
            }
            current = ArrayList()
        }
        for (token in tokens) {
            if (isSpecialToken(token.text)) continue
            if (isWordStart(token.text) && current.isNotEmpty()) flush()
            current.add(token)
        }
        flush()
        return words
    }

    /**
     * Filters words of a sliding window so overlapping windows never produce
     * duplicate words. The first window keeps everything.
     */
    fun wordsForWindow(words: List<Word>, windowStartMs: Long, overlapMs: Long, isFirstWindow: Boolean): List<Word> {
        if (isFirstWindow) return words
        val cutoff = windowStartMs + overlapMs
        return words.filter { it.startTimeMs >= cutoff }
    }
}
