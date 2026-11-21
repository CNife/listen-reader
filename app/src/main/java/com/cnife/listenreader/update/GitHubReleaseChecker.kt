package com.cnife.listenreader.update

import com.cnife.listenreader.core.AppVersion
import com.cnife.listenreader.service.ports.RemoteRelease
import com.cnife.listenreader.service.ports.UpdateCheckException
import com.cnife.listenreader.service.ports.UpdateChecker
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubReleaseChecker(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    var releaseApiUrl: String = RELEASE_API_URL,
) : UpdateChecker {

    override suspend fun latestRelease(): RemoteRelease {
        val request = Request.Builder()
            .url(releaseApiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 403 || response.code == 429 ->
                            throw UpdateCheckException("GitHub 请求过于频繁，请稍后再试")
                        !response.isSuccessful ->
                            throw UpdateCheckException("检查更新失败（HTTP ${response.code}），请稍后重试")
                        else -> response.body?.string().orEmpty()
                    }
                }
            } catch (io: IOException) {
                throw UpdateCheckException("网络连接失败，请检查网络后重试")
            }
        }
        return parseRelease(body)
    }

    private fun parseRelease(body: String): RemoteRelease = try {
        val root = Json.parseToJsonElement(body).jsonObject
        val version = AppVersion.parse(root.stringOrNull("tag_name"))
            ?: throw UpdateCheckException("GitHub 返回的版本号无法识别，请稍后重试")
        val assets = root["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        val apkUrl = assets
            .firstOrNull { asset ->
                asset.stringOrNull("name") == "listen-reader-$version.apk"
            }
            ?.stringOrNull("browser_download_url")
        val url = apkUrl?.takeIf { it.isNotBlank() }
            ?: root.stringOrNull("html_url").orEmpty()
        if (url.isBlank()) throw UpdateCheckException("GitHub Release 缺少下载地址，请稍后重试")
        RemoteRelease(
            version = version.toString(),
            url = url,
            notes = root.stringOrNull("body").orEmpty(),
        )
    } catch (e: UpdateCheckException) {
        throw e
    } catch (e: Exception) {
        throw UpdateCheckException("GitHub 返回数据异常，请稍后重试")
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    companion object {
        const val RELEASE_API_URL = "https://api.github.com/repos/CNife/listen-reader/releases/latest"
        private const val USER_AGENT = "listen-reader"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
