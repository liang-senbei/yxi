package app.yxi

import app.yxi.agent.ChatItem
import app.yxi.ui.ChatRow
import app.yxi.ui.groupToolRuns
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **滚动锚点不能丢。**
 *
 * 对话页先画最新 60 行，再把 400 行历史**往前**灌。LazyColumn 是靠 `key` 找滚动锚点的 ——
 * 只要当前屏幕上那一行的 key 在新列表里还在，位置就保住；一旦消失，它退回按下标定位，
 * 于是**停在几天前的内容上**，每来一批再错一次 = 用户 2026-09-05 录到的
 * 「一闪一闪 + 跳到很久很久以前的对话记录」。
 *
 * 分组行（连续同名工具卡合成一张）的 key 曾经是 `"group-" + calls.first().key` ——
 * 而历史往前灌时，同名连续段会**往前长**，`first()` 就变了，key 随之消失。
 * 这个测试把那个场景原样摆出来：**任何一次「往前加历史」之后，
 * 之前每一行的 key 都必须还能在新列表里找到。**
 */
class ChatRowKeyTest {

    private fun bash(k: String) = ChatItem.ToolCall(
        key = k, name = "Bash", input = org.json.JSONObject(), result = "ok", isError = false,
    )

    private fun say(k: String) = ChatItem.UserText(key = k, text = "说了句话 $k")
    private fun bad(k: String) = ChatItem.ToolCall(key = k, name = "Bash", input = org.json.JSONObject(), result = "exit 1", isError = true)

    /** 老板 2026-09-08 截图：一串 Bash 夹着两条 exit 1 就整串散开 —— 现在要合成一张，失败数在卡上标出来（这里只验分组）。 */
    @Test fun 夹着失败的一串也合成一组() {
        val list = listOf(say("u0"), bash("b1"), bad("b2"), bash("b3"), bad("b4"), bash("b5"), bash("b6"), say("u1"))
        val rows = groupToolRuns(list)
        val groups = rows.filterIsInstance<ChatRow.Group>()
        assertTrue("应合成一组，实际 ${rows.size} 行 / ${groups.size} 组", groups.size == 1 && groups[0].calls.size == 6)
        assertTrue("组里失败数应是 2", groups[0].calls.count { it.isError } == 2)
        // 还在跑的最后一条（result == null）不合进去
        val running = ChatItem.ToolCall(key = "b7", input = org.json.JSONObject(), name = "Bash", result = null, isError = false)
        val rows2 = groupToolRuns(list.dropLast(1) + running)
        assertTrue("跑着的那条要单独留着", rows2.last() is ChatRow.One)
    }

    /** 往前灌历史：同名工具的连续段会往前长，组的第一条会变，最后一条不会。 */
    @Test fun 历史往前灌之后老的key必须还在() {
        // 只有最新一屏时：能看到这一段 Bash 的后三条
        val head = listOf(bash("b4"), bash("b5"), bash("b6"), say("u1"))
        // 历史补齐之后：同一段 Bash 其实有六条
        val full = listOf(
            say("u0"), bash("b1"), bash("b2"), bash("b3"),
            bash("b4"), bash("b5"), bash("b6"), say("u1"),
        )

        val before = groupToolRuns(head).map { it.key }.toSet()
        val after = groupToolRuns(full).map { it.key }.toSet()

        val lost = before - after
        assertTrue(
            "灌完历史之后这些 key 没了，滚动锚点会丢：$lost（前 $before / 后 $after）",
            lost.isEmpty(),
        )
    }

    /** 压力版：反复往前灌，每一步都不许丢 key。 */
    @Test fun 连续往前灌二十次每一步都不丢key() {
        var list: List<ChatItem> = listOf(bash("b100"), bash("b101"), bash("b102"), say("tail"))
        var prev = groupToolRuns(list).map { it.key }.toSet()
        for (step in 1..20) {
            // 每一步往**前面**加三条同名工具卡 —— 正是「历史一批批补进来」的形状
            val older = listOf(bash("b${100 - step * 3}"), bash("b${101 - step * 3}"), bash("b${102 - step * 3}"))
            list = older + list
            val now = groupToolRuns(list).map { it.key }.toSet()
            val lost = prev - now
            assertTrue("第 $step 批历史灌进来之后丢了 key：$lost", lost.isEmpty())
            prev = now
        }
    }

    /**
     * 第二条丢 key 的路（子代理独立查到的，我一开始漏了）：
     * **60 行窗口里边界上的 `tool_use` 配不到 `tool_result`** → `result == null` → **不合组**，
     * 是三行独立的 One；400 行齐了之后 result 有了 → 合成一组。
     * 组的 key 要是带 `"group-"` 前缀，这三个 key 一个都不剩。
     */
    @Test fun result补齐之后合组也不能把老key全弄没() {
        fun pending(k: String) = ChatItem.ToolCall(
            key = k, name = "Bash", input = org.json.JSONObject(), result = null, isError = false,
        )
        // 窗口边界：结果还没配上，三条各自成行
        val head = listOf(pending("b1"), pending("b2"), pending("b3"), say("u1"))
        // 完整解析：结果齐了，合成一组
        val full = listOf(bash("b1"), bash("b2"), bash("b3"), say("u1"))

        val before = groupToolRuns(head).map { it.key }.toSet()
        val after = groupToolRuns(full).map { it.key }.toSet()
        assertTrue(
            "合组之后这些 key 一个都没剩，锚点必丢：前 $before / 后 $after",
            (before intersect after).any { it.startsWith("b") },
        )
    }

    /** 组还是要真的合起来 —— 别为了 key 稳定把分组本身弄坏了。 */
    @Test fun 分组本身没坏() {
        val rows = groupToolRuns(listOf(bash("a"), bash("b"), bash("c"), say("u")))
        assertTrue("连续三条同名工具卡应该合成一组", rows.any { it is ChatRow.Group })
        assertTrue("非工具卡不该被合进去", rows.any { it is ChatRow.One })
        // 不足 3 条不合
        val few = groupToolRuns(listOf(bash("a"), bash("b"), say("u")))
        assertTrue("只有两条不该合组", few.none { it is ChatRow.Group })
    }
}
