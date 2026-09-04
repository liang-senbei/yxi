package app.yxi.ui

/**
 * 给**装不可信 HTML 的 WebView** 用的出网围栏。
 *
 * ⚠️ **为什么光靠 `blockNetworkLoads` + `shouldInterceptRequest` 不够**（2026-09-04 红队 E2E 当场打出来的）：
 * 往实验室推了一张只干一件事的自检页，结果是——
 *
 * · 外链 `<img>`：挡住了（`blockNetworkLoads` 管的就是这个）
 * · `fetch` / XHR / `sendBeacon` / `<script src>`：挡住了（走 `shouldInterceptRequest`，给空应答，请求根本没发出去）
 * · **`new WebSocket('wss://…')` → `onopen` 触发了**——对方回了 HTTP 101，**这是一条真的、通向公网的连接**。
 *
 * WebSocket **不经过 `shouldInterceptRequest`**，也不受 `blockNetworkLoads` 管。而实验室页面是
 * **服务器上的 agent 写的**——「agent 被提示词注入」是我们明写的威胁模型。也就是说：一张实验室页
 * 就能把它在页面里能摸到的东西（页面自己的内容、用户在页面里输入的任何东西）从容外传，全程无声。
 *
 * 能同时管住 fetch / XHR / WebSocket / beacon 的机制只有一个：**CSP 的 `connect-src 'none'`**。
 * 所以这里在加载前把一条 CSP `<meta>` 塞进文档，配合原有的两道（拦导航、拦子资源）一起用。
 *
 * 放行的都是**出不去网**的来源：`data:` / `blob:` / 内联样式脚本 —— 实验室契约本来就要求单文件离线，
 * 正常页面一点不受影响。
 */
object WebFence {

    private const val CSP =
        "default-src 'none'; " +
            "img-src data: blob:; media-src data: blob:; font-src data:; " +
            "style-src 'unsafe-inline' data:; script-src 'unsafe-inline' 'unsafe-eval' data: blob:; " +
            "worker-src blob:; " +
            // ↓ 这条是重点：fetch / XHR / WebSocket / EventSource / sendBeacon 全归它管
            "connect-src 'none'; " +
            "form-action 'none'; base-uri 'none'; frame-src 'none'; object-src 'none'"

    private const val META = """<meta http-equiv="Content-Security-Policy" content="$CSP">"""

    /**
     * 把 CSP 塞进 [html]。
     *
     * ⚠️ **位置很讲究**：`<meta>` 形式的 CSP 只管**它之后**解析到的东西。所以插在
     * 「`<head>` 之后」和「第一个 `<script` 之前」**两者中靠前的那个**位置——
     * 后半句是防一手恶意页面故意把脚本写在 `<head>` 前面（那样先跑脚本、再生效 CSP，等于没围栏）。
     * 写在 `<html>` 前的 `<meta>` 会被解析器收进隐式 head，照样生效。
     */
    fun wrap(html: String): String {
        val headOpen = html.indexOf("<head", ignoreCase = true)
        val headEnd = if (headOpen < 0) -1 else html.indexOf('>', headOpen).let { if (it < 0) -1 else it + 1 }
        val script = html.indexOf("<script", ignoreCase = true)
        val at = when {
            headEnd >= 0 && (script < 0 || headEnd <= script) -> headEnd
            script >= 0 -> script
            // 既没 head 也没脚本：跳过开头的 doctype 再插，别把文档顶成怪异模式
            else -> html.indexOf('>', html.indexOf("<!", ignoreCase = true)).let { if (it in 0..200) it + 1 else 0 }
        }
        return html.substring(0, at) + META + html.substring(at)
    }
}
