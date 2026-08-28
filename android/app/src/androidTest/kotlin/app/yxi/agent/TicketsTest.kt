package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单会被塞进一条 shell 命令追加进文件 —— 用户在工单里写什么字符都可能。
 * ⚠️ 转义错了不是显示难看，是**写坏文件**甚至**被当命令执行**。
 */
class TicketsTest {

    @Test fun 单引号要转义() {
        assertEquals("'abc'", Tickets.shellSingleQuote("abc"))
        // 单引号是单引号字符串里唯一的危险字符：闭合 → 插一个转义的 ' → 再开
        assertEquals("""'it'\''s'""", Tickets.shellSingleQuote("it's"))
    }

    @Test fun 别的危险字符靠单引号本身挡住() {
        // $ ` \ ; | & 换行 在单引号里都是字面量，不该被改写
        val nasty = "\$HOME `rm -rf /` ; echo x | tee \\ && ok\n换行"
        val q = Tickets.shellSingleQuote(nasty)
        assertTrue(q.startsWith("'") && q.endsWith("'"))
        assertEquals(nasty, q.substring(1, q.length - 1))   // 原样保留
    }

    @Test fun 解析jsonl跳过坏行() {
        val raw = """
            {"at":100,"text":"第一条","version":"0.9.20(64)"}
            这不是 json
            {"at":200,"text":"第二条"}
            {"at":300,"text":""}
        """.trimIndent()
        val ts = Tickets.parse(raw)
        assertEquals(2, ts.size)                    // 坏行和空文本都不要
        assertEquals("第一条", ts[0].text)
        assertEquals("0.9.20(64)", ts[0].version)
        assertEquals(200L, ts[1].at)
    }
}
