package app.yxi

import app.yxi.agent.MarkdownFix
import org.junit.Assert.assertEquals
import org.junit.Test

/** 样本来自 unitree_rl_mjlab 的 thesis.md / README_zh.md（用户报的那份）。 */
class MarkdownFixTest {
    @Test fun img标签转成markdown图() {
        assertEquals("![系统信号链](figs/fig_chain.png)",
            MarkdownFix.apply("""<img src="figs/fig_chain.png" alt="系统信号链" width="960" />"""))
        assertEquals("![](doc/gif/go2-velocity-real.gif)", MarkdownFix.apply("""<img src="doc/gif/go2-velocity-real.gif" width="300"/>"""))
    }
    @Test fun 居中壳剥掉_粗体换成星号() {
        assertEquals("**图 4-2　对比**", MarkdownFix.apply("""<p align="center"><b>图 4-2　对比</b></p>"""))
    }
    @Test fun 没有html原样返回() {
        val t = "# 标题\n\n正文 a < b"
        assertEquals(t, MarkdownFix.apply(t))
    }
}
