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

    @Test fun `unrelated path or method cannot become an authorization`() {
        assertTrue(p("GET /other?code=c&state=S HTTP/1.1").isEmpty())
        assertTrue(p("POST /callback?code=c&state=S HTTP/1.1").isEmpty())
    }

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

/**
 * 浏览器回调页显示哪句话。⚠️ 这几条是 2026-09-08 在 Xvfb 上**真跑出来的 bug** 转成的测试:
 * 拿一个 state 不对的回调打进来,页面写「登录成功」而应用红字写「校验失败」—— 两个界面各说各话。
 * 编译过、逻辑自查、单看代码都发现不了,只有跑起来才看得见;看见之后就该钉成测试。
 */
class CallbackPageTest {
    private fun page(kv: Map<String, String>, want: String = "S") = MeAuth.callbackPage(kv, want)

    @kotlin.test.Test fun `callback does not claim token exchange succeeded`() {
        val text = page(mapOf("code" to "c", "state" to "S"))
        kotlin.test.assertTrue(text.startsWith("授权已收到"))
        kotlin.test.assertFalse(text.contains("登录成功"))
    }

    @kotlin.test.Test fun `provider error cannot inject markup`() {
        val text = page(mapOf("error" to "denied", "error_description" to "<script>alert(1)</script>"))
        kotlin.test.assertFalse(text.contains("<script>"))
        kotlin.test.assertTrue(text.contains("&lt;script&gt;"))
    }

    @kotlin.test.Test fun `state 不对不能说成功`() {
        val t = page(mapOf("code" to "c", "state" to "别人的"))
        kotlin.test.assertFalse(t.contains("成功"))
        kotlin.test.assertTrue(t.contains("不是 Yxi 这次登录发起的"))
    }

    @kotlin.test.Test fun `没有 state 也不能说成功`() =
        kotlin.test.assertFalse(page(mapOf("code" to "c")).contains("成功"))

    @kotlin.test.Test fun `用户拒绝授权`() =
        kotlin.test.assertTrue(page(mapOf("error" to "access_denied", "error_description" to "用户拒绝")).contains("用户拒绝"))

    @kotlin.test.Test fun `state 对但没给码`() =
        kotlin.test.assertTrue(page(mapOf("state" to "S")).contains("没给授权码"))
}
