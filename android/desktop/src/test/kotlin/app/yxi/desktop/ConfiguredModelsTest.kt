package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ConfiguredModels.parse 定向测试：全部假 settings JSON 字符串，零 ssh、零生产配置读取
 * （load 的远端脚本不进测试）。钉住老板点名的六个边界。
 * ⚠️ 这里钉的是「配置来源」的解析结果；不含 CLI 环境覆盖与真实第三方请求，
 * 不等价于运行时绝对生效值。
 */
class ConfiguredModelsTest {

    @Test
    fun `three aliases resolving to one glm model dedupe to a single entry`() {
        val raw = """{"env":{
            "ANTHROPIC_BASE_URL":"https://open.bigmodel.cn/api",
            "ANTHROPIC_MODEL":"glm-5.3",
            "ANTHROPIC_DEFAULT_OPUS_MODEL":"glm-5.3",
            "ANTHROPIC_DEFAULT_SONNET_MODEL":"glm-5.3"
        }}"""
        val selection = ConfiguredModels.parse(raw, current = "opus")
        assertEquals(listOf("glm-5.3"), selection.models, "三个别名同指一个模型：只留一份")
        assertEquals("glm-5.3", selection.resolvedCurrent, "当前 opus 解析到配置的真实名，不再是 opus")
    }

    @Test
    fun `glm and deepseek mappings both show without mixing other lines`() {
        val raw = """{"model":"glm-4.6","env":{
            "ANTHROPIC_BASE_URL":"https://api.z.ai/v1",
            "ANTHROPIC_DEFAULT_OPUS_MODEL":"glm-4.6",
            "ANTHROPIC_DEFAULT_SONNET_MODEL":"deepseek-chat"
        }}"""
        val selection = ConfiguredModels.parse(raw, current = "")
        assertEquals(listOf("glm-4.6", "deepseek-chat"), selection.models)
    }

    @Test
    fun `context suffix appears only when explicitly configured`() {
        val explicit = ConfiguredModels.parse(
            """{"env":{"ANTHROPIC_BASE_URL":"https://api.z.ai","ANTHROPIC_DEFAULT_OPUS_MODEL":"glm-5.3[1m]"}}""",
            current = "")
        assertTrue("glm-5.3[1m]" in explicit.models, "明确配置的 [1m] 原样保留")
        val plain = ConfiguredModels.parse(
            """{"env":{"ANTHROPIC_BASE_URL":"https://api.z.ai","ANTHROPIC_DEFAULT_OPUS_MODEL":"glm-5.3"}}""",
            current = "")
        assertEquals(listOf("glm-5.3"), plain.models, "别名映射绝不自动生成 [1m]")
    }

    @Test
    fun `alias cycles terminate instead of looping forever`() {
        val raw = """{"env":{
            "ANTHROPIC_BASE_URL":"https://api.z.ai",
            "ANTHROPIC_MODEL":"opus",
            "ANTHROPIC_DEFAULT_OPUS_MODEL":"sonnet",
            "ANTHROPIC_DEFAULT_SONNET_MODEL":"opus"
        }}"""
        val selection = ConfiguredModels.parse(raw, current = "opus")
        assertTrue(selection.models.isEmpty(), "opus→sonnet→opus 环：解析不出具体模型就整体为空，不悬挂")
        assertEquals(null, selection.resolvedCurrent)
    }

    @Test
    fun `third party config never mixes in availableModels from elsewhere`() {
        val raw = """{"availableModels":["claude-opus-4-5","gpt-x"],"env":{
            "ANTHROPIC_BASE_URL":"https://open.bigmodel.cn/api",
            "ANTHROPIC_DEFAULT_OPUS_MODEL":"glm-4.6"
        }}"""
        val selection = ConfiguredModels.parse(raw, current = "")
        assertEquals(listOf("glm-4.6"), selection.models, "第三方有明确模型：只显示这些，别的线路/availableModels 一律不混")
    }

    @Test
    fun `official concrete models are preserved verbatim`() {
        val raw = """{"model":"claude-haiku-4-5","availableModels":["claude-opus-4-5","opus","claude-sonnet-4-5[1m]"],
            "env":{"ANTHROPIC_BASE_URL":"https://api.anthropic.com"}}"""
        val selection = ConfiguredModels.parse(raw, current = "claude-opus-4-5")
        assertTrue("claude-haiku-4-5" in selection.models)
        assertTrue("claude-opus-4-5" in selection.models)
        assertTrue("claude-sonnet-4-5[1m]" in selection.models, "官方具体模型（含明确后缀）逐字保留")
        assertFalse("opus" in selection.models, "官方线路下纯别名（无映射）不展示")
        assertEquals("claude-opus-4-5", selection.resolvedCurrent)
    }

    @Test
    fun `unmappable alias stays hidden instead of leaking into the menu`() {
        // 无任何映射的官方配置：别名 current 解析不出 → resolvedCurrent 为 null（Menu 不再兜底展示）
        val selection = ConfiguredModels.parse("""{"availableModels":["claude-opus-4-5"]}""", current = "fable")
        assertEquals(null, selection.resolvedCurrent)
    }
}

/** EffortControl 离散档位的纯函数面：档位名映射。滑条/键盘的离散提交在组合函数内，静态确认。 */
class EffortControlLevelTest {
    @Test
    fun `effort names cover the discrete vocabulary and pass through unknowns`() {
        assertEquals("关闭", effortName("none"))
        assertEquals("最少", effortName("minimal"))
        assertEquals("轻度", effortName("low"))
        assertEquals("中等", effortName("medium"))
        assertEquals("高", effortName("high"))
        assertEquals("更高", effortName("xhigh"))
        assertEquals("最高", effortName("max"))
        assertEquals("future-level", effortName("future-level"), "未知档位原样透传，不臆造")
    }
}
