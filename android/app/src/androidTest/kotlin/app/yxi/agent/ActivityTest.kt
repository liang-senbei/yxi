package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「上次对话」时间的来源。⚠️ 用户报过：看板上写着「14 小时前 / 1 天前」，
 * 而那些会话**刚刚还在聊**。实测 `tmux session_activity` 会陈旧到离谱
 * （`claude_desktop` 写着 2 天前，转录一分钟前还在写），所以要拿转录 mtime 兜底。
 */
class ActivityTest {

    @Test fun 解析转录时间表() {
        val raw = "-root-src-workspace-Yxi\t1787762976\n" +
            "-root-src-workspace-mail\t1787762700\n" +
            "坏行没有制表符\n" +
            "-x\tnot-a-number\n" +
            "-y\t0\n"                      // 0 = 没有，不进表
        val m = SessionProbe.parseTranscriptTimes(raw)
        assertEquals(2, m.size)
        assertEquals(1787762976L, m["-root-src-workspace-Yxi"])
        assertEquals(1787762700L, m["-root-src-workspace-mail"])
    }

    @Test fun 转录比tmux新就用转录的() {
        val trs = mapOf("-root-src-workspace-claude-desktop" to 1787762900L)
        // 真实数据：tmux 说 2 天前，转录说 1 分钟前 —— 必须取转录那个
        assertEquals(
            1787762900L,
            SessionProbe.lastActivityOf(1787582123L, "/root/src/workspace/claude_desktop", trs),
        )
    }

    @Test fun 读不到转录就退回tmux() {
        // 不是 Claude 会话 / 目录对不上：退回 tmux 的时间，别显示成 1970
        assertEquals(1787582123L, SessionProbe.lastActivityOf(1787582123L, "/some/other/dir", emptyMap()))
    }

    @Test fun tmux比转录新就用tmux的() {
        val trs = mapOf("-a" to 100L)
        assertEquals(999L, SessionProbe.lastActivityOf(999L, "/a", trs))
    }
}
