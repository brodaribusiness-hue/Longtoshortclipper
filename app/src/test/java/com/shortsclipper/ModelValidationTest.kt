package com.shortsclipper

import com.shortsclipper.ai.WhisperModelValidator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class ModelValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun RandomAccessFile.writeIntLe(value: Int) {
        writeInt(Integer.reverseBytes(value))
    }

    /**
     * Older official whisper.cpp GGML files contain 50,257 serialized tokens
     * but declare a larger embedding vocabulary. whisper.cpp generates the
     * missing special tokens while loading, so this must remain accepted.
     */
    @Test
    fun `legacy official vocabulary tail is accepted`() {
        val file = tmp.newFile("legacy-official.bin")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(WhisperModelValidator.minimumModelBytes())
            raf.seek(0)
            raf.writeIntLe(0x67676D6C) // ggml
            listOf(
                51_864, // embedding vocabulary
                1_500, 384, 6, 4,
                448, 384, 6, 4,
                80, 1,
            ).forEach { raf.writeIntLe(it) }
            raf.writeIntLe(80)
            raf.writeIntLe(201)
            raf.seek(raf.filePointer + 80L * 201L * 4L)
            raf.writeIntLe(50_257) // serialized tokenizer vocabulary
            repeat(50_257) { raf.writeIntLe(0) }
        }

        val header = WhisperModelValidator.inspect(file)
        assertEquals(51_864, header.nVocab)
        assertEquals(201, header.nFft)
    }
}
