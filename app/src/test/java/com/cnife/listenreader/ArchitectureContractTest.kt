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

/**
 * 架构与资源契约：把 .no-mistakes.yaml 依赖 AI Review 反复发现的规则
 * （服务层禁用 runBlocking、端口不留死成员、Room Schema 同步、模板正则有效）
 * 变成确定性的 JVM 单测，在编码阶段就给出可执行的失败信号。
 */
class ArchitectureContractTest {

    /** 兼容从仓库根目录（`./gradlew`）与从 `:app` 子目录运行两种情形。 */
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
        // 回归 PR #21：实现类内部调用自己的端口成员曾把死成员伪装成「有消费者」。
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

        // 回归 PR #20：端口文件里 data class 上新增的公开 fun 曾完全逃出检测。
        val settingsPort = """
            interface SettingsStore {
                suspend fun setThemeMode(mode: String)
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

        // 私有帮助函数（含 suspend/inline 等隔开修饰符变体）不属于公开接口面，不得误报死成员。
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

    /** 返回端口死成员；接口成员用 `Interface.member`，端口 data class 上的公开 fun 用裸名。 */
    private fun unusedMembers(portSource: String, production: List<ProductionFile>): List<String> {
        val text = stripComments(portSource)
        val ports = portInterfaces(text)

        // 实现端口的具体类必然出现 override 声明，它对自己的内部调用不是「外部消费者」。
        // 不排除它，`RoomLibraryStore` 里的 snapshotFile(id) 就会把死成员判成活的。
        val deadInterfaceMembers = ports.flatMap { port ->
            val externalCallers = production.filterNot { implements(port, it) }
            port.members.filterNot { member ->
                externalCallers.any { file ->
                    file.lines.any { line -> !overrideDeclaration(member, line) && wordReference(member, line) }
                }
            }.map { "${port.name}.$it" }
        }

        // 端口文件里的 data class 不承载端口契约，但它上面的公开 fun 同样是生产接口面。
        val deadLooseFuns = loosePublicFuns(text, ports).filterNot { funName ->
            production.any { file ->
                file.lines.any { line -> !definitionLine(funName, line) && wordReference(funName, line) }
            }
        }
        return deadInterfaceMembers + deadLooseFuns
    }

    /**
     * 完整实现一个端口的类必然 override 它的全部抽象成员；只靠「出现同一个成员名」不够，
     * 服务实现类与多个端口本来就有同名成员（如 deleteResource），那会把真实调用者误排除。
     */
    private fun implements(port: PortInterface, file: ProductionFile): Boolean {
        if (port.members.isEmpty()) return false
        return port.members.all { member -> file.lines.any { overrideDeclaration(member, it) } }
    }

    /** 提取每个非 sealed 接口名、其声明的 fun/val 成员名，以及成员所在的源码区间。 */
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
            val members = MEMBER_DECL.findAll(text.substring(bodyStart + 1, bodyEnd))
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            result += PortInterface(match.groupValues[1], members, bodyStart, bodyEnd)
        }
        return result
    }

    /** 端口目录内、接口体之外声明的非私有 fun（data class / class / object 上的公开方法）。 */
    private fun loosePublicFuns(text: String, ports: List<PortInterface>): List<String> =
        FUN_DECL.findAll(text)
            .filterNot { match -> ports.any { match.range.first in it.bodyStart..it.bodyEnd } }
            .filterNot { match -> nonPublicDeclaration(text, match.range.first) }
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /** 声明行中 fun 关键字之前出现 private/protected 修饰符（含 suspend/inline 等隔开变体）。 */
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

    /** 同一源码文件；路径只用于诊断，[lines] 已剥离注释。 */
    private class ProductionFile(val path: String, val lines: List<String>)

    /** 一个非 sealed 端口接口：名字、声明的成员，以及 `{`…`}` 在文件中的下标。 */
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
