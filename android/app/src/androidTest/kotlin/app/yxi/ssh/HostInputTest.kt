package app.yxi.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⚠️ 这些不是假想的输入。用户在真机上就是报的「地址解析不了」，
 * 而他看着那个地址觉得完全正确 —— 因为全角句点和半角句点长得几乎一样。
 */
class HostInputTest {

    @Test fun 全角要变半角() {
        // 中文输入法打出来的：全角数字 + 全角句点
        assertEquals("216.36.108.147", HostInput.normalize("２１６．３６．１０８．１４７"))
        // 只有句点是全角（最常见：数字用半角、句点顺手打成全角）
        assertEquals("216.36.108.147", HostInput.normalize("216．36．108．147"))
        // 中文句号
        assertEquals("216.36.108.147", HostInput.normalize("216。36。108。147"))
    }

    @Test fun 空白和零宽都要清掉() {
        assertEquals("216.36.108.147", HostInput.normalize("  216.36.108.147\n"))
        assertEquals("216.36.108.147", HostInput.normalize("216.36. 108.147"))
        assertEquals("216.36.108.147", HostInput.normalize("216.36.108.147​"))
    }

    @Test fun 拆出端口和用户名() {
        assertEquals(HostInput.Parsed("216.36.108.147", 2222, null), HostInput.parse("216.36.108.147:2222"))
        assertEquals(HostInput.Parsed("h", 22, "root"), HostInput.parse("root@h:22"))
        assertEquals(HostInput.Parsed("h", null, "root"), HostInput.parse("ssh://root@h"))
        assertEquals(HostInput.Parsed("h", 22, "root"), HostInput.parse("ssh://root@h:22/some/path"))
        // 裸地址不动
        assertEquals(HostInput.Parsed("example.com", null, null), HostInput.parse("example.com"))
    }

    @Test fun IPv6不能被当成带端口() {
        // 裸 IPv6：冒号很多，不该把最后一段当端口
        assertEquals("::1", HostInput.parse("::1").host)
        assertNull(HostInput.parse("::1").port)
        // 带端口的写法
        assertEquals(HostInput.Parsed("::1", 2222, null), HostInput.parse("[::1]:2222"))
    }

    @Test fun 指出到底是哪个字符() {
        assertNull(HostInput.suspiciousChar("216.36.108.147"))
        assertNotNull(HostInput.suspiciousChar("216.36.108.147中"))
        assertEquals("「中」(U+4E2D)", HostInput.suspiciousChar("a中b"))
    }
}
