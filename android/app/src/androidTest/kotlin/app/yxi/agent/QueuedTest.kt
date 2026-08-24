package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排队输入的解析。⚠️ 下面每一行都是**真实转录里原样抠出来的**。
 *
 * 这一块的来历：用户在 Claude 忙的时候连打了四条，手机上一条都没出现 ——
 * 因为它们在转录里的类型是 `queue-operation` / `attachment`，不是 `user`，
 * 老解析器当成不认识**静默丢掉**了。见 TROUBLESHOOTING #72。
 */
class QueuedTest {

    @Test fun 进队了还没处理就显示成排队中() {
        val items = Transcript.parse(sequenceOf(ENQUEUE1, ENQUEUE2))
        val q = items.filterIsInstance<ChatItem.Queued>()
        assertEquals("两条都该在排队：" + items, 2, q.size)
        assertTrue(q[0].text.contains("时间复杂度"))
    }

    @Test fun 斜杠命令出队之后气泡要消失() {
        // ⚠️ 真事：用户打错成 `/modle` 排进队里，气泡**永远挂着**。
        // 转录里它只有 enqueue + dequeue：
        //   · 没有 remove
        //   · dequeue **不带 content**（实测 1094 条一条都没有）
        //   · 而且斜杠命令被本地消化，**永远不会作为 user 消息出现**
        //     → #76 那个「出现过就算说过」的兜底也救不了它
        // 所以必须按先进先出弹队头。
        val items = Transcript.parse(sequenceOf(ENQUEUE_SLASH, DEQUEUE))
        assertEquals("出队了就不该还挂着：" + items, 0, items.filterIsInstance<ChatItem.Queued>().size)
    }

    @Test fun 出队是先进先出() {
        // 排两条，只出一条 —— 走的必须是**队头**那条
        val items = Transcript.parse(sequenceOf(ENQUEUE1, ENQUEUE2, DEQUEUE))
        val q = items.filterIsInstance<ChatItem.Queued>()
        assertEquals(1, q.size)
        assertTrue("留下的应该是后排那条：" + q[0].text, q[0].text.contains("更快的写法"))
    }

    @Test fun 同一句话排两次不能被合成一条() {
        // ⚠️ 原来用的是 Set，两条一模一样的排队会被去重掉 —— 那是真的丢消息
        val items = Transcript.parse(sequenceOf(ENQUEUE1, ENQUEUE1))
        assertEquals("两条都得在：" + items, 2, items.filterIsInstance<ChatItem.Queued>().size)
    }

    @Test fun 被收回输入框之后就不再是排队中() {
        // ⚠️ `popAll` 是在真会话上按 `Up` 实测出来的第四种 operation
        //（TUI 脚注：Press up to edit queued messages）。手机上的「收回改一改」走的就是它。
        // 漏认这个词的后果：撤回之后那几条气泡**永远挂着** ——
        // 转录里没有 remove，而它们再也不会被处理，`said` 那条路也兜不住。
        val items = Transcript.parse(sequenceOf(ENQUEUE1, POPALL1))
        assertEquals("收回去了就不该还挂着：" + items, 0, items.filterIsInstance<ChatItem.Queued>().size)
    }

    @Test fun 收回是全有全无所以多条要一起消失() {
        // 按一次 Up 会把排队的全部弹回输入框，每弹一条写一条 popAll
        val items = Transcript.parse(sequenceOf(ENQUEUE1, ENQUEUE2, POPALL1, POPALL2))
        assertEquals("两条都该消失：" + items, 0, items.filterIsInstance<ChatItem.Queued>().size)
    }

    @Test fun 出队之后就不再是排队中() {
        val items = Transcript.parse(sequenceOf(ENQUEUE1, REMOVE1))
        assertEquals("出队了就不该还挂着：" + items, 0, items.filterIsInstance<ChatItem.Queued>().size)
    }

    @Test fun 真正被处理时变成普通用户消息() {
        val items = Transcript.parse(sequenceOf(ENQUEUE1, REMOVE1, QUEUED_COMMAND))
        val u = items.filterIsInstance<ChatItem.UserText>()
        assertEquals("应该留下一条正常的用户消息：" + items, 1, u.size)
        assertTrue(u[0].text.contains("时间复杂度"))
        assertEquals("不能既是普通消息又还挂在排队里", 0, items.filterIsInstance<ChatItem.Queued>().size)
    }

    @Test fun 排队的消息不会凭空消失() {
        // ⚠️ 这是最要紧的一条：用户打的字，从「排队中」到「被处理」，
        // 全程都得在界面上看得见。中间断一截，用户就会以为没发出去然后重发
        val 排队时 = Transcript.parse(sequenceOf(ENQUEUE1))
        val 处理后 = Transcript.parse(sequenceOf(ENQUEUE1, REMOVE1, QUEUED_COMMAND))
        assertTrue("排队时看得见", 排队时.any { it is ChatItem.Queued && "时间复杂度" in it.text })
        assertTrue("处理后还看得见", 处理后.any { it is ChatItem.UserText && "时间复杂度" in it.text })
    }

    @Test fun 后来以普通用户消息出现过的就不算排队() {
        // ⚠️ **出队的判据不是 remove。** 实测一个真实会话：35 个 enqueue 只有
        // 29 个 remove，剩下 13 条全都后来以普通 `user` 消息出现了 ——
        // 命令几小时前就跑完了，界面上还挂着「排队中」。见 TROUBLESHOOTING #76。
        val items = Transcript.parse(sequenceOf(ENQUEUE_A, USER_SAME_TEXT))
        assertEquals("它已经作为用户消息出现过了，不该还挂着：" + items,
            0, items.filterIsInstance<ChatItem.Queued>().size)
        assertEquals(1, items.filterIsInstance<ChatItem.UserText>().size)
    }

    private companion object {
        const val ENQUEUE1 = "{\"type\":\"queue-operation\",\"operation\":\"enqueue\",\"timestamp\":\"2026-08-23T08:17:48.663Z\",\"sessionId\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"content\":\"排队的第一条：顺便说说时间复杂度\"}"
        const val ENQUEUE2 = "{\"type\":\"queue-operation\",\"operation\":\"enqueue\",\"timestamp\":\"2026-08-23T08:17:50.686Z\",\"sessionId\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"content\":\"排队的第二条：再给个更快的写法\"}"
        // ⚠️ 下面两条是 2026-08-24 从真转录里原样抠的（打错的 `/modle` 那次）
        const val ENQUEUE_SLASH = "{\"type\":\"queue-operation\",\"operation\":\"enqueue\",\"timestamp\":\"2026-08-24T09:12:00.000Z\",\"sessionId\":\"x\",\"content\":\"/modle\"}"
        const val DEQUEUE = "{\"type\":\"queue-operation\",\"operation\":\"dequeue\",\"timestamp\":\"2026-08-24T09:12:01.000Z\",\"sessionId\":\"x\"}"
        const val REMOVE1 = "{\"type\":\"queue-operation\",\"operation\":\"remove\",\"timestamp\":\"2026-08-23T08:19:44.145Z\",\"sessionId\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"content\":\"排队的第一条：顺便说说时间复杂度\"}"
        // ⚠️ 下面两条是 2026-08-24 在真会话上按 `Up` 抓下来的原样，不是编的
        const val POPALL1 = "{\"type\":\"queue-operation\",\"operation\":\"popAll\",\"timestamp\":\"2026-08-24T01:45:50.000Z\",\"sessionId\":\"12d150e1-4e72-4ef7-84ef-2836b5c816a9\",\"content\":\"排队的第一条：顺便说说时间复杂度\"}"
        const val POPALL2 = "{\"type\":\"queue-operation\",\"operation\":\"popAll\",\"timestamp\":\"2026-08-24T01:45:50.000Z\",\"sessionId\":\"12d150e1-4e72-4ef7-84ef-2836b5c816a9\",\"content\":\"排队的第二条：再给个更快的写法\"}"
        const val QUEUED_COMMAND = "{\"parentUuid\":\"f523fcb6-6205-4b64-be96-1c51655fc125\",\"isSidechain\":false,\"attachment\":{\"type\":\"queued_command\",\"prompt\":\"排队的第一条：顺便说说时间复杂度\",\"source_uuid\":\"4c2ad2d4-57f8-4a19-b4dd-bee24d3ee212\",\"commandMode\":\"prompt\",\"origin\":{\"kind\":\"human\"},\"timestamp\":\"2026-08-23T08:17:48.663Z\"},\"type\":\"attachment\",\"uuid\":\"c310cab2-4987-42df-aeb1-1e7f5d78744f\",\"timestamp\":\"2026-08-23T08:17:48.663Z\",\"session_id\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/livetest\",\"sessionId\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"version\":\"2.1.241\",\"gitBranch\":\"HEAD\"}"

        /** 同一句话的 enqueue，和它后来作为普通 user 消息出现的那一行（都是真实结构） */
        const val ENQUEUE_A = "{\"type\":\"queue-operation\",\"operation\":\"enqueue\",\"timestamp\":\"2026-08-23T08:24:52.590Z\",\"sessionId\":\"12d150e1-4e72-4ef7-84ef-2836b5c816a9\",\"content\":\"排队甲：这条应该在手机上看得见\"}"
        const val USER_SAME_TEXT = "{\"parentUuid\": \"518a2469-2c3e-45b6-ab47-0089928cd320\", \"isSidechain\": false, \"promptId\": \"e435973c-2837-4af3-b4a9-c3be339597ed\", \"type\": \"user\", \"message\": {\"role\": \"user\", \"content\": \"排队甲：这条应该在手机上看得见\"}, \"uuid\": \"u-after-queue\", \"timestamp\": \"2026-08-23T08:24:43.578Z\", \"permissionMode\": \"bypassPermissions\", \"origin\": {\"kind\": \"human\"}, \"promptSource\": \"typed\", \"userType\": \"external\", \"entrypoint\": \"cli\", \"cwd\": \"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/livetest\", \"sessionId\": \"12d150e1-4e72-4ef7-84ef-2836b5c816a9\", \"version\": \"2.1.241\", \"gitBranch\": \"HEAD\"}"
    }
}
