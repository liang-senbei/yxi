package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API 报错要单独成一类，不能混进正文。
 *
 * ⚠️ 下面两条是真转录里原样抠出来的（这台机器上一共 11 条）。
 */
class ApiErrorTest {

    private val ERR = """{"type":"assistant","isApiErrorMessage":true,"uuid":"e1","message":{"role":"assistant","content":[{"type":"text","text":"API Error: 529 Overloaded. This is a server-side issue, usually temporary — try again in a moment."}]}}"""

    private val REAL_TALK = """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"text","text":"API Error: 529 是什么意思？它表示上游过载。"}]}}"""

    @Test fun 报错单独成一类不进正文() {
        val items = Transcript.parse(sequenceOf(ERR))
        assertEquals(1, items.size)
        assertTrue("必须是 ApiError 而不是 AssistantText：" + items, items[0] is ChatItem.ApiError)
    }

    @Test fun 认标志不认文本() {
        // ⚠️ 关键用例：这条**正文里也有 "API Error: 529"**，但它是 Claude 在解释这个错误。
        // 如果靠匹配文本，用户一问「529 是什么意思」，回答就会被渲染成红色故障卡片。
        val items = Transcript.parse(sequenceOf(REAL_TALK))
        assertTrue("讨论报错的正常回复不能被当成故障：" + items, items[0] is ChatItem.AssistantText)
    }

    @Test fun 报错和正常回复能并存() {
        val items = Transcript.parse(sequenceOf(ERR, REAL_TALK))
        assertEquals(2, items.size)
        assertTrue(items[0] is ChatItem.ApiError)
        assertTrue(items[1] is ChatItem.AssistantText)
    }
}
