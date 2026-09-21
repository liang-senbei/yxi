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
 * GeminiRouteConfig 的针对性小测：`.env` 手术保留、settings.json 字段保留/拒绝重建、脱敏 fingerprint、
 * 以及远端 python3 提交脚本在**本地临时目录**真跑（保存/冲突/坏内容/符号链接/权限/备份）。
 * 全程不碰 SSH、不写用户真实配置、不发模型请求；无 python3 的机器自动跳过脚本段。
 */
class GeminiRouteConfigTest {

    // ---------- patchEnv：按行手术，注释/未知键/顺序全保留 ----------

    @Test
    fun `env手术保留注释未知键与顺序`() {
        val src = """
            # 中转商备注
            GOOGLE_GEMINI_BASE_URL=https://old.example.com
            UNKNOWN_KEEP=1
            GEMINI_API_KEY=old-key

            GEMINI_MODEL=gemini-old
        """.trimIndent()
        val out = GeminiRouteConfig.patchEnv(
            src,
            GeminiRouteConfig.Patch(baseUrl = "https://new.example.com/v1", apiKey = "k2", model = "m2"),
        )
        assertTrue(out.contains("# 中转商备注"))
        assertTrue(out.contains("UNKNOWN_KEEP=1"))
        assertFalse(out.contains("old-key"))
        val kv = GeminiRouteConfig.parseEnv(out)
        assertEquals("https://new.example.com/v1", kv[GeminiRouteConfig.KEY_BASE])
        assertEquals("k2", kv[GeminiRouteConfig.KEY_API])
        assertEquals("m2", kv[GeminiRouteConfig.KEY_MODEL])
        assertTrue(out.indexOf("# 中转商备注") < out.indexOf("GOOGLE_GEMINI_BASE_URL="), "顺序不动")
        assertTrue(out.indexOf("UNKNOWN_KEEP=") < out.indexOf("GEMINI_API_KEY=k2"), "未命中键不挪位")
    }

    @Test
    fun `env手术保留export前缀`() {
        val out = GeminiRouteConfig.patchEnv("export GEMINI_API_KEY=old\n", GeminiRouteConfig.Patch(apiKey = "new"))
        assertEquals("export GEMINI_API_KEY=new\n", out)
    }

    @Test
    fun `env缺键补尾`() {
        val out = GeminiRouteConfig.patchEnv("FOO=bar\n", GeminiRouteConfig.Patch(apiKey = "k", baseUrl = "https://a.b"))
        assertEquals("FOO=bar\nGEMINI_API_KEY=k\nGOOGLE_GEMINI_BASE_URL=https://a.b\n", out)
    }

    @Test
    fun `env空串显式清空`() {
        val out = GeminiRouteConfig.patchEnv("GEMINI_API_KEY=old\n", GeminiRouteConfig.Patch(apiKey = ""))
        assertEquals("GEMINI_API_KEY=\n", out)
    }

    @Test
    fun `env全null原样返回`() {
        val src = "A=1\n# c\n"
        assertEquals(src, GeminiRouteConfig.patchEnv(src, GeminiRouteConfig.Patch()))
    }

    @Test
    fun `env非法值拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            GeminiRouteConfig.patchEnv(null, GeminiRouteConfig.Patch(apiKey = "a\nb"))
        }
        assertFailsWith<IllegalArgumentException> {
            GeminiRouteConfig.patchEnv(null, GeminiRouteConfig.Patch(model = "x\"y"))
        }
    }

    @Test
    fun `baseUrl非https或带用户信息拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            GeminiRouteConfig.patchEnv(null, GeminiRouteConfig.Patch(baseUrl = "ftp://a.b"))
        }
        assertFailsWith<IllegalArgumentException> {
            GeminiRouteConfig.patchEnv(null, GeminiRouteConfig.Patch(baseUrl = "https://u:p@a.b"))
        }
        // 空串 = 显式清空，放行
        assertEquals("GOOGLE_GEMINI_BASE_URL=\n", GeminiRouteConfig.patchEnv(null, GeminiRouteConfig.Patch(baseUrl = "")))
    }

    // ---------- patchSettings：只动 selectedType，其余保留 ----------

    @Test
    fun `settings缺失时新建最小对象`() {
        val o = JSONObject(GeminiRouteConfig.patchSettings(null))
        assertEquals(
            GeminiRouteConfig.SELECTED_TYPE,
            o.getJSONObject("security").getJSONObject("auth").getString("selectedType"),
        )
    }

    @Test
    fun `settings未知字段与手写model保留`() {
        val src = """{"model":"gemini-2.5-pro","theme":"dark","security":{"auth":{"selectedType":"oauth-personal"},"other":1}}"""
        val o = JSONObject(GeminiRouteConfig.patchSettings(src))
        assertEquals(GeminiRouteConfig.SELECTED_TYPE, o.getJSONObject("security").getJSONObject("auth").getString("selectedType"))
        assertEquals("gemini-2.5-pro", o.optString("model"), "官方确认前不写也不清 model")
        assertEquals("dark", o.optString("theme"))
        assertEquals(1, o.getJSONObject("security").optInt("other"))
    }

    @Test
    fun `settings注释或非对象报错不重建`() {
        assertFailsWith<IllegalArgumentException> { GeminiRouteConfig.patchSettings("{/*c*/}") }
        assertFailsWith<IllegalArgumentException> { GeminiRouteConfig.patchSettings("""{"security":[1]}""") }
        assertFailsWith<IllegalArgumentException> { GeminiRouteConfig.patchSettings("""{"security":{"auth":3}}""") }
    }

    // ---------- 脱敏 ----------

    @Test
    fun `fingerprint稳定且不泄原文`() {
        val secret = "sk-super-secret-value"
        val f = GeminiRouteConfig.fingerprint(secret)!!
        assertTrue(f.startsWith("sha256:"))
        assertFalse(f.contains("secret"))
        assertFalse(f.contains("value"))
        assertEquals(f, GeminiRouteConfig.fingerprint(secret)!!, "稳定可对账")
        assertNull(GeminiRouteConfig.fingerprint(null))
        assertNull(GeminiRouteConfig.fingerprint(""))
    }

    // ---------- parseEnv / status 数据 ----------

    @Test
    fun `parseEnv后写覆盖且剥引号`() {
        val kv = GeminiRouteConfig.parseEnv("A=1\n# c\nA=\"2\"\nB='x y'\nNOTKEY")
        assertEquals("2", kv["A"])
        assertEquals("x y", kv["B"])
        assertEquals(2, kv.size)
        assertEquals(emptyMap(), GeminiRouteConfig.parseEnv(null))
    }

    // ---------- 远端 python3 脚本（本地临时目录真跑） ----------

    private fun pythonAvailable(): Boolean = runCatching {
        // 脚本用 fcntl 锁（POSIX）；Windows CPython 没有，这类机器直接跳过脚本段
        ProcessBuilder("python3", "-c", "import fcntl, tempfile, hashlib, json").start().waitFor() == 0
    }.getOrDefault(false)

    /** 直接跑 GeminiRouteConfig.SCRIPT：返回其 JSON 应答。 */
    private fun runScript(p: Path, content: String?, expected: String, jsonMode: Boolean): JSONObject {
        val tmp = p.resolveSibling(".upload-${System.nanoTime()}")
        if (content != null) Files.write(tmp, content.toByteArray())
        val backups = p.parent.resolve("yxi-backups")
        Files.createDirectories(backups)
        val proc = ProcessBuilder(
            "python3", "-c", GeminiRouteConfig.SCRIPT,
            p.toString(), tmp.toString(), expected,
            app.yxi.agent.RemoteAtomicJson.hash(content?.toByteArray() ?: ByteArray(0)),
            backups.toString(), if (jsonMode) "json" else "env",
        ).start()
        val out = String(proc.inputStream.readBytes(), Charsets.UTF_8) + String(proc.errorStream.readBytes(), Charsets.UTF_8)
        proc.waitFor()
        return JSONObject(out.trim())
    }

    private fun assertMode0600(p: Path) {
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(p))
    }

    @Test
    fun `脚本保存带备份与0600权限`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("gemini-route")
        val p = dir.resolve(".env")
        Files.write(p, "A=1\n".toByteArray())
        val st = runScript(p, "A=2\n", app.yxi.agent.RemoteAtomicJson.hash("A=1\n".toByteArray()), jsonMode = false)
        assertEquals("saved", st.optString("status"), st.toString())
        assertEquals("A=2\n", Files.readString(p))
        assertMode0600(p)
        val backups = dir.resolve("yxi-backups")
        val saved = Files.list(backups).use { it.toList() }
        assertEquals(1, saved.size)
        assertEquals("A=1\n", Files.readString(saved[0]), "备份=原文")
        assertMode0600(saved[0])
    }

    @Test
    fun `脚本hash冲突不动文件`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("gemini-route")
        val p = dir.resolve(".env")
        Files.write(p, "A=1\n".toByteArray())
        val st = runScript(p, "A=2\n", "deadbeef", jsonMode = false)
        assertEquals("conflict", st.optString("status"), st.toString())
        assertEquals("A=1\n", Files.readString(p))
    }

    @Test
    fun `脚本json模式拒绝坏内容`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("gemini-route")
        val p = dir.resolve("settings.json")
        Files.write(p, "{}\n".toByteArray())
        val st = runScript(p, "not json\n", app.yxi.agent.RemoteAtomicJson.hash("{}\n".toByteArray()), jsonMode = true)
        assertEquals("error", st.optString("status"), st.toString())
        assertEquals("{}\n", Files.readString(p), "坏内容不落盘")
    }

    @Test
    fun `脚本拒绝符号链接`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("gemini-route")
        val target = dir.resolve("real.env")
        Files.write(target, "A=1\n".toByteArray())
        val link = dir.resolve("link.env")
        runCatching { Files.createSymbolicLink(link, target) }.getOrElse { return }
        val st = runScript(link, "A=2\n", app.yxi.agent.RemoteAtomicJson.hash("A=1\n".toByteArray()), jsonMode = false)
        assertEquals("error", st.optString("status"), st.toString())
        assertEquals("A=1\n", Files.readString(target), "符号链接目标不被改写")
    }

    @Test
    fun `脚本missing路径新建无备份`() {
        if (!pythonAvailable()) return
        val dir = Files.createTempDirectory("gemini-route")
        val p = dir.resolve("sub").resolve(".env")
        Files.createDirectories(p.parent)
        val st = runScript(p, "A=2\n", "missing", jsonMode = false)
        assertEquals("saved", st.optString("status"), st.toString())
        assertEquals("A=2\n", Files.readString(p))
        assertMode0600(p)
        val backups = dir.resolve("yxi-backups")
        assertTrue(!Files.exists(backups) || Files.list(backups).use { it.count() } == 0L, "新建不产生备份")
    }
}
