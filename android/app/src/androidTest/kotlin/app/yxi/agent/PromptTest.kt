package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Prompt] 的解析。**样本全部是从真机屏幕上原样抄下来的**（`tmux capture-pane -p`），
 * 不是照着想象编的 —— 编出来的样本只能验证我的想象。
 */
class PromptTest {

    /** 单选：AskUserQuestion，两个真选项 + Type something + Chat about this。 */
    private val single = """
        ❯ 用 AskUserQuestion 工具问我一个问题：晚饭吃面还是吃饭。
        ────────────────────────────────────────────────────
         ☐ 晚饭

        晚饭吃面还是吃饭？

        ❯ 1. 吃面
             面条类，快手管饱
          2. 吃饭
             米饭配菜
          3. Type something.
        ────────────────────────────────────────────────────
          4. Chat about this
        Enter to select · ↑/↓ to navigate · Esc to cancel
    """.trimIndent()

    /** 多选：带 `[ ]` 复选框，还有个 Submit。 */
    private val multi = """
        ←  ☒ 配菜  ✔ Submit  →

        配菜加哪些？（可多选）

        ❯ 1. [ ] 黑椒牛柳
          肃菜，下饭
          2. [ ] 番茄炒蛋
          经典家常菜
          3. [✔] 清炒时蔬
          素菜解腻
          4. [ ] 紫菜蛋花汤
          配一份汤
          5. [ ] Type something
             Submit
        ────────────────────────────────────────────────────
          6. Chat about this

        Enter to select · ↑/↓ to navigate · Esc to cancel
    """.trimIndent()

    /** ExitPlanMode 的批准框 —— 也是同一套选择器。 */
    private val plan = """
           3. 出锅：关火，倒进碗。
          ──────────────────────────────────────────────────
           Claude has written up a plan and is ready to execute. Would you like to proceed?

           ❯ 1. Yes, and switch to BYPASS PERMISSIONS (no further prompts) for this session
             2. Yes, manually approve edits
             3. Tell Claude what to change
                shift+tab to approve with this feedback

           Enter to select · ↑/↓ to navigate · Esc to cancel
    """.trimIndent()

    @Test fun 单选() {
        val p = Prompt.parse(single)!!
        assertEquals("晚饭吃面还是吃饭？", p.title)
        assertEquals(false, p.multiSelect)
        assertEquals(listOf(1, 2, 3, 4), p.options.map { it.number })
        assertEquals("吃面", p.options[0].label)
        assertEquals("面条类，快手管饱", p.options[0].description)
        // ⚠️ 脚注那行不能变成最后一项的说明（实测在手机上看见过）
        assertTrue(p.options.none { "to navigate" in it.description })
    }

    @Test fun 多选带勾选状态() {
        val p = Prompt.parse(multi)!!
        assertEquals("配菜加哪些？（可多选）", p.title)
        assertTrue(p.multiSelect)
        assertEquals("黑椒牛柳", p.options[0].label)   // `[ ] ` 前缀要剥掉
        assertEquals(false, p.options[0].checked)
        assertEquals(true, p.options[2].checked)       // 第 3 项是 [✔]
    }

    @Test fun 计划批准框() {
        val p = Prompt.parse(plan)!!
        assertTrue("Would you like to proceed" in p.title)
        assertEquals(3, p.options.size)
        assertTrue(p.options[0].label.startsWith("Yes,"))
    }

    /** 没在等人选的时候必须返回 null，不能把普通输出当成选项。 */
    @Test fun 普通屏幕不算提示() {
        assertNull(Prompt.parse("$ ls\n1. foo\n2. bar\n$ "))
        assertNull(Prompt.parse(""))
    }

    /**
     * ⚠️ 回滚里的编号列表**不能**被当成选项 —— 那会让人点到一个不存在的选项，
     * 送出去的按键会落到别处。靠「只看脚注上面 30 行」挡掉。
     */
    @Test fun 远处的编号列表不会被误认() {
        val far = buildString {
            appendLine("1. 这是很久以前输出里的一个列表项")
            appendLine("2. 另一项")
            repeat(40) { appendLine("...") }
            appendLine("要继续吗？")
            appendLine("❯ 1. 好")
            appendLine("  2. 不")
            appendLine("Enter to select · ↑/↓ to navigate · Esc to cancel")
        }
        val p = Prompt.parse(far)!!
        assertEquals(listOf(1, 2), p.options.map { it.number })
        assertEquals("好", p.options[0].label)
    }
}
