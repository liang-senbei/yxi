package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 开机自启写进注册表的那条值。
 *
 * ⚠️ 为什么值得单测：这条**只有在真 Windows 上才看得出对错**（写错了表现是「开机不自启」，
 * 没有任何报错），而我们手上没有 Windows。把 `.reg` 正文抽成纯函数，至少转义这一半能在这儿钉死。
 */
class AutostartRegTest {

    private fun valueLine(s: String) = s.lines().first { it.startsWith("\"Yxi\"=") }

    @Test fun 路径带空格时值里要有引号() {
        // 用户名带空格是常态（C:\Users\Li Ming\…）。值不带引号的话 Windows 会去试 C:\Users\Li.exe
        val v = valueLine(autostartReg("""C:\Users\Li Ming\AppData\Local\Yxi\Yxi.exe"""))
        // .reg 文件里这一行长这样（\" = 值里真的有一个引号，\\ = 路径里一个反斜杠）：
        //   "Yxi"="\"C:\\Users\\Li Ming\\AppData\\Local\\Yxi\\Yxi.exe\""
        // 导进注册表之后，值 = "C:\Users\Li Ming\AppData\Local\Yxi\Yxi.exe"（连引号一起，正是 Run 键要的）
        val want = "\"Yxi\"=\"\\\"C:\\\\Users\\\\Li Ming\\\\AppData\\\\Local\\\\Yxi\\\\Yxi.exe\\\"\""
        assertEquals(want, v)
    }

    @Test fun 反斜杠要成对() {
        // .reg 里 \ 必须写成 \\，否则 \U \A 之类会被当转义
        val v = valueLine(autostartReg("""C:\Yxi\Yxi.exe"""))
        assertTrue(v.contains("""C:\\Yxi\\Yxi.exe"""), v)
        assertTrue(!v.contains("""C:\Yxi"""), "单个反斜杠漏出来了：$v")
    }

    @Test fun 头两行是注册表文件的固定格式() {
        val l = autostartReg("""C:\Yxi\Yxi.exe""").lines()
        assertEquals("Windows Registry Editor Version 5.00", l[0].trim())
        assertTrue(l.any { it.trim() == """[HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run]""" }, l.toString())
    }

    @Test fun 换行是CRLF() {
        // reg import 对 LF 的 .reg 有时会挑食，写成 Windows 的换行最省事
        assertTrue(autostartReg("""C:\a.exe""").contains("\r\n"))
    }
}
