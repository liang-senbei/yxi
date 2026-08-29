package app.yxi

import app.yxi.agent.Attachments.Staged
import app.yxi.agent.Attachments.renumber
import app.yxi.agent.Attachments.header
import org.junit.Assert.*
import org.junit.Test

class AttachmentsTest {

    /**
     * 用户报的：第一张没传完就点第二张，第二张会失败。
     * 除了 SFTP 那把锁（那条在 SshSession 里修），这里还有一个**必然**的连带错：
     * 编号是 `staged.count{} + 1` 算的，而 staged 只在**上传成功后**才更新 ——
     * 并发时两张算出来的都是 1。
     */
    @Test fun 并发上传不会都叫图片1() {
        // 模拟：两张都在 staged 为空时算出了 index=1
        val a = Staged("图片1", "/tmp/a.png", true)
        val b = Staged("图片1", "/tmp/b.png", true)
        val out = renumber(listOf(a, b))
        assertEquals(listOf("图片1", "图片2"), out.map { it.label })
        // ⚠️ 标签重了 header 就废了：Claude 拿到两条同名映射，不知道该用哪条
        assertEquals(2, header(out).lines().size)
        assertTrue("/tmp/a.png" in header(out) && "/tmp/b.png" in header(out))
    }

    @Test fun 图片和附件各编各的() {
        val out = renumber(
            listOf(
                Staged("x", "/a.png", true),
                Staged("x", "/b.txt", false),
                Staged("x", "/c.png", true),
                Staged("x", "/d.txt", false),
            ),
        )
        assertEquals(listOf("图片1", "附件1", "图片2", "附件2"), out.map { it.label })
    }

    /** 路径不能被改动 —— 编号只管标签。 */
    @Test fun 重编号不碰路径() {
        val src = listOf(Staged("图片9", "/keep/me.png", true))
        assertEquals("/keep/me.png", renumber(src).single().remotePath)
    }

    @Test fun 空列表不炸() {
        assertEquals(emptyList<Staged>(), renumber(emptyList()))
    }
}
