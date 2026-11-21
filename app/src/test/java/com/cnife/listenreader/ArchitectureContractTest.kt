package com.cnife.listenreader

import java.io.File
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureContractTest {

    private fun appModuleDir(): File =
        if (File("src/main").exists()) File(".") else File("app")

    private fun mainSourceDir() = File(appModuleDir(), "src/main/java/com/cnife/listenreader")

    private fun allKotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun serviceLayer_neverUsesRunBlocking() {
        val violations = mutableListOf<String>()
        for (file in allKotlinFiles(File(mainSourceDir(), "service"))) {
            val code = stripComments(file.readText())
            code.lineSequence().forEachIndexed { index, line ->
                if (RUN_BLOCKING.containsMatchIn(line)) {
                    violations += "${file.relativeTo(mainSourceDir())}:${index + 1}"
                }
            }
        }
        assertTrue("service 层禁止 runBlocking，违规位点：$violations", violations.isEmpty())
    }

    @Test
    fun servicePorts_everyDeclaredMemberHasProductionCaller() {
        val portsDir = File(mainSourceDir(), "service/ports")
        val production = allKotlinFiles(mainSourceDir())
            .filterNot { it.path.startsWith(portsDir.path) }
            .map { file -> ProductionFile(file.absolutePath, stripComments(file.readText()).lines()) }

        val violations = allKotlinFiles(portsDir).flatMap { port ->
            unusedMembers(port.readText(), production)
                .map { "Unused port member: $it (${port.name})" }
        }
        assertTrue("service/ports 只允许生产代码实际调用的成员，违规位点：$violations", violations.isEmpty())
    }

    @Test
    fun portMemberDetector_flagsImplementerSelfCallsAndPortDataClassFuns() {
        val libraryPort = """
            interface LibraryStore {
                fun audioFile(id: String): File
                fun snapshotFile(id: String): File
            }
        """.trimIndent()
        val libraryImpl = """
            class RoomLibraryStore : LibraryStore {
                override fun audioFile(id: String): File = File("/audio")
                override fun snapshotFile(id: String): File = File("/snapshot")
                fun delete(id: String) {
                    snapshotFile(id).delete()
                }
            }
        """.trimIndent()
        val libraryCaller = """
            fun readAudio(store: LibraryStore) {
                store.audioFile("x")
            }
        """.trimIndent()

        assertEquals(
            listOf("LibraryStore.snapshotFile"),
            unusedMembers(
                libraryPort,
                listOf(
                    ProductionFile("RoomLibraryStore.kt", stripComments(libraryImpl).lines()),
                    ProductionFile("Caller.kt", stripComments(libraryCaller).lines()),
                ),
            ),
        )

        val settingsPort = """
            interface SettingsStore {
                suspend fun setThemeMode(mode: String)

                private suspend fun sharedDefault(): Boolean = true
            }
            data class AppSettings(
                val themeMode: String = "system",
                val autoCheckUpdate: Boolean = true,
            ) {
                fun isKeyConfiguredFor(model: String): Boolean = false

                private suspend fun helper(): Boolean = true
            }
        """.trimIndent()
        val settingsProduction = """
            suspend fun applyTheme(store: SettingsStore) {
                store.setThemeMode("dark")
            }
        """.trimIndent()

        assertEquals(
            listOf("isKeyConfiguredFor"),
            unusedMembers(
                settingsPort,
                listOf(ProductionFile("SettingsSetup.kt", stripComments(settingsProduction).lines())),
            ),
        )
    }

    @Test
    fun roomSchema_versionOneIsExportedAndComplete() {
        val schema = File(
            appModuleDir(),
            "schemas/com.cnife.listenreader.data.db.ListenReaderDatabase/1.json",
        )
        assertTrue("缺少导出的 Room Schema：${schema.path}", schema.isFile)

        val root = Json.parseToJsonElement(schema.readText()).jsonObject
        val database = root["database"]?.jsonObject
            ?: throw AssertionError("Schema 缺少 database 节点：${schema.path}")

        val version = database["version"]?.jsonPrimitive?.content?.toInt()
        assertEquals("Room Schema 版本必须是 1；改实体前先升版本并导出新 Schema", 1, version)

        val tableNames = database["entities"]!!.jsonArray
            .map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(
            "Room Schema 必须完整导出四张表；改实体后重新运行 :app:kspDebugKotlin 并提交 app/schemas/",
            setOf("resources", "segments", "audio_tracks", "audio_clips"),
            tableNames,
        )
    }

    @Test
    fun extractionTemplates_jsonAndRegexPatternsAreValid() {
        val templatesFile = File(appModuleDir(), "src/main/assets/extract/templates.json")
        assertTrue("缺少提取模板：${templatesFile.path}", templatesFile.isFile)

        val root = Json.parseToJsonElement(templatesFile.readText()).jsonObject
        val templates = root["templates"]!!.jsonArray
        assertTrue("模板列表不能为空", templates.isNotEmpty())

        for ((index, element) in templates.withIndex()) {
            val template = element.jsonObject
            val name = template["name"]?.jsonPrimitive?.content ?: "template[$index]"
            val hostPattern = template["hostPattern"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("$name 的 hostPattern 不能为空", hostPattern.isNotBlank())
            Regex(hostPattern)
            val urlPattern = template["urlPattern"]?.jsonPrimitive?.content
            if (!urlPattern.isNullOrBlank()) Regex(urlPattern)
            for (field in SELECTOR_FIELDS) {
                val selectors = template[field]?.jsonArray ?: continue
                selectors.forEach { selector ->
                    assertTrue(
                        "$name 的 $field 含空白选择器",
                        selector.jsonPrimitive.content.isNotBlank(),
                    )
                }
            }
        }
    }

    @Test
    fun runBlockingDetector_ignoresCommentsButCatchesRealCalls() {
        assertFalse(RUN_BLOCKING.containsMatchIn(stripComments("// 不要用 runBlocking 做 IO")))
        assertFalse(RUN_BLOCKING.containsMatchIn(stripComments("/* runBlocking 是阻塞的 */ val x = 1")))
        assertTrue(RUN_BLOCKING.containsMatchIn(stripComments("val value = runBlocking { 1 }")))
    }

    private fun unusedMembers(portSource: String, production: List<ProductionFile>): List<String> {
        val text = stripComments(portSource)
        val ports = portInterfaces(text)

        val deadInterfaceMembers = ports.flatMap { port ->
            val externalCallers = production.filterNot { implements(port, it) }
            port.members.filterNot { member ->
                externalCallers.any { file ->
                    file.lines.any { line -> !overrideDeclaration(member, line) && wordReference(member, line) }
                }
            }.map { "${port.name}.$it" }
        }

        val deadLooseFuns = loosePublicFuns(text, ports).filterNot { funName ->
            production.any { file ->
                file.lines.any { line -> !definitionLine(funName, line) && wordReference(funName, line) }
            }
        }
        return deadInterfaceMembers + deadLooseFuns
    }

    private fun implements(port: PortInterface, file: ProductionFile): Boolean {
        if (port.members.isEmpty()) return false
        return port.members.all { member -> file.lines.any { overrideDeclaration(member, it) } }
    }

    private fun portInterfaces(text: String): List<PortInterface> {
        val result = mutableListOf<PortInterface>()
        for (match in INTERFACE_DECL.findAll(text)) {
            val start = match.range.first
            val headerStart = maxOf(0, start - 40)
            if (text.substring(headerStart, start).contains("sealed")) continue
            val bodyStart = text.indexOf('{', match.range.last + 1)
            if (bodyStart < 0) continue
            val bodyEnd = matchingBrace(text, bodyStart)
            if (bodyEnd < 0) continue
            val bodyOffset = bodyStart + 1
            val members = MEMBER_DECL.findAll(text.substring(bodyOffset, bodyEnd))
                .filterNot { match -> nonPublicDeclaration(text, match.range.first + bodyOffset) }
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            result += PortInterface(match.groupValues[1], members, bodyStart, bodyEnd)
        }
        return result
    }

    private fun loosePublicFuns(text: String, ports: List<PortInterface>): List<String> =
        FUN_DECL.findAll(text)
            .filterNot { match -> ports.any { match.range.first in it.bodyStart..it.bodyEnd } }
            .filterNot { match -> nonPublicDeclaration(text, match.range.first) }
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    private fun nonPublicDeclaration(text: String, funStart: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', maxOf(0, funStart - 1)).let { if (it < 0) 0 else it + 1 }
        return NON_PUBLIC_MODIFIER.containsMatchIn(text.substring(lineStart, funStart))
    }

    private fun matchingBrace(text: String, openIndex: Int): Int {
        var depth = 0
        for (i in openIndex until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun definitionLine(member: String, line: String): Boolean =
        DEFINITION_MEMBER(member).containsMatchIn(line)

    private fun overrideDeclaration(member: String, line: String): Boolean =
        OVERRIDE_MEMBER(member).containsMatchIn(line)

    private fun wordReference(member: String, line: String): Boolean =
        WORD_BOUNDARY(member).containsMatchIn(line)

    private fun stripComments(source: String): String {
        val withoutBlock = BLOCK_COMMENT.replace(source) { match ->
            "\n".repeat(match.value.count { it == '\n' })
        }
        return withoutBlock.lineSequence()
            .joinToString("\n") { line ->
                line.indexOf("//").let { if (it >= 0) line.take(it) else line }
            }
    }

    private val overrideCache = mutableMapOf<String, Regex>()
    private val wordCache = mutableMapOf<String, Regex>()

    private fun OVERRIDE_MEMBER(member: String): Regex = overrideCache.getOrPut(member) {
        Regex("""\boverride\s+(?:suspend\s+)?(?:fun|val)\s+${Pattern.quote(member)}\b""")
    }

    private fun WORD_BOUNDARY(member: String): Regex = wordCache.getOrPut(member) {
        Regex("""\b${Pattern.quote(member)}\b""")
    }

    private val definitionCache = mutableMapOf<String, Regex>()

    private fun DEFINITION_MEMBER(member: String): Regex = definitionCache.getOrPut(member) {
        Regex("""\b(?:override\s+)?(?:suspend\s+)?fun\s+${Pattern.quote(member)}\b""")
    }

    private class ProductionFile(val path: String, val lines: List<String>)

    private class PortInterface(
        val name: String,
        val members: List<String>,
        val bodyStart: Int,
        val bodyEnd: Int,
    )

    private companion object {
        val RUN_BLOCKING = Regex("""\brunBlocking\b""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val INTERFACE_DECL = Regex("""interface\s+(\w+)""")
        val MEMBER_DECL = Regex("""\b(?:fun|val)\s+(\w+)""")
        val FUN_DECL = Regex("""\bfun\s+(\w+)""")
        val NON_PUBLIC_MODIFIER = Regex("""\b(private|protected)\b""")
        val SELECTOR_FIELDS = listOf(
            "titleSelector",
            "contentSelector",
            "removeSelectors",
            "revealSelectors",
            "waitForSelector",
        )
    }
}
