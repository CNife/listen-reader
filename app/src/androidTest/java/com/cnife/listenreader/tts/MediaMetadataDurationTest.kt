package com.cnife.listenreader.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机/模拟器上验证 MediaMetadataRetriever 能读到 fixture MP3 的时长。 */
@RunWith(AndroidJUnit4::class)
class MediaMetadataDurationTest {

    @Test
    fun fixtureMp3DurationIsPositive() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.cacheDir, "audio-fixture.mp3")
        context.assets.open("audio-fixture.mp3").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val durationMs = readDurationMs(file)
        assertTrue("fixture duration should be > 0 but was $durationMs", durationMs > 0)
        assertTrue("fixture is a 2s tone but read $durationMs ms", durationMs >= 1_800)
    }
}
