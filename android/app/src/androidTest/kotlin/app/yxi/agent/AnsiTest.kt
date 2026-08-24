package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 命令输出里的 ANSI 转义要洗掉。
 * ⚠️ 下面这条是真会话里原样抓的（`/model` 的回显）。
 */
class AnsiTest {

    @Test fun 洗掉加粗开关() {
        assertEquals(
            "Set model to Fable 5 for this session only",
            Transcript.clean("Set model to \u001B[1mFable 5\u001B[22m for this session only"),
        )
    }

    @Test fun ESC被吃掉只剩方括号也要洗() {
        // ⚠️ 转录里两种都见过（有的行 ESC 没了只剩 `[1m`），所以正则里 ESC 是可选的
        assertEquals(
            "Set model to Opus 5 (1M context) for this session only",
            Transcript.clean("Set model to [1mOpus 5 (1M context)[22m for this session only"),
        )
    }

    @Test fun 颜色和光标序列一并洗() {
        assertEquals("红字", Transcript.clean("\u001B[31m红字\u001B[0m"))
        assertEquals("abc", Transcript.clean("a\u001B[2Kb\u001B[1;32mc\u001B[m"))
    }

    @Test fun 普通文本一个字不动() {
        // ⚠️ 防误伤：正文里的方括号引用不能被当成转义序列
        assertEquals("正常的一句话 [1] 引用", Transcript.clean("正常的一句话 [1] 引用"))
        assertEquals("", Transcript.clean(""))
    }
}
