package com.cnife.listenreader.capture.webview

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsExtractorContractTest {

    @Test
    fun genericArticle_extractsMarkdownTitleBylineAndLiftsLazyImage() {
        withSession("article.html", "https://example.com/article") { session ->
            val l1 = session.extract()
            assertEquals("在通勤路上听见长文", l1.string("title"))
            assertEquals("潭渊", l1.string("byline"))
            assertTrue(l1.string("markdown").contains("把小屏幕变成耳朵"))
            assertTrue(l1.string("html").contains("https://example.com/photo.jpg"))
            assertFalse(l1.boolean("needsExpand"))
        }
    }

    @Test
    fun readabilityEngine_isLoadedAndParsesArticleText() {
        withSession("article.html", "https://example.com/article") { session ->
            val meta = session.call(buildJsonObject { put("op", "meta") })
            val readability = meta["readability"]!!.jsonObject
            assertTrue("readability.js 必须在宿主里成功加载", readability["present"]!!.jsonPrimitive.boolean)
            assertTrue(
                "Readability 必须能从 fixture 解析出正文",
                readability["textLength"]!!.jsonPrimitive.int > 200,
            )
            assertEquals(emptyList<String>(), meta["consoleErrors"]!!.jsonArray.map { it.toString() })
        }
    }

    @Test
    fun wechatTemplate_revealsHiddenContentAndStripsNoise() {
        withSession("wechat-article.html", "https://mp.weixin.qq.com/s/demo") { session ->
            val l1 = session.extract()
            assertEquals("template:微信公众号", l1.string("extractor"))
            assertEquals("把通勤时间变成阅读时间", l1.string("title"))
            val markdown = l1.string("markdown")
            assertTrue("初始隐藏的正文段落必须保留", markdown.contains("被隐藏的正文段落"))
            assertTrue(markdown.contains("https://example.com/wechat-1.jpg"))
            assertTrue(markdown.contains("https://example.com/wechat-2.jpg"))
            assertFalse("二维码与关注引导必须被剔除", markdown.contains("扫码关注公众号"))
            assertFalse("底部工具栏必须被剔除", markdown.contains("阅读 1024"))
        }
    }

    @Test
    fun l3Expand_clicksSafeExpandControlAndReextractsFullArticle() {
        withSession("wechat-expand.html", "https://mp.weixin.qq.com/s/fold") { session ->
            assertTrue("导语过短时必须要求展开", session.extract().boolean("needsExpand"))

            val picked = L3Rules.pick(session.collectCandidates(), emptySet())
            assertTrue("必须有可安全点击的展开控件", picked != null)
            assertEquals("展开全文", picked!!.text)
            assertTrue("展开控件必须判定为可见", picked.visible)

            assertEquals("true", session.click(picked.index))

            val after = session.extract()
            assertFalse("展开后正文足够长，needsExpand 必须转 false", after.boolean("needsExpand"))
            val markdown = after.string("markdown")
            assertTrue(markdown.contains("MARKER 展开后的正文第一段"))
            assertFalse("隐藏按钮不能被点击", markdown.contains("HIDDEN_MARKER"))
            assertFalse("广告链接不能被点击", markdown.contains("AD_MARKER"))
        }
    }

    @Test
    fun l3Expand_loopStopsWhenAlreadyClicked() {
        withSession("wechat-expand-loop.html", "https://mp.weixin.qq.com/s/loop") { session ->
            val clickedKeys = mutableSetOf<String>()
            var rounds = 0
            var extraction = session.extract()
            while (rounds < 2) {
                val picked = L3Rules.pick(session.collectCandidates(), clickedKeys) ?: break
                assertEquals("true", session.click(picked.index))
                clickedKeys += picked.key
                rounds++
                extraction = session.extract()
            }

            assertEquals("L3 最多只允许点击 2 轮", 2, rounds)
            val markdown = extraction.string("markdown")
            assertTrue(markdown.contains("LAYER2 第二轮展开的正文内容"))
            assertFalse("第三层按钮绝不能被点击", markdown.contains("LAYER3"))
        }
    }

    @Test
    fun bundledTemplates_allSelectorsAreValid() {
        withSession("article.html", "https://example.com/article") { session ->
            val invalid = session.call(buildJsonObject { put("op", "validate-templates") })["invalid"]!!
                .jsonArray.map { it.jsonObject }

            assertTrue("模板含非法选择器：$invalid", invalid.isEmpty())

            val control = session.call(
                buildJsonObject {
                    put("op", "validate-templates")
                    putJsonArray("templates") {
                        add(
                            buildJsonObject {
                                put("name", "阳性对照")
                                putJsonArray("removeSelectors") {
                                    add(kotlinx.serialization.json.JsonPrimitive("div[["))
                                }
                            },
                        )
                    }
                },
            )["invalid"]!!.jsonArray.map { it.jsonObject }

            assertTrue("校验器必须能发现非法选择器：$control", control.isNotEmpty())
        }
    }

    @Test
    fun invalidTemplateSelector_isReportedWhileExtractionContinues() {
        withSession("article.html", "https://example.com/article") { session ->
            val result = session.extract(
                buildJsonObject {
                    putJsonArray("templates") {
                        add(
                            buildJsonObject {
                                put("name", "通用网页")
                                put("hostPattern", ".*")
                                putJsonArray("removeSelectors") {
                                    add(kotlinx.serialization.json.JsonPrimitive("div[["))
                                }
                            },
                        )
                    }
                },
            )

            val diagnostics = result["diagnostics"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("非法选择器必须进入提取诊断：$diagnostics", diagnostics.contains("invalid selector: div[["))
            assertTrue(result.string("markdown").contains("把小屏幕变成耳朵"))
        }
    }

    private fun withSession(name: String, url: String, block: (ExtractSession) -> Unit) {
        startSession(name, url).use(block)
    }

    private fun startSession(fixture: String, url: String): ExtractSession {
        val root = repoRoot()
        val stderr = File.createTempFile("js-extract-harness", ".log")
        val process = try {
            ProcessBuilder(
                "node",
                "scripts/js-extract-harness.mjs",
                "--fixture",
                fixture,
                "--url",
                url,
            ).directory(root).redirectError(stderr).start()
        } catch (error: IOException) {
            stderr.delete()
            throw AssertionError("需要 Node.js 才能运行网页提取回归：${error.message}", error)
        }
        return ExtractSession(process, stderr)
    }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "scripts/js-extract-harness.mjs").isFile }
            ?: throw AssertionError("找不到 scripts/js-extract-harness.mjs，无法定位仓库根目录")

    private fun ExtractSession.extract(): JsonObject {
        val response = call(buildJsonObject { put("op", "extract") })
        return response["result"]!!.jsonObject
    }

    private fun ExtractSession.extract(options: JsonObject): JsonObject {
        val response = call(
            buildJsonObject {
                put("op", "extract")
                put("options", options)
            },
        )
        return response["result"]!!.jsonObject
    }

    private fun ExtractSession.collectCandidates(): List<L3Candidate> {
        val response = call(
            buildJsonObject {
                put("op", "collect")
                putJsonArray("phrases") { L3Rules.PHRASES.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            },
        )
        return response["candidates"]!!.jsonArray.mapIndexed { index, element ->
            val obj = element.jsonObject
            L3Candidate(
                index = index,
                tag = obj.string("tag"),
                text = obj.string("text"),
                visible = obj.boolean("visible"),
                inForm = obj.boolean("inForm"),
                href = obj["href"]?.jsonPrimitive?.contentOrNull,
                type = obj["type"]?.jsonPrimitive?.contentOrNull,
                submit = obj.boolean("submit"),
                leaf = obj.boolean("leaf"),
                rectWidth = obj.double("rectWidth"),
                rectHeight = obj.double("rectHeight"),
            )
        }
    }

    private fun ExtractSession.click(index: Int): String =
        call(buildJsonObject {
            put("op", "click")
            put("index", index)
        })["clicked"]!!.jsonPrimitive.content

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.boolean(key: String): Boolean = this[key]!!.jsonPrimitive.boolean
    private fun JsonObject.double(key: String): Double = this[key]!!.jsonPrimitive.content.toDouble()

    private class ExtractSession(
        private val process: Process,
        private val stderrFile: File,
    ) : AutoCloseable {
        private val writer: BufferedWriter = process.outputStream.bufferedWriter()
        private val reader: BufferedReader = process.inputStream.bufferedReader()
        private val executor = Executors.newSingleThreadExecutor()

        fun call(command: JsonObject): JsonObject {
            writer.write(command.toString())
            writer.newLine()
            writer.flush()

            val pending = executor.submit<String?> { reader.readLine() }
            val line = try {
                pending.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: TimeoutException) {
                pending.cancel(true)
                throw AssertionError("Node 宿主 ${RESPONSE_TIMEOUT_SECONDS}s 未响应，命令：$command", error)
            }
            val response = line ?: throw AssertionError(
                "Node 宿主提前退出（命令：$command）：${stderrText()}",
            )
            val parsed = Json.parseToJsonElement(response).jsonObject
            assertTrue("Node 宿主执行失败：$response", parsed["ok"]!!.jsonPrimitive.boolean)
            return parsed
        }

        private fun stderrText(): String =
            runCatching { stderrFile.readText().trim() }.getOrDefault("").ifBlank { "（无 stderr 输出）" }

        override fun close() {
            runCatching {
                writer.write("""{"op":"close"}""")
                writer.newLine()
                writer.flush()
            }
            executor.shutdownNow()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            runCatching { stderrFile.delete() }
        }

        private companion object {
            const val RESPONSE_TIMEOUT_SECONDS = 30L
        }
    }
}
