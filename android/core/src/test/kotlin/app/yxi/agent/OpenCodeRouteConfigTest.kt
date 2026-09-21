package app.yxi.agent

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OpenCodeRouteConfig 的针对性小测：JSONC 手术（原位替换/风格化插入/注释与未知字段保留/嵌套同名键不碰）、
 * 读取抽取与脱敏 fingerprint、model 白名单、以及远端 python3 提交脚本在**本地临时目录**真跑
 * （JSONC 校验/保存/冲突/坏内容/符号链接/权限/备份）。
 * 全程不碰 SSH、不写用户真实配置、不发模型请求；无 python3 的机器自动跳过脚本段。
 */
class OpenCodeRouteConfigTest {

    /** 一份贴近真实形状的用户级配置：注释、$schema、尾逗号、provider、未知未来键全有。 */
    private val richDoc = """
        {
          // 中转商备注：别删
          "${'$'}schema": "https://opencode.ai/config.json",
          "theme": "dark",
          "autoupdate": true,
          "model": "old-provider/old-model", /* 旧选择 */
          "provider": {
            "my-relay": {
              "npm": "@ai-sdk/openai-compatible",
              "name": "中转",
              "options": { "baseURL": "https://relay.example.com/v1", "apiKey": "sk-keep", },
              "models": { "m-a": {}, "m-b": { "name": "B" }, },
            },
          },
          "unknown_future_key": { "deep": [1, 2, 3] }
        }
    """.trimIndent()

    // ---------- patchModel：原位替换，注释/未知字段/顺序/尾逗号全保留 ----------

    @Test
    fun `model原位替换除值外逐字节不变`() {
        val out = OpenCodeRouteConfig.patchModel(richDoc, "my-relay/m-b")
        // 手术的最强证明：整份文件里唯一的差异就是那个值本身
        assertEquals(
            richDoc.replace("\"old-provider/old-model\"", "\"my-relay/m-b\""),
            out,
        )
        assertEquals("my-relay/m-b", OpenCodeRouteConfig.parseConfig(out).model, "替换后仍可解析")
    }

    @Test
    fun `model缺键按文件风格插入`() {
        assertEquals(
            "{\n  \"model\": \"a/b\",\n  \"theme\": \"dark\"\n}",
            OpenCodeRouteConfig.patchModel("{\n  \"theme\": \"dark\"\n}", "a/b"),
            "多行风格：抄既有缩进、补逗号",
        )
        assertEquals(
            "{\"model\": \"a/b\", \"theme\": \"dark\" }",
            OpenCodeRouteConfig.patchModel("{ \"theme\": \"dark\" }", "a/b"),
            "单行风格：内联插入（插在 { 后，原有空格保留）",
        )
        assertEquals("{\"model\": \"a/b\"}", OpenCodeRouteConfig.patchModel("{}", "a/b"), "空对象内联")
        assertEquals("{\n  \"model\": \"a/b\"\n}", OpenCodeRouteConfig.patchModel("{\n}", "a/b"), "空对象多行")
        // 插入后注释仍贴在原键上方
        val out = OpenCodeRouteConfig.patchModel("{\n  // 主题\n  \"theme\": \"dark\"\n}", "a/b")
        assertTrue(out.contains("{\n  \"model\": \"a/b\",\n  // 主题\n"), out)
    }

    @Test
    fun `model缺失时新建最小配置`() {
        assertEquals("{\n  \"model\": \"a/b\"\n}\n", OpenCodeRouteConfig.patchModel(null, "a/b"))
        assertEquals("{\n  \"model\": \"a/b\"\n}\n", OpenCodeRouteConfig.patchModel("   \n", "a/b"))
    }

    @Test
    fun `尾逗号配置可读可改且原样保留`() {
        val p = OpenCodeRouteConfig.parseConfig(richDoc)
        assertEquals("old-provider/old-model", p.model, "尾逗号文档读取路径要能解析")
        val out = OpenCodeRouteConfig.patchModel(richDoc, "my-relay/m-a")
        assertTrue(out.contains("\"sk-keep\", }"), "原有尾逗号原样保留（不新增也不清除）")
        assertEquals("my-relay/m-a", OpenCodeRouteConfig.parseConfig(out).model)
    }

    @Test
    fun `嵌套同名model不碰且顶层缺失时插入`() {
        val src = """
            {
              "nested": { "model": "decoy/inner" },
              "provider": { "p": { "models": { "m": { "model": "meta/inner" } } } }
            }
        """.trimIndent()
        val out = OpenCodeRouteConfig.patchModel(src, "a/b")
        assertTrue(out.contains("\"decoy/inner\""), "嵌套同名键不动")
        assertTrue(out.contains("\"meta/inner\""), "深层同名键不动")
        assertTrue(out.contains("\"model\": \"a/b\","), out)
        val p = OpenCodeRouteConfig.parseConfig(out)
        assertEquals("a/b", p.model)
        assertEquals(listOf("nested"), p.otherTopLevelKeys, "provider 已单列，不算 other")
    }

    @Test
    fun `坏配置拒绝盲改`() {
        // 只断言拒绝（两道闸都可能先响：结构扫描 / org.json 严格解析），报错措辞各归各测
        assertFailsWith<IllegalArgumentException> { OpenCodeRouteConfig.patchModel("""{ "model": "old/x", "theme": }""", "a/b") }
        assertFailsWith<IllegalArgumentException>("块注释未闭合也要拒") {
            OpenCodeRouteConfig.patchModel("{\n  /* 未闭合\n  \"a\": 1\n}", "a/b")
        }
        // parseConfig 的措辞单独钉死
        val e = assertFailsWith<IllegalArgumentException> { OpenCodeRouteConfig.parseConfig("""{ "a": }""") }
        assertTrue(e.message!!.contains("JSONC"))
    }

    @Test
    fun `重复顶层model拒绝`() {
        val e = assertFailsWith<IllegalArgumentException> { OpenCodeRouteConfig.patchModel("""{"model": "a/b", "model": "c/d"}""", "x/y") }
        assertTrue(e.message!!.contains("2 次"))
    }

    @Test
    fun `根非对象与空文本拒绝`() {
        for (bad in listOf("[1, 2]", "{oops", "null")) {
            assertFailsWith<IllegalArgumentException>("应拒绝：$bad") { OpenCodeRouteConfig.patchModel(bad, "a/b") }
        }
    }

    // ---------- validateModel：官方首个 / 切分口径，modelID 可含 / ----------

    @Test
    fun `model白名单校验`() {
        OpenCodeRouteConfig.validateModel("anthropic/claude-sonnet-4-5")
        OpenCodeRouteConfig.validateModel("openrouter/anthropic/claude-3.5-sonnet")
        OpenCodeRouteConfig.validateModel("my-relay/m-1")
        for (bad in listOf("", "onlyone", "a/b c", "a\nb/c", "a\"b/c", "a\\b/c", "a/b\n", "/leading", "a/")) {
            assertFailsWith<IllegalArgumentException>("应拒绝：$bad") { OpenCodeRouteConfig.validateModel(bad) }
        }
    }

    // ---------- parseConfig：抽取 provider/model，脱敏 fingerprint ----------

    @Test
    fun `parseConfig抽取provider且key只出指纹`() {
        val p = OpenCodeRouteConfig.parseConfig(richDoc)
        assertEquals("old-provider/old-model", p.model)
        val relay = p.providers.single()
        assertEquals("my-relay", relay.id)
        assertEquals("中转", relay.name)
        assertEquals("@ai-sdk/openai-compatible", relay.npm)
        assertEquals("https://relay.example.com/v1", relay.baseURL)
        val f = relay.apiKeyFingerprint!!
        assertTrue(f.startsWith("sha256:"))
        assertEquals(f, OpenCodeRouteConfig.fingerprint("sk-keep"), "与 fingerprint 同口径")
        assertFalse(f.contains("sk-keep"))
        assertEquals(listOf("m-a", "m-b"), relay.modelIds, "models 键字典序")
        assertEquals(listOf("\$schema", "autoupdate", "theme", "unknown_future_key"), p.otherTopLevelKeys)
        val dumped = p.toString() + relay.toString()
        assertFalse(dumped.contains("sk-keep"), "Parsed 全量字符串不含密钥原文")
    }

    @Test
    fun `parseConfig类型不对报错`() {
        assertFailsWith<IllegalArgumentException> { OpenCodeRouteConfig.parseConfig("""{"model": 3}""") }
        assertFailsWith<IllegalArgumentException> { OpenCodeRouteConfig.parseConfig("""{"provider": [1]}""") }
        // provider 条目不是对象：不炸，如实给空字段
        val p = OpenCodeRouteConfig.parseConfig("""{"provider": {"bad": 3}}""")
        assertEquals("bad", p.providers.single().id)
        assertNull(p.providers.single().apiKeyFingerprint)
    }

    @Test
    fun `stripJsonc不碰字符串内的注释符`() {
        val out = OpenCodeRouteConfig.stripJsonc("""{"a": "x//y", "b": "p/*q*/r",} // 尾注释""")
        assertTrue(out.contains("\"x//y\""))
        assertTrue(out.contains("\"p/*q*/r\""))
        assertFalse(out.contains("尾注释"))
        assertEquals("""{"a": "x//y", "b": "p/*q*/r"} """, out, "尾逗号已丢弃、注释已剥（} 后原空格保留）")
        JSONObject(out)
    }

    @Test
    fun `尾逗号跨注释也丢弃`() {
        val stripped = OpenCodeRouteConfig.stripJsonc("""{"a": 1, /* c */ }""")
        assertEquals("""{"a": 1  }""", stripped)
        JSONObject(stripped)
    }

    // ---------- 远端 python3 脚本（本地临时目录真跑） ----------

    private fun pythonAvailable(): Boolean = runCatching {
        // 脚本用 fcntl 锁（POSIX）；Windows CPython 没有，这类机器直接跳过脚本段
        ProcessBuilder("python3", "-c", "import fcntl, tempfile, hashlib, json").start().waitFor() == 0
    }.getOrDefault(false)

    /** 直接跑 OpenCodeRouteConfig.SCRIPT：返回其 JSON 应答。 */
    private fun runScript(p: Path, content: String?, expected: String): JSONObject {
        val tmp = p.resolveSibling(".upload-${System.nanoTime()}")
        if (content != null) Files.write(tmp, content.toByteArray())
        val backups = p.parent.resolve("yxi-backups")
        Files.createDirectories(backups)
        val proc = ProcessBuilder(
            "python3", "-c", OpenCodeRouteConfig.SCRIPT,
            p.toString(), tmp.toString(), expected,
            app.yxi.agent.RemoteAtomicJson.hash(content?.toByteArray() ?: ByteArray(0)),
            backups.toString(),
        ).start()
        val out = String(proc.inputStream.readBytes(), Charsets.UTF_8) + String(proc.errorStream.readBytes(), Charsets.UTF_8)
        proc.waitFor()
        return JSONObject(out.trim())
    }

    private fun assertMode0600(p: Path) {
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(p))
    }

    @Test
    fun `脚本保存带备份与0600权限且旧JSONC可过校验`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("opencode-route")
        val p = dir.resolve("opencode.jsonc")
        val old = "{\n  // 用户手写注释\n  \"theme\": \"dark\",\n}\n"
        Files.write(p, old.toByteArray())
        val new = "{\n  // 用户手写注释\n  \"theme\": \"dark\",\n  \"model\": \"a/b\",\n}\n"
        val st = runScript(p, new, app.yxi.agent.RemoteAtomicJson.hash(old.toByteArray()))
        assertEquals("saved", st.optString("status"), st.toString())
        assertEquals(new, Files.readString(p))
        assertMode0600(p)
        val backups = dir.resolve("yxi-backups")
        val saved = Files.list(backups).use { it.toList() }
        assertEquals(1, saved.size)
        assertEquals(old, Files.readString(saved[0]), "备份=原文")
        assertTrue(saved[0].fileName.toString().startsWith("opencode-route-"))
        assertMode0600(saved[0])
    }

    @Test
    fun `脚本hash冲突不动文件`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("opencode-route")
        val p = dir.resolve("opencode.json")
        Files.write(p, "{}\n".toByteArray())
        val st = runScript(p, "{\"model\": \"a/b\"}\n", "deadbeef")
        assertEquals("conflict", st.optString("status"), st.toString())
        assertEquals("{}\n", Files.readString(p))
    }

    @Test
    fun `脚本jsonc校验拒绝坏内容`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("opencode-route")
        val p = dir.resolve("opencode.jsonc")
        Files.write(p, "{}\n".toByteArray())
        for (bad in listOf("not json", "{\"a\": 1 /* 未闭合")) {
            val st = runScript(p, bad, app.yxi.agent.RemoteAtomicJson.hash("{}\n".toByteArray()))
            assertEquals("error", st.optString("status"), "$bad → $st")
            assertEquals("{}\n", Files.readString(p), "坏内容不落盘")
        }
    }

    @Test
    fun `脚本拒绝符号链接`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("opencode-route")
        val target = dir.resolve("real.jsonc")
        Files.write(target, "{}\n".toByteArray())
        val link = dir.resolve("opencode.jsonc")
        runCatching { Files.createSymbolicLink(link, target) }.getOrElse { return }
        val st = runScript(link, "{\"model\": \"a/b\"}\n", app.yxi.agent.RemoteAtomicJson.hash("{}\n".toByteArray()))
        assertEquals("error", st.optString("status"), st.toString())
        assertEquals("{}\n", Files.readString(target), "符号链接目标不被改写")
    }

    @Test
    fun `脚本missing路径新建无备份`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("opencode-route")
        val p = dir.resolve("sub").resolve("opencode.json")
        Files.createDirectories(p.parent)
        val st = runScript(p, "{\"model\": \"a/b\"}\n", "missing")
        assertEquals("saved", st.optString("status"), st.toString())
        assertEquals("{\"model\": \"a/b\"}\n", Files.readString(p))
        assertMode0600(p)
        val backups = dir.resolve("yxi-backups")
        assertTrue(!Files.exists(backups) || Files.list(backups).use { it.count() } == 0L, "新建不产生备份")
    }
}
