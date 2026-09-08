package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 登录回调的解析。**这段原来埋在 socket 里**,只有真跑一遍浏览器登录才碰得到 ——
 * 而它恰恰是最容易错的地方:URL 解码、参数缺失、浏览器发回来的是错误而不是授权码。
 */
class MeAuthTest {
    private fun p(line: String) = MeAuth.parseCallback(line)

    @Test fun `正常回调`() {
        val kv = p("GET /callback?code=abc123&state=xyz HTTP/1.1")
        assertEquals("abc123", kv["code"]); assertEquals("xyz", kv["state"])
    }

    /** state 里有 URL-safe base64 的 `-` `_`,还可能被浏览器编码过 */
    @Test fun `值要 URL 解码`() {
        val kv = p("GET /callback?code=a%2Fb%2Bc&state=x-y_z HTTP/1.1")
        assertEquals("a/b+c", kv["code"]); assertEquals("x-y_z", kv["state"])
    }

    /** 用户在授权页点了「拒绝」:回来的是 error,不是 code —— 不能当成功 */
    @Test fun `拒绝授权`() {
        val kv = p("GET /callback?error=access_denied&error_description=User%20denied HTTP/1.1")
        assertEquals("access_denied", kv["error"])
        assertEquals("User denied", kv["error_description"])
        assertTrue(kv["code"].isNullOrEmpty())
    }

    /** 没有查询串 / 根路径的探测请求:不能崩,也不能解出个空 code 当成功 */
    @Test fun `没有参数`() {
        assertTrue(p("GET /callback HTTP/1.1").isEmpty())
        assertTrue(p("GET / HTTP/1.1").isEmpty())
        assertTrue(p("").isEmpty())
    }

    /** 畸形串(没有等号、只有等号)不能让整个解析炸掉 —— 这个端口是对本机开放的,谁都能发一行过来 */
    @Test fun `畸形串不炸`() {
        val kv = p("GET /callback?justkey&=noname&code=ok HTTP/1.1")
        assertEquals("ok", kv["code"])
    }
}
