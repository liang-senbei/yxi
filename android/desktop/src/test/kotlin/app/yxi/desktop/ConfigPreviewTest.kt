package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 7fa5e17 定向检查：configPreview 是纯显示投影——递归打码 token/apiKey/password/
 * secret/headers 等键，坏 JSON 不显示原文，TOML/env（含 dotenv 变体）默认全隐藏；
 * 非敏感内容与路径行为保持原样。纯字符串函数，假 JSON 直接喂，零 IO。
 */
class ConfigPreviewTest {
    private val secret = "sk-live-abc123SECRETVALUE"

    private fun jsonWithSecrets() = """
        {
          "model": "claude",
          "api_key": "$secret",
          "AUTH-TOKEN": "$secret",
          "accessToken": "$secret",
          "client_secret": "$secret",
          "password": "$secret",
          "X-Custom-Headers": {"Authorization": "Bearer $secret"},
          "headers": "sessionid=leak",
          "nested": {"privateKey": "$secret", "deep": [{"credential": "$secret"}, "plain"]},
          "safe": {"note": "普通配置", "count": 3, "flag": true, "empty": null}
        }
    """.trimIndent()

    /** 递归打码：敏感键（大小写/连字符/下划线/嵌套/数组）变 ••••••，非敏感原样保留。 */
    @Test fun `masks sensitive keys recursively and keeps the rest verbatim`() {
        val out = configPreview(jsonWithSecrets(), "/home/u/.claude/settings.json")
        assertFalse(out.contains(secret), "密文不得出现在预览")
        assertFalse(out.contains("sessionid=leak"), "headers 值必须打码")
        assertTrue(out.contains("••••••") || out.contains("\\u2022"), "敏感值以打码符出现")
        assertTrue(out.contains("\"claude\"") && out.contains("\"note\": \"普通配置\""), "非敏感值保留")
        assertTrue(out.contains("\"count\": 3") && out.contains("\"flag\": true"), "标量保留")
        assertTrue(out.contains("plain"), "数组普通元素保留")
        assertFalse(out.contains("Bearer"), "嵌套 headers 内的 Authorization 打码")
        assertFalse(out.contains("abc123"), "嵌套 privateKey 打码")
    }

    /** 坏 JSON / 顶层标量：一律提示文案，绝不回显原文。 */
    @Test fun `broken or scalar json shows fallback text never the raw content`() {
        for (raw in listOf("{ not json", "{\"a\":", "just a string with $secret", "12345", "null")) {
            val out = configPreview(raw, "settings.json")
            assertTrue(out.contains("配置预览暂不可用"), "坏输入应有兜底: $raw")
            assertFalse(out.contains(secret), "兜底路径不得泄漏: $raw")
        }
        // 合法 JSON 前缀+拖尾垃圾：org.json 只取第一个值，返回打码后的合法部分，不回显垃圾
        val trailing = configPreview("[] trailing garbage $secret", "settings.json")
        assertFalse(trailing.contains(secret))
        assertEquals("[]", trailing.trim())
        // 空内容：不崩溃、无泄漏（ConfigPane 以 ifBlank 兜占位）
        assertTrue(configPreview("", "settings.json").isNotEmpty())
    }

    /** TOML/env 后缀（大小写不敏感）全隐藏；dotenv 变体（.env.local 等）同样默认隐藏。 */
    @Test fun `toml and env files are fully hidden including dotenv variants`() {
        val raw = "api_key = \"$secret\""
        for (path in listOf("config.toml", "CONFIG.TOML", ".env", ".ENV",
                            "/home/u/app/.env.local", ".env.production", "secrets.Env")) {
            val out = configPreview(raw, path)
            assertFalse(out.contains(secret), "$path 必须默认全隐藏")
            assertTrue(out.contains("配置源码默认隐藏"), "$path 应显示提示文案")
        }
    }

    /** 非 JSON 隐藏类后缀之外的文件原样返回；inline 标题行为：无扩展名原样（内容已由服务端打码）。 */
    @Test fun `non json files return raw and extensionless inline titles behave as before`() {
        val md = "# CLAUDE.md\n说明文字"
        assertEquals(md, configPreview(md, "/home/u/.claude/CLAUDE.md"))
        assertEquals(md, configPreview(md, "CLAUDE.md"))
        val serverMasked = """{"env": {"API_KEY": "••••"}}"""
        assertEquals(serverMasked, configPreview(serverMasked, "my-mcp-server"), "无扩展名标题原样返回（服务端已打码）")
        assertEquals(serverMasked, configPreview(serverMasked, ""), "空 path 用空串判定，同样原样")
    }

    /** settings.json 内联标题兜底：path 空但 title 以 .json 结尾时仍按 JSON 打码。 */
    @Test fun `inline settings json title still gets masked`() {
        val out = configPreview("""{"token": "$secret", "model": "m"}""", "settings.json")
        assertFalse(out.contains(secret))
        assertTrue((out.contains("••••••") || out.contains("\\u2022")) && out.contains("\"m\""))
    }
}
