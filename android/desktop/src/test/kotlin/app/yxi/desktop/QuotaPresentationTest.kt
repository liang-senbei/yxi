package app.yxi.desktop

import app.yxi.agent.AccountApi
import org.json.JSONObject
import kotlin.test.*

class QuotaPresentationTest {
    private fun summary(quota: String?) = quotaSummary(AccountApi.parseMe(JSONObject(if (quota == null) "{}" else """{"quota":$quota}""")))
    @Test fun `missing and null data never imply unlimited membership`() {
        listOf(null, "null", "{}", """{"unlimited":false,"remaining":null}""").forEach {
            assertEquals("额度暂不可用，请刷新后查看", summary(it))
        }
    }
    @Test fun `only explicit unlimited flag grants unlimited display`() {
        assertEquals("不限", summary("""{"unlimited":true,"remaining":null}"""))
        assertEquals("额度暂不可用，请刷新后查看", summary("""{"unlimited":"true","remaining":null}"""))
    }
    @Test fun `zero remaining and unknown total are distinct`() {
        assertEquals("本期额度已用完（共 3 次）", summary("""{"unlimited":false,"remaining":0,"limit":3}"""))
        assertEquals("还能改 2 次", summary("""{"remaining":2}"""))
    }
    @Test fun `malformed or negative counts never become a zero quota`() {
        listOf("-1", "1.5", "\"bad\"", "2147483648").forEach {
            assertEquals("额度暂不可用，请刷新后查看", summary("""{"remaining":$it}"""))
        }
    }
}
