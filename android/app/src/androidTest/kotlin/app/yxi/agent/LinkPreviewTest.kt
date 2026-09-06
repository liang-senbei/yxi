package app.yxi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ 这一组盯的是**「抓一个网址 = 访问它一次」的后果**，不是解析得漂不漂亮。
 * 漏抓只是少一张卡片；错抓可能废掉一个一次性凭据、或者戳到内网的某个东西。
 */
class LinkPreviewTest {

    /**
     * **带一次性码的地址一律不抓。**
     *
     * ⚠️ 不是假想：1.1.14 的 MCP 认证流程就会在转录里留下
     * `http://localhost:57970/callback?code=…`。抓一次可能就把那个码消耗掉了。
     */
    @Test fun 带凭据的网址不抓() {
        assertFalse(LinkPreview.previewable("http://localhost:57970/callback?code=abc123"))
        assertFalse(LinkPreview.previewable("https://example.com/cb?access_token=xyz"))
        assertFalse(LinkPreview.previewable("https://example.com/x?a=1&api_key=k"))
        assertFalse(LinkPreview.previewable("https://example.com/s?signature=zz"))
        // ⚠️ 大小写也要拦 —— 协议判断栽过一次（扫一扫的 HTTPS://，见 pilot 那轮审查）
        assertFalse(LinkPreview.previewable("HTTPS://example.com/cb?CODE=abc"))
    }

    /** **本机和内网一律不抓** —— 戳内网可能有副作用，而且那不是「网站」。 */
    @Test fun 本机和内网不抓() {
        listOf(
            "http://localhost:8080/x", "http://127.0.0.1/x", "http://0.0.0.0/x",
            "http://10.1.2.3/x", "http://192.168.1.1/x", "http://172.16.0.1/x",
            "http://172.31.255.1/x", "http://169.254.1.1/x",
            "http://100.111.242.66/x",          // Tailscale 的 CGNAT 段
            "http://box.local/x", "http://foo.localhost/x",
        ).forEach { assertFalse(it, LinkPreview.previewable(it)) }

        // 172.32 不在私网段里，是公网，该放行
        assertTrue(LinkPreview.previewable("http://172.32.0.1/x"))
        assertTrue(LinkPreview.previewable("https://veritickets.com/the-weeknd"))
    }

    /** 非 http/https 一律不抓（`javascript:` `intent:` `file:` 这些）。 */
    @Test fun 只认_http_和_https() {
        assertFalse(LinkPreview.previewable("javascript:alert(1)"))
        assertFalse(LinkPreview.previewable("intent://x#Intent;end"))
        assertFalse(LinkPreview.previewable("file:///etc/passwd"))
        assertFalse(LinkPreview.previewable("yxi-file:///opt/workspace/a.md"))
    }

    /** ⚠️ `user:pass@host` 这种要按**真实主机**判，不能被前面那段骗过去。 */
    @Test fun 用户名密码不能骗过主机判断() {
        assertFalse(LinkPreview.previewable("http://example.com@127.0.0.1/x"))
        assertFalse(LinkPreview.previewable("http://a:b@192.168.0.5:8080/x"))
    }

    @Test fun 从正文里挑网址() {
        val 正文 = "看这个 https://veritickets.com/tw 还有 http://localhost:1/callback?code=x 以及 https://b.com/p。"
        val 结果 = LinkPreview.urlsIn(正文)
        // localhost 那条被闸门拦掉；结尾的句号不算网址的一部分
        assertEquals(listOf("https://veritickets.com/tw", "https://b.com/p"), 结果)
    }

    /** og 标签：属性顺序和引号都不固定，两种排法都得认。 */
    @Test fun 认得出_og_标签() {
        val a = LinkPreview.parse(
            "https://veritickets.com/tw",
            """<html><head>
               |<meta property="og:title" content="The Weeknd《2026 Asia Tour》香港站">
               |<meta content='https://cdn.x/p.jpg' property='og:image'>
               |<meta property="og:site_name" content="Veritickets">
               |</head></html>""".trimMargin(),
        )
        assertEquals("The Weeknd《2026 Asia Tour》香港站", a?.title)
        assertEquals("https://cdn.x/p.jpg", a?.image)
        assertEquals("Veritickets", a?.site)
    }

    /** 没有 og 就退回 `<title>`；标题和图都没有就**不摆卡片**（只有域名的卡片不如不显示）。 */
    @Test fun 没有_og_时退回标题_都没有就不摆卡() {
        val 只有标题 = LinkPreview.parse("https://a.com/x", "<html><head><title>普通页面</title></head>")
        assertEquals("普通页面", 只有标题?.title)
        assertEquals("a.com", 只有标题?.site)

        assertNull(LinkPreview.parse("https://a.com/x", "<html><head></head><body>正文</body></html>"))
    }

    /** HTML 实体要还原，不然标题里全是 `&amp;`。 */
    @Test fun 还原_html_实体() {
        val c = LinkPreview.parse("https://a.com", """<head><title>A &amp; B &quot;C&quot;</title></head>""")
        assertEquals("A & B \"C\"", c?.title)
    }
}
