package com.cnife.listenreader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaMetadataDurationTest {

    @Test
    fun fixtureMp3DurationIsPositive() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDir = targetContext.cacheDir.apply { mkdirs() }
        val file = File(cacheDir, "audio-fixture.mp3")
        testContext.assets.open("audio-fixture.mp3").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val durationMs = readDurationMs(file)
        assertTrue("fixture duration should be > 0 but was $durationMs", durationMs > 0)
        assertTrue("fixture is a 2s tone but read $durationMs ms", durationMs >= 1_800)
    }
}
