package com.cnife.listenreader.playback

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnife.listenreader.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundPlaybackTest {

    private lateinit var context: Context
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private lateinit var controller: MediaController
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        grantNotificationPermission()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(1_500)
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        controllerFuture = MediaController.Builder(context, token).buildAsync()
        controller = controllerFuture.get(10, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        runCatching { onMain { controller.pause() } }
        runCatching { onMain { MediaController.releaseFuture(controllerFuture) } }
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    @Test
    fun backgroundPlaybackKeepsPlayingWithNotificationControls() {
        prepareFixtureAndPlay()

        awaitPlaying(true)
        assertTrue("媒体通知应在播放时出现", hasPlaybackNotification())
        snapshot("t8-background.png")

        shell("cmd statusbar expand-notifications")
        Thread.sleep(500)
        snapshot("t8-notification.png")
        shell("cmd statusbar collapse")
        Thread.sleep(300)

        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(2_000)
        assertTrue("按 Home 后应继续播放", isPlaying())
        snapshot("t8-home.png")

        shell("input keyevent KEYCODE_MEDIA_PAUSE")
        awaitPlaying(false)
        assertFalse("媒体键应能暂停", isPlaying())

        shell("input keyevent KEYCODE_MEDIA_PLAY")
        awaitPlaying(true)
        assertTrue("媒体键应能恢复播放", isPlaying())

        assertTrue(
            "媒体会话应能通过 dumpsys 查询到播放状态",
            shell("dumpsys media_session").contains(context.packageName),
        )

        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(2_000)
        assertTrue("锁屏后应继续播放", isPlaying())
        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(1_000)
        assertTrue("解锁前应仍在播放", isPlaying())
    }

    private fun prepareFixtureAndPlay() {
        val fixture = File(context.cacheDir, "background-fixture.mp3")
        instrumentation.context.assets.open("audio-long-fixture.mp3").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(fixture))
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle("听我读后台播放测试").build(),
            )
            .build()
        onMain {
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        }
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        runCatching {
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        Thread.sleep(500)
    }

    private fun hasPlaybackNotification(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.activeNotifications.any { it.packageName == context.packageName }
    }

    private fun awaitPlaying(expected: Boolean, timeoutMs: Long = 6_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPlaying() == expected) return
            Thread.sleep(200)
        }
        throw AssertionError("等待播放状态 $expected 超时")
    }

    private fun isPlaying(): Boolean {
        val value = AtomicBoolean()
        instrumentation.runOnMainSync { value.set(controller.isPlaying) }
        return value.get()
    }

    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun snapshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.filesDir, name).outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation
            .executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }
}
