package app.yxi

import app.yxi.agent.LabPrompts
import app.yxi.agent.LabRemote
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三个按钮发出去的话：命令要对、id 要带、不带 `$`（要经 tmux send-keys） */
class LabPromptsTest {
    private val item = LabRemote.Item("a-1", "Yxi 架构图", "html", "a-1.html", "说明", by = "Claude", at = 1L, aspect = "16:10", group = "架构图")

    @Test fun 查明_默认与补充() {
        val d = LabPrompts.verify("")
        assertTrue(d.contains("yxi-lab add") && d.contains("--group 架构图") && !d.contains("补充要求") && !d.contains("$"))
        assertTrue(LabPrompts.verify("只画后端").contains("补充要求：只画后端"))
    }

    @Test fun 画图_没描述就默认依赖图() {
        assertTrue(LabPrompts.draw("").contains("依赖图"))
        assertTrue(LabPrompts.draw("登录流程时序图").contains("描述：登录流程时序图"))
    }

    @Test fun 更新_带id和原文件() {
        val u = LabPrompts.update(item, "")
        assertTrue(u.contains("yxi-lab update a-1") && u.contains("~/.yxi/lab/a-1.html") && u.contains("16:10") && !u.contains("$"))
    }
}
