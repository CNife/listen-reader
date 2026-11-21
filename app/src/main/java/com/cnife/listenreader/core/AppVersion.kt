package com.cnife.listenreader.core

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    val code: Int
        get() = major * UNIT * UNIT + minor * UNIT + patch

    override fun compareTo(other: AppVersion): Int = code.compareTo(other.code)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        const val UNIT = 1_000

        private val PATTERN = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")

        fun parse(raw: String?): AppVersion? {
            val match = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            if (minor >= UNIT || patch >= UNIT) return null
            val code = major.toLong() * UNIT * UNIT + minor.toLong() * UNIT + patch
            if (code < 1L || code > Int.MAX_VALUE) return null
            return AppVersion(major, minor, patch)
        }
    }
}
