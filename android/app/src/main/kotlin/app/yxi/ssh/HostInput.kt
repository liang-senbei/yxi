package app.yxi.ssh

/**
 * 把用户在「地址」栏里敲进来的东西弄成能连的形式。
 *
 * ⚠️ **这不是吹毛求疵，是真的连不上。** 手机上输地址的坑比电脑上多得多：
 *   · 中文输入法打出来的是**全角**：`２１６．３６．１０８．１４７` —— 长得几乎一样，DNS 直接失败
 *   · 从别处复制过来常常带 `root@`、`:22`、`ssh://`，甚至前后有空格或换行
 *   · 有些输入法会插**零宽字符**，肉眼完全看不见
 *
 * 报错「地址解析不了」的时候用户是懵的：他明明看着那个地址是对的。
 * 所以能自动纠的就纠掉，纠不掉的要**把那个字符指出来**。
 */
object HostInput {

    data class Parsed(val host: String, val port: Int?, val user: String?)

    /** 全角 → 半角；顺带清掉零宽字符和所有空白。 */
    fun normalize(raw: String): String = buildString {
        for (c in raw) {
            when {
                c == '　' -> Unit                       // 全角空格
                c.isWhitespace() -> Unit
                c == '​' || c == '‌' || c == '‍' || c == '﻿' -> Unit  // 零宽
                c == '．' || c == '。' || c == '｡' -> append('.')
                c == '：' -> append(':')
                c == '＠' -> append('@')
                c == '－' || c == '—' -> append('-')
                c in '！'..'～' -> append(c - 0xFEE0)        // 全角 ASCII 区整段平移
                else -> append(c)
            }
        }
    }

    /**
     * 从一坨输入里拆出 host / port / user。
     * 支持 `ssh://user@host:port`、`user@host:port`、`host:port`、裸 host。
     * IPv6 用 `[::1]:22` 这种写法。
     */
    fun parse(raw: String): Parsed {
        var s = normalize(raw)
        s = s.removePrefix("ssh://").removePrefix("SSH://")
        s = s.substringBefore('/')                 // 有人会把路径也带上
        var user: String? = null
        if ('@' in s) {
            user = s.substringBeforeLast('@').ifBlank { null }
            s = s.substringAfterLast('@')
        }
        var port: Int? = null
        if (s.startsWith("[")) {                   // IPv6 字面量
            val close = s.indexOf(']')
            if (close > 0) {
                val after = s.substring(close + 1)
                if (after.startsWith(":")) port = after.drop(1).toIntOrNull()
                s = s.substring(1, close)
            }
        } else if (s.count { it == ':' } == 1) {   // 只有一个冒号才当端口，多个说明是裸 IPv6
            val p = s.substringAfterLast(':').toIntOrNull()
            if (p != null && p in 1..65535) { port = p; s = s.substringBeforeLast(':') }
        }
        return Parsed(s, port, user)
    }

    /**
     * 还有没有解析不了的字符？有就返回**具体是哪个**。
     * 只说「地址解析不了」等于没说 —— 用户看着那个地址觉得它是对的。
     */
    fun suspiciousChar(host: String): String? {
        val bad = host.firstOrNull { it.code > 127 } ?: return null
        return "「$bad」(U+%04X)".format(bad.code)
    }
}
