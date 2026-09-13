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
 * 回调端口使用服务端已注册的1455；占用时显示错误，不让用户进入无法返回的授权流程。
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
    var profileError by mutableStateOf("")
        private set

    private val sessions = AuthSessionStore(File(Store.dir, "auth.json"))
    private var callbackServer: ServerSocket? = null
    internal val sessionGeneration get() = sessions.generation

    /** 启动时叫一次:有令牌就当已登录,顺手拉一次资料。 */
    suspend fun load() {
        val generation = sessionGeneration
        try {
            val active = sessions.read(generation).optString("refresh").isNotEmpty()
            sessions.guarded(generation) { signedIn = active; if (!active) me = null }
            if (active) {
                val problem = refresh()
                sessions.guarded(generation) { profileError = problem.orEmpty() }
            }
        } catch (_: StaleAuthSession) { }
        catch (e: Exception) { runCatching { sessions.guarded(generation) { signedIn = false; signedOutWhy = "无法读取本地登录记录：${e.message}" } } }
    }

    fun signOut() {
        sessions.signOut(onNotice = { signedOutWhy = it.orEmpty() }) {
            signedIn = false; me = null; waitingBrowser = false; profileError = ""
            runCatching { callbackServer?.close() }; callbackServer = null
        }
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
        val server = runCatching { ServerSocket(1455, 1, InetAddress.getByName("127.0.0.1")) }
            .getOrElse {
                return@withContext "回调端口 1455 被占用（可能上一次登录还没退干净）。稍后再试，或重启电脑后重试。:${it.message}"
            }
        val generation = sessions.begin { signedIn = false; me = null; profileError = ""; callbackServer = server }
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
            runCatching { sessions.guarded(generation) { callbackServer = null } }
            return@withContext "打不开浏览器。把这个地址复制到浏览器里也行:\n$url"
        }
        try {
            sessions.guarded(generation) { waitingBrowser = true }
            val (code, backState, err) = server.use { awaitCallback(it, state) }
            when {
                err.isNotEmpty() -> return@withContext "授权没通过:$err"
                // state 对不上 = 这个回调不是我们发起的那一次(PKCE 已经挡住登录 CSRF,这是第二道)
                backState != state -> return@withContext "登录状态校验失败,重来一次"
                code.isEmpty() -> return@withContext "浏览器没给授权码,重来一次"
            }
            sessions.guarded(generation) { check(callbackServer === server) }
            val (c, body) = AccountApi.form(
                "${AccountApi.AUTH}/oidc/token",
                mapOf(
                    "grant_type" to "authorization_code", "code" to code,
                    "redirect_uri" to redirect, "client_id" to AccountApi.APP_ID,
                    "code_verifier" to verifier,
                ),
            )
            if (c !in 200..299) return@withContext AccountApi.httpErr(c, body)
            sessions.guarded(generation) {
                sessions.save(generation, JSONObject(body), fresh = true)
                signedIn = true; signedOutWhy = ""
            }
            refresh()
        } catch (e: java.net.SocketTimeoutException) {
            "登录等待已超时，请重新尝试"
        } catch (e: Exception) {
            if (sessionGeneration != generation) "登录已取消" else "登录出错:${e.message}"
        } finally {
            runCatching { sessions.guarded(generation) { waitingBrowser = false; callbackServer = null } }
        }
    }

    /** 拉一次 `/api/me`。@return 出错原因,成功 null。 */
    internal suspend fun accountRequest(owner: String, path: String, method: String, body: String?, generation: Long = sessionGeneration): Pair<Int, String> = withContext(Dispatchers.IO) {
        sessions.guarded(generation) { check(signedIn && me?.userId == owner) { "登录账号已变化，请重新进入账户页面" } }
        require(listOf("/api/mail", "/api/support/tickets").any { path == it || path.startsWith("$it/") || path.startsWith("$it?") })
        val access = token(generation) ?: error("登录暂不可用，请检查连接或重新登录")
        sessions.guarded(generation) { check(signedIn && me?.userId == owner) { "登录账号已变化" } }
        val result = AccountApi.req(AccountApi.API + path, method, access, body)
        sessions.guarded(generation) { check(signedIn && me?.userId == owner) { "登录账号已变化，已忽略旧账号的回复" } }
        result
    }

    internal fun mailCounters(owner: String, result: JSONObject, generation: Long = sessionGeneration) = runCatching { sessions.guarded(generation) {
        val current = me?.takeIf { signedIn && it.userId == owner } ?: return@guarded
        me = current.copy(
            unreadMail = result.optInt("unread", -1).takeIf { it >= 0 } ?: current.unreadMail,
            unclaimedMail = result.optInt("unclaimed", -1).takeIf { it >= 0 } ?: current.unclaimedMail,
            tickets = result.optInt("tickets", -1).takeIf { it >= 0 } ?: current.tickets,
            balanceCents = result.optLong("balanceCents", -1).takeIf { it >= 0 } ?: current.balanceCents,
        )
    } }.let { Unit }

    internal fun supportUnread(owner: String, count: Int, generation: Long = sessionGeneration) = runCatching { sessions.guarded(generation) {
        me?.takeIf { signedIn && it.userId == owner && count >= 0 }?.let { me = it.copy(unreadTickets = count) }
    } }.let { Unit }

    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        val generation = sessionGeneration
        try {
        val tk = token(generation) ?: return@withContext "登录暂不可用，请检查连接或重新登录"
        val (c, body) = AccountApi.req("${AccountApi.API}/api/me", "GET", tk, null)
        if (c !in 200..299) return@withContext AccountApi.httpErr(c, body)
        val profile = runCatching { AccountApi.parseMe(JSONObject(body)) }.getOrElse { return@withContext "读不懂服务器的回复" }
        sessions.guarded(generation) { check(signedIn); me = profile }
        null
        } catch (_: StaleAuthSession) { "登录会话已变化" }
        catch (e: Exception) { "登录信息无法更新：${e.message}" }
    }

    /**
     * 拿一把还能用的 access token;快过期就先续。
     *
     * ⚠️ **refresh token 每次都会轮换**,旧的只有约五秒宽限,过了再用**整条授权当场被撤销**
     * (手机端 2026-09 实测出来的,注释在 `Account.token`)。所以:响应里的新令牌必须存回去,
     * 而且**同一时刻只能有一个线程在续** —— 两个线程同时进来会互相拿旧的重用。
     */
    @Synchronized
    private fun token(generation: Long): String? {
        val o = sessions.read(generation)
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
            if (c == 400 || c == 401) sessions.guarded(generation) {
                signOut(); signedOutWhy = listOf("登录失效了,重新登一次", signedOutWhy).filter { it.isNotBlank() }.joinToString("；")
            }
            return null
        }
        try { sessions.save(generation, JSONObject(body)) }
        catch (e: StaleAuthSession) { throw e }
        catch (e: Exception) {
            sessions.guarded(generation) {
                signOut(); signedOutWhy = listOf("新的登录凭据无法保存，请重新登录：${e.message}", signedOutWhy).filter { it.isNotBlank() }.joinToString("；")
            }
            return null
        }
        return sessions.read(generation).optString("access").takeIf { it.isNotEmpty() }
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
        val pairs = q.split('&').map {
            val i = it.indexOf('=')
            if (i <= 0) return emptyMap()
            runCatching {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.getOrElse { return emptyMap() }
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) return emptyMap()
        return pairs.toMap()
    }

    /**
     * 收一次回调。返回 (code, state, error)。
     * @param expectState 发起这次登录时用的 state。**浏览器那页显示成功与否要用它判** ——
     *   不然会出现「浏览器说登录成功、应用说校验失败」这种自相矛盾(2026-09-08 在 Xvfb 上跑出来的:
     *   拿一个 state 不对的回调打进来,页面照样写「登录成功」)。只看 code 在不在是不够的。
     */
    internal fun awaitCallback(server: ServerSocket, expectState: String, timeoutMs: Int = 300_000, readMs: Int = 3000): Triple<String, String, String> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
          val remaining = ((deadline - System.nanoTime()) / 1_000_000).toInt()
          if (remaining <= 0) throw java.net.SocketTimeoutException()
          server.soTimeout = remaining
          try { server.accept().use { sock ->
            val requestDeadline = minOf(deadline, System.nanoTime() + readMs * 1_000_000L)
            val bytes = java.io.ByteArrayOutputStream()
            val input = sock.getInputStream()
            while (bytes.size() < 8192) {
                val left = ((requestDeadline - System.nanoTime()) / 1_000_000).toInt()
                if (left <= 0) throw java.net.SocketTimeoutException()
                sock.soTimeout = left
                val byte = input.read()
                if (byte < 0 || byte == 10) break
                if (byte != 13) bytes.write(byte)
            }
            val line = if (bytes.size() >= 8192) "" else bytes.toString("UTF-8")
            var blank = true
            var headersDone = false
            var headerSize = 0
            while (headerSize++ < 16384) {
                val left = ((requestDeadline - System.nanoTime()) / 1_000_000).toInt()
                if (left <= 0) throw java.net.SocketTimeoutException()
                sock.soTimeout = left
                val byte = input.read()
                if (byte < 0) break
                if (byte == 10 && blank) { headersDone = true; break }
                if (byte != 13) blank = byte == 10
            }
            val kv = if (headersDone) parseCallback(line) else emptyMap()
            val valid = kv["state"] == expectState && (kv["code"].isNullOrEmpty() xor kv["error"].isNullOrEmpty())
            // 浏览器那边要看到一句人话,不然停在空白页会以为没成功
            val page = callbackPage(kv, expectState)
            val html = "<!doctype html><meta charset=utf-8><title>Yxi</title>" +
                "<body style=\"font:16px/1.7 system-ui;padding:3rem;color:#222\">$page</body>"
            sock.getOutputStream().apply {
                write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                       "Content-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(html.toByteArray()); flush()
            }
            if (valid) return Triple(kv["code"].orEmpty(), kv["state"].orEmpty(), kv["error"].orEmpty())
          } } catch (_: java.net.SocketTimeoutException) { /* Keep the original deadline. */ }
          catch (e: java.io.IOException) { if (server.isClosed) throw e }
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
