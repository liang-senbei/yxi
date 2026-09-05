package app.yxi

import app.yxi.agent.Connect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「连接」里新加的微信一行：状态段 `__WECHAT__` 的三种结果，以及分类不漏项。 */
class ConnectWechatTest {

    private fun out(wechat: String) = "__GH__\nNO_GH\n__MCP__\nNO_CLAUDE\n__CLAUDE__\nNO_CLAUDE\n__CODEX__\nNO_CODEX\n__TMUX__\n__WECHAT__\n$wechat\n__END__"
    private val wx = Connect.CATALOG.first { it.key == "wechat" }

    @Test fun 密钥在_已连接() {
        assertEquals(Connect.State.CONNECTED, Connect.parseStatus(out("KEY_OK")).of(wx))
    }

    @Test fun 电脑不可达_连不上() {
        assertEquals(Connect.State.FAILED, Connect.parseStatus(out("NO_LAPTOP")).of(wx))
    }

    @Test fun 可达没密钥_要登录() {
        assertEquals(Connect.State.NEEDS_AUTH, Connect.parseStatus(out("")).of(wx))
    }

    @Test fun 老输出没有微信段_不崩且tmux判断不受影响() {
        val s = Connect.parseStatus("__GH__\nNO_GH\n__MCP__\nNO_CLAUDE\n__CLAUDE__\nNO_CLAUDE\n__CODEX__\nNO_CODEX\n__TMUX__\nNO_TMUX\n__END__")
        assertEquals(false, s.tmuxInstalled)
        assertEquals(Connect.State.ABSENT, s.of(wx))     // 没有微信段 = 不知道，不能报成「要提取」
    }

    @Test fun 分类_除agent登录外每项都有类() {
        Connect.CATALOG.filter { it.kind != Connect.Kind.AGENT }.forEach {
            assertTrue("${it.key} 没分类", it.cat.isNotBlank())
        }
        assertEquals("本地", wx.cat)
    }
}
