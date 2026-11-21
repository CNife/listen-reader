package com.cnife.listenreader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPolicyTest {

    @Test
    fun firstPartySources_stayWithinCommentBudget() {
        val root = repoRoot()
        val baseline = File(root, BASELINE)
        val actual = sourceFiles(root)
            .associate { file ->
                file.relativeTo(root).invariantSeparatorsPath to commentLines(file).size
            }
            .filterValues { it > 0 }

        if (System.getenv("UPDATE_COMMENT_BASELINE") == "1") {
            writeBaseline(baseline, actual)
            return
        }

        val budgets = readBaseline(baseline)
        val violations = actual
            .filter { (path, count) -> count > (budgets[path] ?: 0) }
            .map { (path, count) -> "$path: $count > ${budgets[path] ?: 0}" }
            .sorted()

        assertTrue(
            "第一方源码不写注释，把「为什么」写进 docs/adr/；超标：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun budgetEntries_pointAtExistingFiles() {
        val root = repoRoot()
        val stale = readBaseline(File(root, BASELINE)).keys
            .filterNot { File(root, it).isFile }
            .sorted()
        assertTrue("注释预算里的文件已不存在：$stale", stale.isEmpty())
    }

    private fun sourceFiles(root: File): List<File> =
        SCAN_ROOTS.flatMap { relative ->
            val target = File(root, relative)
            when {
                target.isFile -> listOf(target)
                target.isDirectory -> target.walkTopDown()
                    .filter { file ->
                        file.isFile && (
                            file.extension in EXTENSIONS ||
                                (relative == ".githooks" && file.extension.isEmpty())
                            )
                    }
                    .filterNot { excluded(it.relativeTo(root).invariantSeparatorsPath) }
                    .toList()
                else -> emptyList()
            }
        }.distinct()

    private fun excluded(path: String): Boolean =
        path == "app/src/main/assets/extract/readability.js" ||
            path.startsWith("app/src/androidTest/assets/") ||
            path.startsWith("app/src/test/resources/")

    private fun commentLines(file: File): Set<Int> {
        val text = file.readText()
        return when (file.extension) {
            "py" -> pythonCommentLines(text)
            "xml" -> xmlCommentLines(text)
            "yml", "yaml" -> yamlCommentLines(text)
            "sh", "" -> shellCommentLines(text)
            else -> cLikeCommentLines(text)
        }
    }

    private fun shellCommentLines(text: String): Set<Int> {
        val lines = sortedSetOf<Int>()
        var index = 0
        var line = 1
        var quote: Char? = null
        while (index < text.length) {
            val char = text[index]
            if (char == '\n') {
                line++
                index++
                continue
            }
            val open = quote
            if (open != null) {
                when {
                    open == '"' && char == '\\' -> index += 2
                    char == open -> {
                        quote = null
                        index++
                    }
                    else -> index++
                }
                continue
            }
            when {
                char == '\'' || char == '"' -> {
                    quote = char
                    index++
                }
                char == '#' && (index == 0 || text[index - 1].isWhitespace()) -> {
                    if (index != 0) lines += line
                    while (index < text.length && text[index] != '\n') index++
                }
                else -> index++
            }
        }
        return lines
    }

    private fun cLikeCommentLines(text: String): Set<Int> {
        val lines = sortedSetOf<Int>()
        var index = 0
        var line = 1
        var quote: String? = null
        var block = false
        while (index < text.length) {
            val char = text[index]
            if (char == '\n') {
                line++
                index++
                continue
            }
            if (block) {
                lines += line
                if (char == '*' && text.getOrNull(index + 1) == '/') {
                    block = false
                    index += 2
                } else {
                    index++
                }
                continue
            }
            val open = quote
            if (open != null) {
                when {
                    char == '\\' && open != "\"\"\"" -> index += 2
                    text.startsWith(open, index) -> {
                        quote = null
                        index += open.length
                    }
                    else -> index++
                }
                continue
            }
            when {
                char == '"' && text.startsWith("\"\"\"", index) -> {
                    quote = "\"\"\""
                    index += 3
                }
                char == '"' || char == '\'' || char == '`' -> {
                    quote = char.toString()
                    index++
                }
                char == '/' && text.getOrNull(index + 1) == '/' -> {
                    lines += line
                    while (index < text.length && text[index] != '\n') index++
                }
                char == '/' && text.getOrNull(index + 1) == '*' -> {
                    block = true
                    lines += line
                    index += 2
                }
                else -> index++
            }
        }
        return lines
    }

    private fun pythonCommentLines(text: String): Set<Int> {
        val lines = sortedSetOf<Int>()
        val metadata = pythonMetadataLines(text)
        var index = 0
        var line = 1
        var quote: String? = null
        while (index < text.length) {
            val char = text[index]
            if (char == '\n') {
                line++
                index++
                continue
            }
            val open = quote
            if (open != null) {
                when {
                    char == '\\' -> index += 2
                    text.startsWith(open, index) -> {
                        quote = null
                        index += open.length
                    }
                    else -> index++
                }
                continue
            }
            when {
                char == '"' || char == '\'' -> {
                    val triple = "$char$char$char"
                    if (text.startsWith(triple, index)) {
                        quote = triple
                        index += 3
                    } else {
                        quote = char.toString()
                        index++
                    }
                }
                char == '#' -> {
                    if (line !in metadata) lines += line
                    while (index < text.length && text[index] != '\n') index++
                }
                else -> index++
            }
        }
        return lines
    }

    private fun pythonMetadataLines(text: String): Set<Int> {
        val metadata = mutableSetOf<Int>()
        var inScript = false
        text.lineSequence().forEachIndexed { offset, value ->
            val trimmed = value.trimStart()
            when {
                trimmed.startsWith("# /// script") -> {
                    inScript = true
                    metadata += offset + 1
                }
                inScript && trimmed.startsWith("# ///") -> {
                    inScript = false
                    metadata += offset + 1
                }
                inScript || trimmed.startsWith("#!") -> metadata += offset + 1
            }
        }
        return metadata
    }

    private fun xmlCommentLines(text: String): Set<Int> {
        val lines = sortedSetOf<Int>()
        var index = 0
        var line = 1
        while (index < text.length) {
            val char = text[index]
            if (char == '\n') {
                line++
                index++
                continue
            }
            if (char == '<' && text.startsWith("<!--", index)) {
                lines += line
                index += 4
                while (index < text.length && !text.startsWith("-->", index)) {
                    if (text[index] == '\n') {
                        line++
                        lines += line
                    }
                    index++
                }
                index += 3
                continue
            }
            index++
        }
        return lines
    }

    private fun yamlCommentLines(text: String): Set<Int> {
        val lines = sortedSetOf<Int>()
        var index = 0
        var line = 1
        var quote: Char? = null
        while (index < text.length) {
            val char = text[index]
            if (char == '\n') {
                line++
                quote = null
                index++
                continue
            }
            val open = quote
            if (open != null) {
                when {
                    open == '"' && char == '\\' -> index += 2
                    char == open -> {
                        quote = null
                        index++
                    }
                    else -> index++
                }
                continue
            }
            when {
                char == '"' || char == '\'' -> {
                    quote = char
                    index++
                }
                char == '#' && (index == 0 || text[index - 1].isWhitespace()) -> {
                    lines += line
                    while (index < text.length && text[index] != '\n') index++
                }
                else -> index++
            }
        }
        return lines
    }

    private fun readBaseline(file: File): Map<String, Int> =
        if (!file.isFile) {
            emptyMap()
        } else {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val separator = line.indexOf(' ')
                    if (separator <= 0) {
                        null
                    } else {
                        line.substring(0, separator).toIntOrNull()?.let { count ->
                            line.substring(separator + 1) to count
                        }
                    }
                }
                .toMap()
        }

    private fun writeBaseline(file: File, actual: Map<String, Int>) {
        file.parentFile?.mkdirs()
        val body = actual.entries
            .sortedBy { it.key }
            .joinToString("\n") { (path, count) -> "$count $path" }
        file.writeText(
            "# 每个文件的注释行预算，只列非零项；删除注释后用 UPDATE_COMMENT_BASELINE=1 收紧。\n$body" +
                if (body.isEmpty()) "" else "\n",
        )
    }

    private fun repoRoot(): File {
        val current = File(".").canonicalFile
        return if (File(current, "app/src/main").isDirectory) current else current.parentFile
    }

    private companion object {
        const val BASELINE = "app/comment-baseline.txt"
        val EXTENSIONS = setOf("kt", "kts", "js", "mjs", "py", "xml", "yml", "yaml", "sh")
        val SCAN_ROOTS = listOf(
            "app/src/main/java",
            "app/src/main/assets/extract",
            "app/src/debug",
            "app/src/main/res",
            "app/src/test/java",
            "app/src/androidTest/java",
            "scripts",
            ".github/workflows",
            ".githooks",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "settings.gradle.kts",
        )
    }
}
