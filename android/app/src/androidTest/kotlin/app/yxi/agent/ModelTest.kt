package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `/model` 选单的解析。**下面是真面板原样抠出来的**（2026-08-24 实测）。
 */
class ModelTest {

    // ── borrowable：能不能借这个会话送键 ──────────────────────────
    private fun box(inner: String, footer: String = "  ⏵⏵ bypass permissions on (shift+tab to cycle)") =
        "● 上一条回复\n\n" + "─".repeat(80) + "\n" + inner + "\n" + "─".repeat(80) + "\n" + footer

    @Test fun 输入框空着才肯借() {
        assertTrue(Model.borrowable(box("❯ ")))
    }

    @Test fun 输入框有草稿绝不借() {
        // ⚠️ 唯一会造成真实损失的一步：送的键接在草稿后面，回车连带发出去
        assertFalse(Model.borrowable(box("❯ 我正在写一半的话")))
    }

    @Test fun 忙着不借() {
        assertFalse(Model.borrowable(box("❯ ", "  ⏵⏵ bypass permissions on · esc to interrupt")))
    }


    private val REAL = """
   Select model
   Switch between Claude models. Your pick becomes the default for new sessions.
     1. Default (recommended)  Opus 5 with 1M context · Best for everyday, complex tasks
     2. Opus (1M context)      Opus 5 with 1M context · Best for everyday, complex tasks
   ❯ 3. Fable ✔                Fable 5 · Most capable for your hardest and longest-running tasks
     4. Sonnet                 Sonnet 5 · Efficient for routine tasks
     5. Haiku                  Haiku 4.5 · Fastest for quick answers
   ● High effort (default) ←/→ to adjust
   Enter to set as default · s to use this session only · Esc to cancel
"""

    @Test fun 解出五个选项() {
        val c = Model.parse(REAL)!!
        assertEquals(5, c.size)
        assertEquals(1, c[0].number)
        assertEquals("Default (recommended)", c[0].name)
        assertEquals("Sonnet", c[3].name)
        assertTrue(c[2].desc.startsWith("Fable 5"))
    }

    @Test fun 勾在哪就是当前会话在用哪个() {
        val c = Model.parse(REAL)!!
        assertEquals("Fable", c.single { it.current }.name)
        // ⚠️ 名字里不能把 ✔ 带出来
        assertTrue("名字里不该有勾：" + c[2].name, "✔" !in c[2].name)
    }

    @Test fun 不是这个面板就返回空() {
        // ⚠️ 这条是要紧的：权限提示、计划审批**也是编号列表**。
        // 只认编号行的话会把它们当成模型选单，用户一点就把选项送进了别的提示里。
        val perm = """
   Claude needs your permission to use Bash
     1. Yes
     2. Yes, and don't ask again for bash commands
   ❯ 3. No
"""
        assertNull(Model.parse(perm))
        assertNull(Model.parse(""))
    }

    @Test fun 窄窗口下折行的描述也要认() {
        // ⚠️ 手机上的终端很窄，描述会折到下一行 —— 这是**常态不是例外**。
        // 下面这段是真会话（80 列）上原样抓的
        val narrow = """
   Select model
   Switch between Claude models. Your pick becomes the default for new
   sessions. For other/previous model names, specify with --model.
     1. Default (recommended)  Opus 5 with 1M context · Best for everyday,
                               complex tasks
   ❯ 2. Opus (1M context) ✔    Opus 5 with 1M context · Best for everyday,
                               complex tasks
   ↓ 3. Fable                  Fable 5 · Most capable for your hardest and
                               longest-running tasks
      … +2 models
   ◈ Max effort ←/→ to adjust
   Enter to set as default · s to use this session only · Esc to cancel
"""
        val c = Model.parse(narrow)!!
        assertEquals("折行的续行不能被当成新选项", 3, c.size)
        assertEquals("Default (recommended)", c[0].name)
        assertEquals("Fable", c[2].name)
        assertEquals("Opus (1M context)", c.single { it.current }.name)
    }

    @Test fun 权限提示不能被当成模型选单() {
        // ⚠️ 已经在 Prompt.parse 里让路了，这里再守一道：
        // 两边都认成自己的话，界面上会同时冒出两个入口，
        // 而通用那条路点一下是送数字 = 改账号默认
        val perm = """
   Claude needs your permission to use Bash
     1. Yes
     2. Yes, and don't ask again
   ❯ 3. No
   Enter to confirm · Esc to cancel
"""
        assertNull(Model.parse(perm))
    }

    @Test fun 只有标题没有脚注也不认() {
        // 面板还没画完就抓屏了 —— 宁可再抓一次，也不能拿半个面板去点
        assertNull(Model.parse("   Select model\n     1. Opus   Opus 5\n"))
    }
}
