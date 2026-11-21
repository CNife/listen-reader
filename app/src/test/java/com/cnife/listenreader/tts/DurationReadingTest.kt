package com.cnife.listenreader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.core.ErrorKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurationReadingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `missing audio file yields no duration`() {
        assertNull(readDurationMs(File(tempFolder.root, "missing.mp3")))
    }

    @Test
    fun `corrupt audio file yields no duration`() {
        val file = tempFolder.newFile("corrupt.mp3").apply { writeText("not an mp3") }

        assertNull(readDurationMs(file))
    }

    @Test
    fun `readable duration is returned unchanged`() {
        val durationMs = requireDurationMs(File("ok.mp3"), { 1_234L }, { })

        assertEquals(1_234L, durationMs)
    }

    @Test
    fun `unreadable duration raises retryable synthesis error and logs the file`() {
        val logs = mutableListOf<String>()

        val error = runCatching {
            requireDurationMs(File("broken.mp3"), { null }, { logs += it })
        }.exceptionOrNull()

        assertTrue(error is SpeechException)
        error as SpeechException
        assertEquals(ErrorKind.UNKNOWN, error.kind)
        assertTrue(error.retryable)
        assertEquals("音频时长读取失败，请稍后重试", error.message)
        assertEquals(listOf("duration unavailable: broken.mp3"), logs)
    }

    @Test
    fun `zero duration is treated as unreadable`() {
        val logs = mutableListOf<String>()

        val error = runCatching {
            requireDurationMs(File("empty.mp3"), { 0L }, { logs += it })
        }.exceptionOrNull()

        assertTrue(error is SpeechException)
        assertEquals(listOf("duration unavailable: empty.mp3"), logs)
    }
}
