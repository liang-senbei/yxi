package app.yxi

import app.yxi.agent.Live
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「上传附件之后点发送，话又回到输入框里」相关的判据。**（老板 2026-09-06 报）
 *
 * ⚠️ **根因不是回车被吞。** 我一开始是这么猜的（多行 → 括号粘贴 → 回车被当成换行），
 * 还写了压测去打真 Claude Code（`scratchpad/stress2.py`，12 条 × 长附件头 × 模拟慢链路
 * 分块到达）—— **旧写法和新写法都是 0 条卡住，复现不出来**。
 * 想通了：那 0.4 秒的间隔是**服务器端**在一条命令里 sleep 的，网络抖动根本拉不长它。
 *
 * 真根因是**连接**：上传是最容易把 SSH 连接换掉的操作（几张图 / 一段视频，
 * 中途断线重连很常见）。上传那条路早就为此做了「等一条活着的连接」
 * （`Uploader` + `ChatScreen.aliveSsh`），而发送那条路拿的是 Composable 里
 * **抓好的那个** `ssh`，一看 `isConnected == false` 就当场判失败、把草稿还回输入框。
 * 所以症状精确地是「**特别是上传有附件的时候**」。
 *
 * ⚠️ 这个文件只放**能真验的东西**：附件消息的真实形状、以及重试所依赖的那个判据
 * （[Live.inputEmpty]）。「等连接」那段要真 SshSession 才验得了，靠 Mac E2E 覆盖，
 * **不在这里摆一个自己写的 lambda 假装测过**（那正是今天栽过的「测试比现实宽松」）。
 */
class SendReconnectTest {

    /**
     * **带附件的消息一定是多行的** —— 这是这条路上的常态不是特例。
     * 压测和用例的样本都得照真实形状来（头部若干行路径 + 正文），别拿单行凑数。
     */
    @Test fun 附件消息的形状是多行() {
        val staged = (1..6).map {
            app.yxi.agent.Attachments.Staged(
                label = "图片$it",
                remotePath = "/root/src/tmp/Yxi/0906-00$it-Screenshot_20260906_1234_com_hihonor_x.jpg",
                isImage = true, ext = "jpg",
            )
        }
        val header = app.yxi.agent.Attachments.header(staged)
        assertTrue("六个附件的头部应该是多行，实际=\n$header", header.count { it == '\n' } >= 6)
    }

    /**
     * **重试补回车所依赖的判据：输入框里还有没有东西。**
     *
     * ⚠️ 三态不能混（#276 的老规矩）：
     * · `true`  = 空了 → 提交成功
     * · `false` = 还堆着 → 补一次回车
     * · `null`  = **判断不了**（读不到屏 / 找不到边框）→ **什么都不做**，
     *   既不补回车（可能在别的界面上误触发），也不谎报失败。
     */
    @Test fun 输入框判据的三态() {
        val 上 = "─".repeat(39) + " cc-demo ─"     // 上边框画着会话名（真实形状，见 #281）
        val 下 = "─".repeat(50)

        assertEquals(true, Live.inputEmpty(listOf("正文", 上, "❯", 下).joinToString("\n")))
        assertEquals(
            false,
            Live.inputEmpty(listOf("正文", 上, "❯ [附件] /root/a.jpg", "还没发出去的正文", 下).joinToString("\n")),
        )
        assertNull("读不到屏就别下结论", Live.inputEmpty(""))
        assertNull("找不到两条边框也别下结论", Live.inputEmpty("只有正文\n没有输入框"))
    }

    /**
     * **真抓屏：Claude Code 忙着的时候，发过去的话进「排队」，输入框里画的是它的占位提示。**
     *
     * ⚠️ 这份是**真会话抓的**（`tmux capture-pane -p`，haiku 写长文时插两条），不是我编的形状。
     *    老板 2026-09-06 录屏报的就是它：服务器上那条消息**到了五次**，App 每次都说「没发出去」，
     *    他就再发一遍。根因是这句提示被当成了「输入框里还堆着草稿」。
     *
     * ⚠️ **有这句 = 话已经收下了**（排队 = 收下，只是还没轮到），必须判成「空」。
     *    判成 false 的话调用方会补三次回车、再报失败、把话还回输入框 —— 正是老板看到的。
     */
    @Test fun 排队时的占位提示要判成空() {
        val 真抓屏 = """
  ❯ 排队甲：这条应该进队列
  ❯ 排队乙：这条也是

───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯ Press up to edit queued messages
───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  ⏵⏵ bypass permissions on (shift+tab to cycle) · esc to interrupt · ← for agents
        """.trimIndent()
        assertEquals("排队 = 已经收下了", true, Live.inputEmpty(真抓屏))
    }

    /** 反过来：真有草稿堆着的时候别判成空（不然就永远不补回车了）。 */
    @Test fun 真有草稿还是要判成没发出去() {
        val 上 = "─".repeat(39) + " cc-demo ─"
        val 下 = "─".repeat(50)
        assertEquals(false, Live.inputEmpty(listOf(上, "❯ 这条真的还在框里", 下).joinToString("\n")))
        // 长得像但不是那句提示的，照旧算草稿
        assertEquals(false, Live.inputEmpty(listOf(上, "❯ press up 然后呢", 下).joinToString("\n")))
    }
}
