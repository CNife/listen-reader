package com.cnife.listenreader.core

/**
 * 规范三段版本号，映射规则与 `.github/workflows/release.yml` 的 versionCode 完全一致：
 * `major * 1_000_000 + minor * 1_000 + patch`。
 * 因此三段都不接受前导零，minor 与 patch 必须小于 1000，且 0.0.0 不合法。
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    val code: Int
        get() = major * UNIT * UNIT + minor * UNIT + patch

    override fun compareTo(other: AppVersion): Int = code.compareTo(other.code)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        const val UNIT = 1_000

        private val PATTERN = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")

        /** 解析 `v1.2.3` 或 `1.2.3`；不规范的版本号返回 null，调用方按无版本处理。 */
        fun parse(raw: String?): AppVersion? {
            val match = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            if (minor >= UNIT || patch >= UNIT) return null
            val code = major.toLong() * UNIT * UNIT + minor.toLong() * UNIT + patch
            // Android 的 versionCode 是 int；0.0.0 与越界值都不是合法发版。
            if (code < 1L || code > Int.MAX_VALUE) return null
            return AppVersion(major, minor, patch)
        }
    }
}
