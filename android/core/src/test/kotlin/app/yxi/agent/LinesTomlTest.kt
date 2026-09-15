package app.yxi.agent

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第 76-79 轮「线路」字符串拼接层的针对性单测（进度文档自述这些轮次未运行过验证）。
 * 覆盖：config.toml 两段标记的挖除/还原、被挤走的顶层键的影子注释、TOML 字符串转义与反解、
 * Codex 当前态四元组比对、settings.json 白名单两道门。
 * 纯 JVM，不碰 SSH——真实传输路径由 fixture 的 RemoteRoutesTest 覆盖（另测）。
 */
class LinesTomlTest {

    // ---------- stripBlocks：只认独占一行的标记；认不出来当没有；不成对宁可拒绝 ----------

    @Test
    fun `无标记时原样返回（不算错，当没有）`() {
        val toml = "model = \"gpt-5\"\n\n[mcp_servers.foo]\ncommand = \"uvx\"\n"
        // 实现两端 trim 掉 \n、空格、制表符（applyCodex 写盘前自己补换行），中间内容一字不动
        assertEquals(toml.trim('\n', ' ', '\t'), Lines.stripBlocks(toml))
    }

    @Test
    fun `两段标记连同内容一起挖掉，用户内容一字不动`() {
        val toml = listOf(
            "model_provider = \"openai\"",
            "# >>> yxi line >>>",
            "# 这两段是 Yxi「线路」自动写的，手改会被覆盖。",
            "model_provider = \"yxi\"",
            "# <<< yxi line <<<",
            "",
            "[mcp_servers.foo]",
            "command = \"uvx\"",
            "",
            "# >>> yxi provider >>>",
            "[model_providers.yxi]",
            "base_url = \"https://relay.example/v1\"",
            "# <<< yxi provider <<<",
        ).joinToString("\n")
        val out = Lines.stripBlocks(toml)!!
        assertFalse(out.contains("yxi"))
        assertTrue(out.contains("model_provider = \"openai\""))
        assertTrue(out.contains("[mcp_servers.foo]"))
    }

    @Test
    fun `影子注释在挖除时还原成原行`() {
        val toml = "# yxi original root: model = \"user-model\"\nkey = 1\n"
        assertEquals("model = \"user-model\"\nkey = 1", Lines.stripBlocks(toml))
    }

    @Test
    fun `开了没关的标记返回 null（宁可拒绝不能猜）`() {
        val toml = "a = 1\n# >>> yxi line >>>\nmodel_provider = \"yxi\"\n"
        assertNull(Lines.stripBlocks(toml))
    }

    @Test
    fun `段内再遇任何标记返回 null（不成对不猜）`() {
        val toml = "# >>> yxi line >>>\n# >>> yxi line >>>\n# <<< yxi line <<<\n"
        assertNull(Lines.stripBlocks(toml))
    }

    @Test
    fun `单独出现的闭合标记返回 null`() {
        assertNull(Lines.stripBlocks("# <<< yxi provider <<<\n"))
    }

    @Test
    fun `值里含标记字样的行不算标记（所以必须整行认，不能 indexOf）`() {
        val toml = "note = \"x # >>> yxi line >>>\"\nkeep = 1\n"
        assertEquals(toml.trim('\n', ' ', '\t'), Lines.stripBlocks(toml))
    }

    @Test
    fun `shadow 到 strip 是恒等还原（对没有标记的用户配置）`() {
        val body = "model = \"user-model\"\n\n[mcp_servers.foo]\ncommand = \"uvx\"\n"
        val shadowed = Lines.shadowCodexRoot(body, setOf("model_provider", "model"))
        val restored = Lines.stripBlocks(shadowed)!!
        assertEquals(body.trim('\n', ' ', '\t'), restored)
    }

    // ---------- shadowCodexRoot：只动第一个 [表] 之前的顶层键 ----------

    @Test
    fun `第一个表之后的同名键不影子（表内 name 不是顶层）`() {
        val body = "model = \"user-model\"\n\n[model_providers.custom]\nname = \"x\"\n"
        val out = Lines.shadowCodexRoot(body, setOf("model", "model_provider"))
        assertContains(out, "# yxi original root: model = \"user-model\"")
        assertFalse(out.contains("# yxi original root: name"))
    }

    @Test
    fun `不带 model 的线路不动用户自己的 model 键`() {
        val body = "model = \"user-own\"\n"
        val out = Lines.shadowCodexRoot(body, setOf("model_provider"))
        assertEquals(body, out)
    }

    @Test
    fun `带引号的键名也认得出`() {
        val out = Lines.shadowCodexRoot("\"model\" = \"a\"\n'model_provider' = \"b\"\n", setOf("model", "model_provider"))
        assertContains(out, "# yxi original root: \"model\" = \"a\"")
        assertContains(out, "# yxi original root: 'model_provider' = \"b\"")
    }

    @Test
    fun `顶层多行字符串直接拒绝（拼回去必坏，宁可说不）`() {
        val body = "desc = \"\"\"\nmodel = \"x\"\n\"\"\"\n"
        val e = assertFailsWith<IllegalArgumentException> { Lines.shadowCodexRoot(body, setOf("model")) }
        assertContains(e.message!!, "多行 TOML 字符串")
    }

    @Test
    fun `数组元素长得像赋值也不影子（带空格的元素不匹配键名正则）`() {
        // 曾疑心这里会把数组内容注释掉（安全自查），复推正则证实不会：引号后跟的是空格不是引号，键名组匹配不上
        val body = "tags = [\n  \"model = fake\",\n]\n"
        assertEquals(body, Lines.shadowCodexRoot(body, setOf("model")))
    }

    @Test
    fun `注释行不影子也不打断顶层区`() {
        val body = "# model = \"fake\"\nmodel = \"real\"\n"
        val out = Lines.shadowCodexRoot(body, setOf("model"))
        assertFalse(out.contains("# yxi original root: # model"))
        assertContains(out, "# yxi original root: model = \"real\"")
    }

    // ---------- tomlEscape / tomlUnescape：值里一个引号就能注入任意表键，必须转义闭环 ----------

    @Test
    fun `转义后反解等于原文（含引号反斜杠换行制表符）`() {
        val nasty = "he said \"hi\" \\ path\n\r\tend"
        assertEquals(nasty, Lines.tomlUnescape(Lines.tomlEscape(nasty)))
    }

    @Test
    fun `控制字符与 DEL 转成 uXXXX（大写十六进制、四位补零）`() {
        assertEquals("\\u0001", Lines.tomlEscape("\u0001"))
        assertEquals("\\u007F", Lines.tomlEscape("\u007F"))
        assertEquals("\u0001", Lines.tomlUnescape("\\u0001"))
        assertEquals("\n", Lines.tomlUnescape("\\u000a"), "反解也要认小写十六进制")
    }

    @Test
    fun `反解单遍扫描：转义的反斜杠后面跟 n 不能被当成换行（连串 replace 会吃错）`() {
        // Kotlin 字面量 "\\\\n" = 文本 `\` `\` `n`（TOML 里即「转义的反斜杠 + 字母 n」）
        assertEquals("\\n", Lines.tomlUnescape("\\\\n"))
        val original = "\\n"
        assertEquals(original, Lines.tomlUnescape(Lines.tomlEscape(original)))
    }

    @Test
    fun `非 ASCII（中文）原样透传`() {
        val cn = "中转·线路"
        assertEquals(cn, Lines.tomlEscape(cn))
        assertEquals(cn, Lines.tomlUnescape(cn))
    }

    @Test
    fun `转义输出里不出现裸换行或裸控制符`() {
        val out = Lines.tomlEscape("a\nb\rc\td\"e\\fg")
        assertTrue(out.none { it < ' ' || it == '\u007F' }, "实际：$out")
    }

    // ---------- matchesCodex：四元组（端点+钥匙+模型+强度）逐项比 ----------

    private fun codexLine(model: String? = "gpt-5", effort: String? = "high") = Lines.Line(
        id = "l1", name = "中转A", baseUrl = "https://relay.example/v1", apiKey = "sk-1", agent = Lines.CODEX,
        extra = JSONObject().put("model", model).put("model_reasoning_effort", effort),
    )

    @Test
    fun `四元组全等才算当前线`() {
        assertTrue(Lines.matchesCodex(codexLine(), Lines.CodexNow("https://relay.example/v1", "sk-1", "gpt-5", "high")))
    }

    @Test
    fun `同端点不同钥匙不算（一个中转常挂几家）`() {
        assertFalse(Lines.matchesCodex(codexLine(), Lines.CodexNow("https://relay.example/v1", "sk-2", "gpt-5", "high")))
    }

    @Test
    fun `模型或强度不同都不算`() {
        val now = Lines.CodexNow("https://relay.example/v1", "sk-1", "gpt-5", "high")
        assertFalse(Lines.matchesCodex(codexLine(model = "gpt-5-mini"), now))
        assertFalse(Lines.matchesCodex(codexLine(effort = "medium"), now))
    }

    @Test
    fun `线路没配模型或强度时与空串比对（optString 缺键得空串）`() {
        val bare = codexLine(model = "", effort = "")
        assertTrue(Lines.matchesCodex(bare, Lines.CodexNow("https://relay.example/v1", "sk-1")))
        assertFalse(Lines.matchesCodex(bare, Lines.CodexNow("https://relay.example/v1", "sk-1", "gpt-5", "high")))
    }

    // ---------- 白名单两道门：顶层默认拒绝，env 按前缀 + 点名 ----------

    @Test
    fun `顶层白名单放偏好键、拒钩子类`() {
        assertTrue(Lines.topAllowed("effortLevel"))
        assertTrue(Lines.topAllowed("model"))
        assertFalse(Lines.topAllowed("hooks"))
        assertFalse(Lines.topAllowed("apiKeyHelper"))
        assertFalse(Lines.topAllowed("permissions"))
    }

    @Test
    fun `env 白名单按前缀与点名，NODE_OPTIONS 等不进`() {
        assertTrue(Lines.envAllowed("ANTHROPIC_MODEL"))
        assertTrue(Lines.envAllowed("ENABLE_TOOL_SEARCH"))
        assertTrue(Lines.envAllowed("DISABLE_PROMPT_CACHING"))
        assertTrue(Lines.envAllowed("MAX_THINKING_TOKENS"))
        assertFalse(Lines.envAllowed("NODE_OPTIONS"))
        assertFalse(Lines.envAllowed("LD_PRELOAD"))
        assertFalse(Lines.envAllowed("PATH"))
        assertFalse(Lines.envAllowed("HOME"))
    }

    @Test
    fun `rejectedKeys 把不许的顶层键和 env 键都挑出来（含 env 前缀）`() {
        val extra = JSONObject()
            .put("hooks", JSONObject())
            .put("model", "gpt-5")
            .put("env", JSONObject().put("NODE_OPTIONS", "--require=/x.js").put("ANTHROPIC_MODEL", "gpt-5"))
        val rejected = Lines.rejectedKeys(extra)
        assertEquals(2, rejected.size, "实际：$rejected")
        assertContains(rejected, "hooks")
        assertContains(rejected, "env.NODE_OPTIONS")
    }

    @Test
    fun `fromSettings 拆出核心三键、白名单外的键进不了模型`() {
        val settings = JSONObject()
            .put("model", "gpt-5")
            .put("hooks", JSONObject())
            .put(
                "env", JSONObject()
                    .put("ANTHROPIC_BASE_URL", "https://relay.example/v1")
                    .put("ANTHROPIC_AUTH_TOKEN", "tok")
                    .put("ANTHROPIC_API_KEY", "sk")
                    .put("ANTHROPIC_MODEL", "gpt-5")
                    .put("NODE_OPTIONS", "--require=/x.js"),
            )
        val line = Lines.Line.fromSettings(Lines.Line(id = "l1", name = "n"), settings)
        assertEquals("https://relay.example/v1", line.baseUrl)
        assertEquals("tok", line.token)
        assertEquals("sk", line.apiKey)
        assertEquals("gpt-5", line.extra.optString("model"))
        assertFalse(line.extra.has("hooks"))
        assertFalse(line.extraEnv().has("NODE_OPTIONS"))
        assertTrue(line.extraEnv().has("ANTHROPIC_MODEL"))
    }
}
