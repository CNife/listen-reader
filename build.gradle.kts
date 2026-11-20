plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
}

// 自动挂载仓库自带的 .githooks（git worktree 共享仓库配置，主工作区与隔离 worktree 均生效）。
// 每次配置只做一次极轻量的 git config 查询，不引入外部 pre-commit 运行时。
runCatching {
    val hook = rootDir.resolve(".githooks/pre-commit")
    if (rootDir.resolve(".git").exists() && hook.isFile) {
        hook.setExecutable(true)
        val current = ProcessBuilder("git", "config", "--get", "core.hooksPath")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                process.waitFor()
                output
            }
        if (current != ".githooks") {
            ProcessBuilder("git", "config", "core.hooksPath", ".githooks")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
                .inputStream
                .readBytes()
        }
    }
}
