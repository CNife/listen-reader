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
        val productionLines = allKotlinFiles(mainSourceDir())
            .filterNot { it.path.startsWith(portsDir.path) }
            .map { stripComments(it.readText()).lines() }

        val violations = allKotlinFiles(portsDir).flatMap { port ->
            unusedMembers(port.readText(), productionLines)
                .map { "Unused port member: $it (${port.name})" }
        }
        assertTrue("service/ports 只允许生产代码实际调用的成员，违规位点：$violations", violations.isEmpty())
    }

    @Test
    fun portMemberDetector_flagsDeadMembersAndIgnoresOverrideDeclarations() {
        val port = """
            interface DemoPort {
                fun used()
                fun dead()
                val usedValue: Int
            }
        """.trimIndent()
        val production = listOf(
            """
            class DemoImpl : DemoPort {
                override fun used() = Unit
                override fun dead() = Unit
                override val usedValue: Int = 1
            }
            fun call(port: DemoPort) {
                port.used()
                println(port.usedValue)
            }
            """.trimIndent(),
        ).map { stripComments(it).lines() }

        assertEquals(listOf("DemoPort.dead"), unusedMembers(port, production))
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

    /** 返回 `Interface.member` 形式的死端口成员。 */
    private fun unusedMembers(portSource: String, productionLines: List<List<String>>): List<String> =
        portInterfaces(stripComments(portSource)).flatMap { (iface, members) ->
            members.filterNot { member ->
                productionLines.any { lines ->
                    lines.any { line -> !overrideDeclaration(member, line) && wordReference(member, line) }
                }
            }.map { "$iface.$it" }
        }

    /** 提取每个非 sealed 接口名及其声明的 fun/val 成员名。 */
    private fun portInterfaces(text: String): List<Pair<String, List<String>>> {
        val result = mutableListOf<Pair<String, List<String>>>()
        for (match in INTERFACE_DECL.findAll(text)) {
            val start = match.range.first
            val headerStart = maxOf(0, start - 40)
            if (text.substring(headerStart, start).contains("sealed")) continue
            val bodyStart = text.indexOf('{', match.range.last + 1)
            if (bodyStart < 0 || bodyStart < match.range.last) continue
            val bodyEnd = matchingBrace(text, bodyStart)
            if (bodyEnd < 0) continue
            val members = MEMBER_DECL.findAll(text.substring(bodyStart + 1, bodyEnd))
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            result += match.groupValues[1] to members
        }
        return result
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

    private companion object {
        val RUN_BLOCKING = Regex("""\brunBlocking\b""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val INTERFACE_DECL = Regex("""interface\s+(\w+)""")
        val MEMBER_DECL = Regex("""\b(?:fun|val)\s+(\w+)""")
        val SELECTOR_FIELDS = listOf(
            "titleSelector",
            "contentSelector",
            "removeSelectors",
            "revealSelectors",
            "waitForSelector",
        )
    }
}
