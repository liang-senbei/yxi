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

        var host = lower.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')            // 去掉 user:pass@
        // ⚠️ **IPv6 要先摘方括号再切端口** —— 原来先 `substringBefore(':')`，
        //    `[::ffff:127.0.0.1]` 整个塌成 `"["`，下面所有判断全落空（审查实测能打到本机）。
        host = if (host.startsWith("[")) host.substringAfter('[').substringBefore(']')
        else host.substringBefore(':')
        // ⚠️ 百分号编码（`%6cocalhost`）和结尾的点（`localhost.`）都能绕过字面比较
        host = runCatching { java.net.URLDecoder.decode(host, "UTF-8") }.getOrDefault(host)
            .trimEnd('.').trim()
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false

        // IPv6：只要能解析成 IPv6，就交给下面统一的「是不是内网」判断
        if (':' in host) return !isPrivate(host)

        // ⚠️ **十进制 / 八进制 / 十六进制 / 短写都要认**：`2130706433`、`0177.0.0.1`、
        //    `0x7f.0.0.1`、`127.1`、`0` 都会被 curl 解析成 127.0.0.1（审查逐个实测过）。
        //    原来用 `toIntOrNull`（只认十进制）+ 要求正好 4 段，这些全漏了。
        ipv4Of(host)?.let { return !isPrivateV4(it) }
        // 不是字面 IP 就是域名。⚠️ **域名解析到内网这一层字符串判不了** ——
        //    真正的把关在 [fetch]：那边禁跳转 + 用 `getent hosts` 按解析出的 IP 再判一次。
        return true
    }

    /** 从网址里取出主机名（跟 [previewable] 用同一套规范化：去 user@、方括号、端口、尾点、百分号）。 */
    internal fun hostOf(url: String): String {
        var h = url.trim().lowercase().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')
        h = if (h.startsWith("[")) h.substringAfter('[').substringBefore(']') else h.substringBefore(':')
        return runCatching { java.net.URLDecoder.decode(h, "UTF-8") }.getOrDefault(h).trimEnd('.').trim()
    }

    /** 把各种写法的 IPv4 归一成 4 个字节。认十进制 / 八进制（前导 0）/ 十六进制（0x）/ 短写。 */
    internal fun ipv4Of(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val nums = parts.map { p ->
            val v = when {
                p.startsWith("0x") -> p.drop(2).toLongOrNull(16)
                p.length > 1 && p.startsWith("0") -> p.drop(1).toLongOrNull(8)
                else -> p.toLongOrNull()
            } ?: return null
            if (v < 0) return null
            v
        }
        // 短写：最后一段吃掉剩下的字节（`127.1` = 127.0.0.1、`2130706433` = 整个地址）
        val last = nums.last()
        if (last >= (1L shl ((5 - nums.size) * 8))) return null
        val out = IntArray(4)
        for (i in 0 until nums.size - 1) {
            if (nums[i] > 255) return null
            out[i] = nums[i].toInt()
        }
        var rest = last
        for (i in 3 downTo nums.size - 1) { out[i] = (rest and 0xFF).toInt(); rest = rest shr 8 }
        return out
    }

    /** 127/8 · 10/8 · 172.16–31 · 192.168 · 169.254 · 100.64–127（Tailscale）· 0.x · 多播/保留 */
    internal fun isPrivateV4(ip: IntArray): Boolean = when {
        ip[0] == 0 || ip[0] == 127 || ip[0] == 10 -> true
        ip[0] == 172 && ip[1] in 16..31 -> true
        ip[0] == 192 && ip[1] == 168 -> true
        ip[0] == 169 && ip[1] == 254 -> true
        ip[0] == 100 && ip[1] in 64..127 -> true
        ip[0] >= 224 -> true
        else -> false
    }

    /** IPv6：本机、链路本地、唯一本地，以及内嵌 IPv4 的 `::ffff:a.b.c.d`。 */
    internal fun isPrivate(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "::1" || h == "::") return true
        if (h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd")) return true
        h.substringAfterLast(':').takeIf { '.' in it }?.let { v4 ->
            ipv4Of(v4)?.let { return isPrivateV4(it) }     // ::ffff:127.0.0.1
        }
        return false
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
        // ⚠️⚠️ **不跟跳转（`--max-redirs 0`）。** 原来开着 `-L`：闸门只看**你给的那个地址**，
        //    管不住它跳到哪 —— 一个完全合规的公网链接 302 一下就能指到
        //    `169.254.169.254`（云厂商元数据，上面有这台机器的凭据）或任何内网地址。
        //    审查实测走通了。代价是短链接预览不出来 —— **宁可少个功能**。
        // ⚠️ 只取 `<head>`：一见 `</head>` 就停 + 20KB 封顶。原来把 200KB 正文全拉回来，
        //    解析那边的正则在那个长度上是二次方复杂度（审查实测 120 秒没跑完）。
        // ⚠️ 用 [app.yxi.ssh.catching] 不用 runCatching —— 后者会把协程取消也吞掉，
        //    重连时正在飞的这次会被渲染成「这个链接取不到预览」（Catching.kt 里记着这条，已栽过五次）。

        // 先按**解析出来的真实 IP** 再把一次关：字符串闸门拦不住 `localtest.me`
        // 这种「域名解析到内网」的写法。拿不到解析结果（没有 getent）就不拦，交给上面的字符串闸门。
        val host = hostOf(url)
        val ips = app.yxi.ssh.catching {
            ssh.exec("getent hosts ${Shell.q(host)} 2>/dev/null | awk '{print ${'$'}1}'")
        }.getOrNull().orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (ips.any { ip -> ipv4Of(ip)?.let { isPrivateV4(it) } ?: isPrivate(ip) }) return Result.Blocked

        val out = app.yxi.ssh.catching {
            ssh.exec(
                "if ! command -v curl >/dev/null 2>&1; then echo __NOCURL__; exit 0; fi; " +
                    "curl -s --max-time 8 --max-redirs 0 " +
                    "-A 'Mozilla/5.0 (compatible; Yxi/1.0; +https://yxi.keuury.com)' " +
                    "$q 2>/dev/null | sed -e '/<\\/[Hh][Ee][Aa][Dd]>/q' | head -c 20000"
            )
        }.getOrNull().orEmpty()

        if (out.startsWith("__PRIVATE__")) return Result.Blocked

        if (out.startsWith("__NOCURL__")) return Result.NoCurl
        if (out.isBlank()) return Result.Failed
        // ⚠️ 解析放 IO 线程：那两条正则在长文本上是**二次方**复杂度，主线程上会直接 ANR
        //    （审查实测 200KB × 6 个标签名 120 秒没跑完）。现在只取 head + 20KB 封顶，
        //    但仍然不该占着主线程。
        val card = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parse(url, out) }
        return card?.let { Result.Ok(it) } ?: Result.Failed
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
