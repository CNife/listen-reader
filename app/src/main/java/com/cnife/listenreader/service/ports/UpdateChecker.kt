package com.cnife.listenreader.service.ports

/** 远端最新正式版（GitHub Release）的可跳转信息。 */
data class RemoteRelease(
    /** 规范三段版本号，如 `1.2.3`。 */
    val version: String,
    /** 优先为 APK 下载直链，找不到附件时为 Release 详情页。 */
    val url: String,
    /** Release 正文；为空表示没有更新说明。 */
    val notes: String,
)

/** 查询远端最新正式版；失败时抛出 [UpdateCheckException]。 */
interface UpdateChecker {
    suspend fun latestRelease(): RemoteRelease
}

/** 更新检查失败的可读原因，由 service 原样展示给用户。 */
class UpdateCheckException(message: String) : Exception(message)
