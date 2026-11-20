package com.cnife.listenreader.update

import com.cnife.listenreader.service.ports.UpdateCheckException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * GitHub Releases API 的响应契约：发版工作流发布的附件名为 `listen-reader-<version>.apk`
 * 与 `SHA256SUMS`；`/releases/latest` 只返回非 Draft、非 Pre-release 的正式版。
 */
class GitHubReleaseCheckerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun checker() =
        GitHubReleaseChecker(releaseApiUrl = server.url("/releases/latest").toString())

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    @Test
    fun `apk asset is preferred and normalized version is returned`() = runBlocking {
        enqueue(
            """
            {
              "tag_name": "v0.2.1",
              "html_url": "https://github.com/CNife/listen-reader/releases/tag/v0.2.1",
              "body": "修复若干问题",
              "assets": [
                {"name": "SHA256SUMS", "browser_download_url": "https://example.test/SHA256SUMS"},
                {"name": "listen-reader-0.2.1.apk", "browser_download_url": "https://example.test/download/listen-reader-0.2.1.apk"}
              ]
            }
            """.trimIndent(),
        )

        val release = checker().latestRelease()

        assertEquals("0.2.1", release.version)
        assertEquals("https://example.test/download/listen-reader-0.2.1.apk", release.url)
        assertEquals("修复若干问题", release.notes)
    }

    @Test
    fun `release without apk falls back to the html page`() = runBlocking {
        enqueue(
            """
            {
              "tag_name": "v0.3.0",
              "html_url": "https://github.com/CNife/listen-reader/releases/tag/v0.3.0",
              "body": "",
              "assets": []
            }
            """.trimIndent(),
        )

        val release = checker().latestRelease()

        assertEquals("0.3.0", release.version)
        assertEquals("https://github.com/CNife/listen-reader/releases/tag/v0.3.0", release.url)
        assertEquals("", release.notes)
    }

    @Test
    fun `renamed apk asset is not used and falls back to the html page`() = runBlocking {
        enqueue(
            """
            {
              "tag_name": "v0.4.0",
              "html_url": "https://github.com/CNife/listen-reader/releases/tag/v0.4.0",
              "body": "",
              "assets": [
                {"name": "listen-reader-0.3.9.apk", "browser_download_url": "https://example.test/download/listen-reader-0.3.9.apk"}
              ]
            }
            """.trimIndent(),
        )

        val release = checker().latestRelease()

        assertEquals("0.4.0", release.version)
        assertEquals("https://github.com/CNife/listen-reader/releases/tag/v0.4.0", release.url)
    }

    @Test
    fun `rate limit maps to a readable message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        try {
            checker().latestRelease()
            fail("403 应当抛出 UpdateCheckException")
        } catch (e: UpdateCheckException) {
            assertEquals("GitHub 请求过于频繁，请稍后再试", e.message)
        }
    }

    @Test
    fun `unparseable payload maps to a readable message`() = runBlocking {
        enqueue("""{"tag_name": "latest-release"}""")

        try {
            checker().latestRelease()
            fail("不规范的 tag 应当抛出 UpdateCheckException")
        } catch (e: UpdateCheckException) {
            assertEquals("GitHub 返回的版本号无法识别，请稍后重试", e.message)
        }
    }
}
