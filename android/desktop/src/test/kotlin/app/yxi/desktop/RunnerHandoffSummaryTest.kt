package app.yxi.desktop

import kotlin.test.*

class RunnerHandoffSummaryTest {
    @Test fun `reviewed sections retain edits and disclose bounded history without inventing completion`() {
        val draft = RunnerHandoffSummary(requirements = "修复模型菜单", remaining = "尚未发布", sourceRange = "最近100条消息")
        val result = draft.copy(requirements = "仅修复当前Agent的模型菜单").render()
        assertTrue(result.contains("最近100条消息"))
        assertTrue(result.contains("仅修复当前Agent的模型菜单"))
        assertTrue(result.contains("## 已完成事项\n未填写"))
        assertTrue(result.contains("附件未自动转移"))
        assertTrue(result.contains("## 未完成事项\n尚未发布"))
        assertFailsWith<IllegalArgumentException> { draft.copy(sourceRange = "").render() }
        assertFailsWith<IllegalArgumentException> { RunnerHandoffSummary(sourceRange = "最近1条").render() }
        assertFailsWith<IllegalArgumentException> { draft.copy(requirements = "x".repeat(16001)).render() }
    }
}
