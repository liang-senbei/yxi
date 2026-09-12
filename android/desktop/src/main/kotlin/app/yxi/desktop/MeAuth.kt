package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yxi.agent.AccountApi
import app.yxi.agent.Plat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * 桌面端的**登录**:OIDC + PKCE,走**系统浏览器 + 本机回环端口**收回调。
 *
 * ⚠️ **不内嵌 WebView**(老板 09-08 指定,`design/desktop-reference.md` §2.1 也是这个结论):
 * Codex 的做法就是浏览器转一圈回 `http://localhost:<端口>/callback`。内嵌浏览器有三个问题 ——
 * 用户看不到地址栏里的域名(钓鱼没法分辨)、密码管理器和已登录会话用不上、
 * 而且我们要多打包一个浏览器内核。
 *
 * ⚠️ **端口动态分配**(`ServerSocket(0)`),不写死。写死会撞用户机器上别的软件,
 * 被占了就登不进去 —— 而登录是「我的」这一整页的门,登不了整页就废了。
 * (pilot 2026-09-08:桌面壳的单实例锁也是 `ServerSocket(0)` 拿临时端口,同一个理由。)
 *
 * ⚠️ **令牌存 `%LOCALAPPDATA%\Yxi`,不是 `%APPDATA%`**:后者是漫游目录,域账户换台机器登录、
 * 或者 OneDrive 备份开着,它会被同步到别处去 —— 那等于把登录凭据抄送到公司文件服务器。
 * 这条是桌面端 [Store] 已经踩过并写进注释的坑,登录令牌比 hosts.json 更不能漫游。
 */
object MeAuth {

    /** 登录了没。界面直接读。 */
    var signedIn by mutableStateOf(false)
        private set

    /** 服务端那份资料(可能是上次缓存的)。 */
    var me by mutableStateOf<AccountApi.Me?>(null)
        private set

    /** 掉登录的原因,给界面显示一句人话;没掉就是空。 */
    var signedOutWhy by mutableStateOf("")
        private set

    /** 正在等浏览器那边授权 —— 界面显示「已经在浏览器里打开,授权完这里会自己继续」。 */
    var waitingBrowser by mutableStateOf(false)
        private set

    private val tokens = File(Store.dir, "auth.json")

    private fun read(): JSONObject =
        runCatching { JSONObject(tokens.readText()) }.getOrElse { JSONObject() }

    private fun write(o: JSONObject) {
        runCatching {
            tokens.writeText(o.toString())
            // ⚠️ 尽力把权限收到「只有本人能读」。Windows 上 setReadable(false, false) 是 no-op,
            //    真正的防线是目录本身(%LOCALAPPDATA% 在用户 profile 下);Linux/macOS 上这一步有效。
            tokens.setReadable(false, false); tokens.setReadable(true, true)
        }.onFailure { Plat.logw("Yxi", "存令牌失败: ${it.message}") }
    }

    /** 启动时叫一次:有令牌就当已登录,顺手拉一次资料。 */
    suspend fun load() {
        signedIn = read().optString("refresh").isNotEmpty()
        if (signedIn) refresh()
    }

    fun signOut() {
        runCatching { tokens.delete() }
        signedIn = false; me = null
    }

    /**
     * 走一遍登录。**挂起到浏览器那边回来为止**(或者超时/用户关掉窗口)。
     * @return 出错原因;成功返回 null。
     */
    suspend fun signIn(): String? = withContext(Dispatchers.IO) {
        val verifier = AccountApi.randomUrlSafe(32)
        val state = AccountApi.randomUrlSafe(8)
        // 固定 1455（照 Codex 的做法）：Logto 的 redirect_uri 必须精确匹配，随机端口永远注册不上
        // （1.1.1 真机报 invalid_redirect_uri 就是随机端口害的；Logto 侧已注册 http://127.0.0.1:1455/callback）。
        // 先绑上再拼 redirect_uri，绑不上（别的程序占了口/上一个实例没退干净）就明说，别让浏览器白跑一趟。
        val server = runCatching { ServerSocket(1455, 1, InetAddress.getLoopbackAddress()) }
            .getOrElse {
                return@withContext "回调端口 1455 被占用（可能上一次登录还没退干净）。稍后再试，或重启电脑后重试。:${it.message}"
            }
        server.soTimeout = 5 * 60 * 1000            // 五分钟没人回来就收摊,别把线程和端口永远占着
        val redirect = "http://127.0.0.1:${server.localPort}/callback"
        val url = "${AccountApi.AUTH}/oidc/auth?" + mapOf(
            "client_id" to AccountApi.APP_ID,
            "redirect_uri" to redirect,
            "response_type" to "code",
            "scope" to AccountApi.SCOPES,
            "state" to state,
            "prompt" to "consent",
            "code_challenge" to AccountApi.challengeOf(verifier),
            "code_challenge_method" to "S256",
        ).entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

        if (!openBrowser(url)) {
            runCatching { server.close() }
            return@withContext "打不开浏览器。把这个地址复制到浏览器里也行:\n$url"
        }
        waitingBrowser = true
        try {
            val (code, backState, err) = server.use { awaitCallback(it, state) }
            when {
                err.isNotEmpty() -> return@withContext "授权没通过:$err"
                // state 对不上 = 这个回调不是我们发起的那一次(PKCE 已经挡住登录 CSRF,这是第二道)
                backState != state -> return@withContext "登录状态校验失败,重来一次"
                code.isEmpty() -> return@withContext "浏览器没给授权码,重来一次"
            }
            val (c, body) = AccountApi.form(
                "${AccountApi.AUTH}/oidc/token",
                mapOf(
                    "grant_type" to "authorization_code", "code" to code,
                    "redirect_uri" to redirect, "client_id" to AccountApi.APP_ID,
                    "code_verifier" to verifier,
                ),
            )
            if (c !in 200..299) return@withContext AccountApi.httpErr(c, body)
            save(JSONObject(body))
            signedIn = true; signedOutWhy = ""
            refresh()
        } catch (e: java.net.SocketTimeoutException) {
            "等了五分钟没等到浏览器那边的授权,重来一次"
        } catch (e: Exception) {
            "登录出错:${e.message}"
        } finally {
            waitingBrowser = false
        }
    }

    /** 拉一次 `/api/me`。@return 出错原因,成功 null。 */
    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        val tk = token() ?: return@withContext "没登录"
        val (c, body) = AccountApi.req("${AccountApi.API}/api/me", "GET", tk, null)
        if (c !in 200..299) return@withContext AccountApi.httpErr(c, body)
        runCatching { me = AccountApi.parseMe(JSONObject(body)) }
            .onFailure { return@withContext "读不懂服务器的回复" }
        null
    }

    /**
     * 拿一把还能用的 access token;快过期就先续。
     *
     * ⚠️ **refresh token 每次都会轮换**,旧的只有约五秒宽限,过了再用**整条授权当场被撤销**
     * (手机端 2026-09 实测出来的,注释在 `Account.token`)。所以:响应里的新令牌必须存回去,
     * 而且**同一时刻只能有一个线程在续** —— 两个线程同时进来会互相拿旧的重用。
     */
    @Synchronized
    private fun token(): String? {
        val o = read()
        val acc = o.optString("access").takeIf { it.isNotEmpty() }
        if (acc != null && System.currentTimeMillis() < o.optLong("exp") - 60_000L) return acc
        val rt = o.optString("refresh").takeIf { it.isNotEmpty() } ?: return acc
        val (c, body) = AccountApi.form(
            "${AccountApi.AUTH}/oidc/token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to rt,
                  "client_id" to AccountApi.APP_ID, "scope" to AccountApi.SCOPES),
        )
        if (c !in 200..299) {
            Plat.logw("Yxi", "续令牌失败 HTTP $c")
            // ⚠️ 被拒(撤销 / 过期 / 旧令牌被重用过)才是真掉登录;**网络不通(code 0)不算** ——
            //    那种时候把人登出是最坏的处理。
            if (c == 400 || c == 401) { signOut(); signedOutWhy = "登录失效了,重新登一次" }
            return null
        }
        save(JSONObject(body))
        return read().optString("access").takeIf { it.isNotEmpty() }
    }

    private fun save(j: JSONObject) {
        val o = read()
        j.optString("access_token").takeIf { it.isNotEmpty() }?.let { o.put("access", it) }
        // ⚠️ **新的 refresh token 必须存回去**。漏了它,下次就是拿旧的重用 = 整条授权被撤销。
        j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { o.put("refresh", it) }
        val ttl = j.optLong("expires_in", 3600L)
        o.put("exp", System.currentTimeMillis() + ttl * 1000L)
        write(o)
    }

    /**
     * 从请求行里解出回调参数。**抽成纯函数是为了测得到** —— 登录最容易错的就是这一段
     * (URL 解码、参数缺失、浏览器发的是错误回调),而它原来埋在 socket 里,只有真跑一遍登录才碰得到。
     * @param line 形如 `GET /callback?code=x&state=y HTTP/1.1`
     */
    internal fun parseCallback(line: String): Map<String, String> {
        val parts = line.split(' ')
        if (parts.size != 3 || parts[0] != "GET" || parts[1].substringBefore('?') != "/callback") return emptyMap()
        val q = line.substringAfter('?', "").substringBefore(' ')
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else runCatching {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()
    }

    /**
     * 收一次回调。返回 (code, state, error)。
     * @param expectState 发起这次登录时用的 state。**浏览器那页显示成功与否要用它判** ——
     *   不然会出现「浏览器说登录成功、应用说校验失败」这种自相矛盾(2026-09-08 在 Xvfb 上跑出来的:
     *   拿一个 state 不对的回调打进来,页面照样写「登录成功」)。只看 code 在不在是不够的。
     */
    private fun awaitCallback(server: ServerSocket, expectState: String): Triple<String, String, String> {
        server.accept().use { sock ->
            val line = sock.getInputStream().bufferedReader().readLine().orEmpty()   // "GET /callback?... HTTP/1.1"
            val kv = parseCallback(line)
            // 浏览器那边要看到一句人话,不然停在空白页会以为没成功
            val page = callbackPage(kv, expectState)
            val html = "<!doctype html><meta charset=utf-8><title>Yxi</title>" +
                "<body style=\"font:16px/1.7 system-ui;padding:3rem;color:#222\">$page</body>"
            sock.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                       "Content-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(html.toByteArray()); flush()
            }
            return Triple(kv["code"].orEmpty(), kv["state"].orEmpty(), kv["error"].orEmpty())
        }
    }

    /**
     * 浏览器那一页该显示哪句话。**抽成纯函数是为了测得到** ——
     * 2026-09-08 在 Xvfb 上真跑了一遍才发现:拿一个 state 不对的回调打进来,
     * 页面照样写「登录成功」,而应用那边红字写着「校验失败」—— **两边说的话相反**。
     * 只看 code 在不在是不够的,state 也得判;而这种「两个界面各说各话」编译和单看代码都发现不了。
     */
    internal fun callbackPage(kv: Map<String, String>, expectState: String): String {
        val stateOk = kv["state"] == expectState
        val hasCode = !kv["code"].isNullOrEmpty()
        val err = kv["error"].orEmpty()
        return when {
            err.isEmpty() && hasCode && stateOk -> "授权已收到，正在由 Yxi 验证账号。请回到应用查看登录结果。"
            err.isEmpty() && !stateOk -> "这个回调不是 Yxi 这次登录发起的,已经忽略。回到 Yxi 重新点一次登录。"
            else -> "登录没有完成:" + htmlEscape(kv["error_description"] ?: err.ifEmpty { "浏览器没给授权码" })
        }
    }

    private fun htmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** 用系统默认浏览器打开。Desktop.browse 在部分 Linux 上不可用,退回 xdg-open / start / open。 */
    private fun openBrowser(url: String): Boolean {
        runCatching {
            val d = java.awt.Desktop.getDesktop()
            if (d.isSupported(java.awt.Desktop.Action.BROWSE)) { d.browse(java.net.URI(url)); return true }
        }
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.startsWith("windows") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> arrayOf("open", url)
            else -> arrayOf("xdg-open", url)
        }
        return runCatching { ProcessBuilder(*cmd).start(); true }.getOrDefault(false)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
