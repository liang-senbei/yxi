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

    private companion object {
        const val ENQUEUE1 = "{\"type\":\"queue-operation\",\"operation\":\"enqueue\",\"timestamp\":\"2026-08-23T08:17:48.663Z\",\"sessionId\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"content\":\"排队的第一条：顺便说说时间复杂度\"}"
        const val ENQUEUE2 = "{\"type\":\"queue-operation\",\"operation\":\"enqueue\",\"timestamp\":\"2026-08-23T08:17:50.686Z\",\"sessionId\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"content\":\"排队的第二条：再给个更快的写法\"}"
        const val REMOVE1 = "{\"type\":\"queue-operation\",\"operation\":\"remove\",\"timestamp\":\"2026-08-23T08:19:44.145Z\",\"sessionId\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"content\":\"排队的第一条：顺便说说时间复杂度\"}"
        const val QUEUED_COMMAND = "{\"parentUuid\":\"f523fcb6-6205-4b64-be96-1c51655fc125\",\"isSidechain\":false,\"attachment\":{\"type\":\"queued_command\",\"prompt\":\"排队的第一条：顺便说说时间复杂度\",\"source_uuid\":\"4c2ad2d4-57f8-4a19-b4dd-bee24d3ee212\",\"commandMode\":\"prompt\",\"origin\":{\"kind\":\"human\"},\"timestamp\":\"2026-08-23T08:17:48.663Z\"},\"type\":\"attachment\",\"uuid\":\"c310cab2-4987-42df-aeb1-1e7f5d78744f\",\"timestamp\":\"2026-08-23T08:17:48.663Z\",\"session_id\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"userType\":\"external\",\"entrypoint\":\"cli\",\"cwd\":\"/tmp/claude-0/-root-src-workspace-Yxi/d0ccc7db-ab52-458f-807f-39247666d0c2/scratchpad/livetest\",\"sessionId\":\"459efa19-5e8f-4894-8f9a-e07010d9e04c\",\"version\":\"2.1.241\",\"gitBranch\":\"HEAD\"}"
    }
}
