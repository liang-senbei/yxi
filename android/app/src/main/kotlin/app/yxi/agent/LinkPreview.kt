package app.yxi.agent

import android.content.Context
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession

/**
 * 对话里那些网址的预览卡片（老板 2026-09-06：「搜索链接比如网站和视频可以支持渲染出来」）。
 *
 * ⚠️⚠️ **抓一个网址 = 访问它一次**，所以这件事不能见网址就做：
 * 对话里的网址**不全是「网站」**。1.1.14 的 MCP 认证流程就会在转录里留下
 * `http://localhost:57970/callback?code=…` 这种**带一次性凭据**的地址；还有报错里的
 * 内网地址、agent 贴的私有链接。自动全抓一遍等于把这些都访问了 ——
 * 一次性码可能被消耗掉、内网地址可能触发副作用。
 * 所以：**默认「点了才抓」**（[Prefs.MANUAL]），能抓的也先过 [previewable] 这道闸。
 *
 * ⚠️ **抓取走服务器（SSH 里 curl），不走手机**：跟 Yxi 的模型一致（手机只是遥控器），
 * 而且手机不用去碰那些站点。
 */
object LinkPreview {

    /** 什么时候抓。⚠️ 默认 [MANUAL] —— 见类注释里那条一次性凭据的坑。 */
    object Prefs {
        const val OFF = "off"
        const val MANUAL = "manual"
        const val AUTO = "auto"

        private const val KEY = "linkpreview.mode"
        private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

        fun mode(ctx: Context): String = p(ctx).getString(KEY, MANUAL) ?: MANUAL
        fun setMode(ctx: Context, v: String) = p(ctx).edit().putString(KEY, v).apply()
    }

    /** 网址里出现这些参数名就**一律不抓** —— 多半是一次性码 / 令牌，抓一次可能就废了。 */
    private val SECRETISH = listOf(
        "code=", "token=", "secret=", "password=", "passwd=", "apikey=", "api_key=",
        "access_token=", "id_token=", "refresh_token=", "sig=", "signature=", "auth=",
    )

    /**
     * 这个网址能不能抓。**故意认得保守** —— 漏抓只是少一张卡片，错抓可能是废掉一个凭据
     * 或者戳到内网的某个东西。
     *
     * 拦掉：非 http/https · 本机和内网段（含 Tailscale 的 100.64/10） · 带疑似凭据的参数。
     */
    fun previewable(url: String): Boolean {
        val u = url.trim()
        val lower = u.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (SECRETISH.any { it in lower }) return false

        val host = lower.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')            // 去掉 user:pass@
            .substringBefore(':')               // 去掉端口
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (host == "0.0.0.0" || host == "[::1]" || host == "::1") return false

        val ip = host.split('.').mapNotNull { it.toIntOrNull() }
        if (ip.size == 4) {
            val (a, b) = ip[0] to ip[1]
            // 127/8 本机 · 10/8 · 172.16–31 · 192.168 私网 · 169.254 链路本地
            // · 100.64–127 CGNAT（Tailscale 用的就是这一段）
            if (a == 127 || a == 10) return false
            if (a == 172 && b in 16..31) return false
            if (a == 192 && b == 168) return false
            if (a == 169 && b == 254) return false
            if (a == 100 && b in 64..127) return false
        }
        return true
    }

    /** 抓回来的那点东西。任何一项都可能没有 —— 拿不到就别显示那一行。 */
    data class Card(val url: String, val site: String, val title: String, val image: String)

    /**
     * 去服务器上抓这个网址的 og 标签。
     *
     * ⚠️ **限时限量**：`--max-time 8` + `head -c 200k`。网页正文可以很大，而我们只要 `<head>`；
     * 不限的话一个大页面能把这条 SSH 通道占很久（[SshSession.exec] 那边没有超时兜底，见 #277）。
     * ⚠️ `-L` 跟跳转，但 `--max-redirs 3` 防跳转环。
     * ⚠️ 插值只走 [Shell.q]（SECURITY.md）。
     */
    suspend fun fetch(ssh: SshSession, url: String): Result {
        if (!previewable(url)) return Result.Blocked
        val q = Shell.q(url)
        // ⚠️ **让命令自报家门**：`command -v curl` 没有时什么都不打印，而 exec 连接断了
        //    同样返回空串 —— 靠「空」分不出「没装 curl」「站点没回」「网断了」（#276 同款）。
        //    干净的 Ubuntu 服务器**真的可能没有 curl**（装机测试踩过，不是假想）。
        val out = runCatching {
            ssh.exec(
                "if ! command -v curl >/dev/null 2>&1; then echo __NOCURL__; else " +
                    "curl -sL --max-time 8 --max-redirs 3 " +
                    "-A 'Mozilla/5.0 (compatible; Yxi/1.0; +https://yxi.keuury.com)' " +
                    "$q 2>/dev/null | head -c 200000; fi"
            )
        }.getOrNull().orEmpty()

        if (out.startsWith("__NOCURL__")) return Result.NoCurl
        if (out.isBlank()) return Result.Failed
        return parse(url, out)?.let { Result.Ok(it) } ?: Result.Failed
    }

    /** 抓取结果。⚠️ 分清「拿到了」「站点没给」「这台机器没 curl」—— 三种要说不同的话。 */
    sealed class Result {
        data class Ok(val card: Card) : Result()
        /** 闸门拦下（内网 / 带凭据 / 非 http）。界面上**什么都不显示** —— 解释反而引人去点。 */
        object Blocked : Result()
        /** 抓了但没结果（站点没回、没有 og、超时）。 */
        object Failed : Result()
        /** 这台机器上没有 curl —— 装一个就能用，得告诉用户，不然他以为功能坏了。 */
        object NoCurl : Result()
    }

    /**
     * 从 HTML 的 `<head>` 里挑 og 标签。
     *
     * ⚠️ **不写 HTML 解析器**，只按标签名找属性 —— 属性顺序、单双引号、自闭合都得容得下，
     * 所以是两条宽松的正则各扫一遍，而不是「property 必须在 content 前面」。
     */
    internal fun parse(url: String, html: String): Card? {
        fun meta(vararg names: String): String {
            for (n in names) {
                // property/name 在前 或 content 在前，两种都认
                val a = Regex(
                    """<meta[^>]+(?:property|name)\s*=\s*["']$n["'][^>]*?content\s*=\s*["']([^"']*)["']""",
                    RegexOption.IGNORE_CASE,
                ).find(html)?.groupValues?.get(1)
                val b = a ?: Regex(
                    """<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*?(?:property|name)\s*=\s*["']$n["']""",
                    RegexOption.IGNORE_CASE,
                ).find(html)?.groupValues?.get(1)
                if (!b.isNullOrBlank()) return unescape(b.trim())
            }
            return ""
        }

        val title = meta("og:title", "twitter:title").ifBlank {
            Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.trim()?.let { unescape(it) }.orEmpty()
        }
        val image = meta("og:image", "twitter:image", "og:image:url")
        val site = meta("og:site_name").ifBlank {
            url.lowercase().removePrefix("https://").removePrefix("http://")
                .substringBefore('/').removePrefix("www.")
        }
        // 标题和图都没有就别摆卡片了 —— 一张只有域名的卡片不如不显示
        if (title.isBlank() && image.isBlank()) return null
        return Card(url, site.take(60), title.take(140), image.take(500))
    }

    /** 只处理最常见那几个实体。⚠️ 不引 HTML 解析库，为这点事不值得。 */
    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ")

    /** 一段文字里的网址（给对话页找「这条消息里有哪些可预览的链接」用）。 */
    private val URL_RE = Regex("""https?://[^\s<>"'）)\]】，。；]+""")

    fun urlsIn(text: String): List<String> =
        URL_RE.findAll(text).map { it.value.trimEnd('.', ',', '，', '。', '、') }
            .filter { previewable(it) }.distinct().take(3).toList()
}
