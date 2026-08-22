package app.yxi.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Paths] 是纯字符串运算，但**错了很难查**：markdown 里的相对图片路径解析歪了，
 * 界面上只表现为「图裂了」，看不出是路径拼错还是文件真没有。所以它有测试。
 */
class PathsTest {

    @Test fun 目录部分() {
        assertEquals("/a/b", Paths.dirOf("/a/b/c.md"))
        assertEquals("/", Paths.dirOf("/c.md"))
        assertEquals(".", Paths.dirOf("c.md"))
        assertEquals("/a", Paths.dirOf("/a/b/"))     // 末尾斜杠不算一层
    }

    @Test fun 扩展名() {
        assertEquals("md", Paths.extOf("/a/README.MD"))   // 大小写归一
        assertEquals("", Paths.extOf("/a/Makefile"))
        assertEquals("gz", Paths.extOf("/a/x.tar.gz"))
    }

    /** ⚠️ 这条就是验收标准里那个「带图的 README.md」靠的东西。 */
    @Test fun 相对路径解析() {
        assertEquals("/opt/p/docs/a.png", Paths.resolve("/opt/p", "docs/a.png"))
        assertEquals("/opt/p/a.png", Paths.resolve("/opt/p", "./a.png"))
        assertEquals("/opt/a.png", Paths.resolve("/opt/p", "../a.png"))
        assertEquals("/opt/x/a.png", Paths.resolve("/opt/p/docs", "../../x/a.png"))
        // 绝对路径原样（还是要归一）
        assertEquals("/a/b.png", Paths.resolve("/opt/p", "/a/b.png"))
        assertEquals("/a/b.png", Paths.resolve("/opt/p", "/a/./b.png"))
    }

    @Test fun 归一() {
        assertEquals("/a/b", Paths.normalize("/a//b/"))
        assertEquals("/", Paths.normalize("/a/.."))
        assertEquals("/", Paths.normalize("/"))
        // 根目录上面没有东西了，`..` 不该跑到根以外去
        assertEquals("/", Paths.normalize("/../.."))
    }

    @Test fun 面包屑() {
        assertEquals(
            listOf("/" to "/", "opt" to "/opt", "p" to "/opt/p"),
            Paths.crumbs("/opt/p"),
        )
        assertEquals(listOf("/" to "/"), Paths.crumbs("/"))
    }
}
