package app.yxi.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * **账号**：Logto 登录（OIDC + PKCE）+ 会员服务（api.yxi.keuury.com）。
 *
 * ⚠️ **没用任何 SDK**。离线构建加不了依赖，而这件事就是「浏览器转一圈 + 两个 form POST」，
 * 手写反而看得清。PKCE 的 verifier 存在本机，token 换回来也存在本机（App 私有目录，
 * 跟 SSH 私钥一个地方）。
 *
 * ⚠️ **资料不再直接写 Logto**（cc-logto_yxi 2026-09-04 第二条）：改资料要扣配额、会员要到期、
 * 兑换码要防重复 —— 这些只有服务端说了算。Logto 那边用户直写已经关掉了，
 * 唯一的写入口是 `PATCH /api/me/profile`。
 */
object Account {

    private const val AUTH = "https://auth.yxi.keuury.com"
    private const val API = "https://api.yxi.keuury.com"
    private const val APP_ID = "g6ydpvvn833z0ua19n6hz"
    const val REDIRECT = "io.yxi.app://callback"
    private const val SCOPES = "openid offline_access profile email custom_data roles"

    enum class Tier { Free, Pro, Ultra }

    /** `GET /api/me` 的全部 —— 会员中心整页就渲染它 */
    data class Me(
        val userId: String,
        val tier: Tier,
        val expiresAt: String?,          // ISO8601 +08:00；null = 没有会员
        val neverExpires: Boolean,
        val nickname: String,
        val avatar: String?,
        val signature: String,
        val email: String,
        val quotaLimit: Int,
        val quotaUsed: Int,
        /** null = 不限（ultra） */
        val quotaRemaining: Int?,
        val nextRefreshAt: String?,
        val quotaRule: String,
    )

    /** 登录了没。⚠️ 是 Compose 状态，界面直接读。 */
    var signedIn by mutableStateOf(false)
        private set
    /** 服务端那份资料（可能是上次缓存的）。 */
    var me by mutableStateOf<Me?>(null)
        private set

    /** 浏览器登录完跳回来的那个 URI —— MainActivity 塞进来，界面层取走处理 */
    var pendingCallback by mutableStateOf<Uri?>(null)

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    /** 进程起来时调一次：把上次的登录状态和资料摆出来，界面不用等网络 */
    fun load(ctx: Context) {
        signedIn = p(ctx).getString("auth.refresh", null) != null
        me = p(ctx).getString("auth.me", null)?.let { runCatching { parseMe(JSONObject(it)) }.getOrNull() }
    }

    // ── 登录 ───────────────────────────────────────────────────────────────

    /** 拉起浏览器去登录。回来的是 [REDIRECT]，由 MainActivity 接住交给 [finishLogin]。 */
    fun startLogin(ctx: Context) {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        p(ctx).edit().putString("auth.verifier", verifier).putString("auth.state", state).apply()
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = Uri.parse("$AUTH/oidc/auth").buildUpon()
            .appendQueryParameter("client_id", APP_ID)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            // ⚠️ 要 refresh_token 就得带上 offline_access + prompt=consent，
            //    不然下次进 App 又要重新登一遍。
            .appendQueryParameter("prompt", "consent")
            .build()
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 浏览器回调回来：拿 code 换 token。@return 出错时的原因，成功返回 null */
    suspend fun finishLogin(ctx: Context, data: Uri): String? = withContext(Dispatchers.IO) {
        val err = data.getQueryParameter("error")
        if (err != null) return@withContext data.getQueryParameter("error_description") ?: err
        val code = data.getQueryParameter("code") ?: return@withContext "回调里没有 code"
        val state = data.getQueryParameter("state")
        val saved = p(ctx).getString("auth.state", null)
        // ⚠️ state 对不上就是别人塞过来的回调，直接扔
        if (saved == null || state != saved) return@withContext "登录状态对不上，重新登一次"
        val verifier = p(ctx).getString("auth.verifier", null) ?: return@withContext "登录状态丢了，重新登一次"
        val (c, body) = form(
            "$AUTH/oidc/token",
            mapOf(
                "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to REDIRECT, "client_id" to APP_ID, "code_verifier" to verifier,
            ),
        )
        if (c !in 200..299) return@withContext "换 token 失败（$c）"
        saveTokens(ctx, JSONObject(body))
        p(ctx).edit().remove("auth.verifier").remove("auth.state").apply()
        signedIn = true
        refresh(ctx)
        null
    }

    fun signOut(ctx: Context) {
        p(ctx).edit().remove("auth.access").remove("auth.refresh").remove("auth.exp").remove("auth.me").apply()
        signedIn = false; me = null
    }

    private fun saveTokens(ctx: Context, o: JSONObject) {
        val e = p(ctx).edit()
        o.optString("access_token").takeIf { it.isNotEmpty() }?.let { e.putString("auth.access", it) }
        o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { e.putString("auth.refresh", it) }
        e.putLong("auth.exp", System.currentTimeMillis() + o.optLong("expires_in", 3600L) * 1000L)
        e.apply()
    }

    /** 拿一把还能用的 access token；快过期就先续。拿不到 = 没登录 / 续不上。 */
    private fun token(ctx: Context): String? {
        val sp = p(ctx)
        val acc = sp.getString("auth.access", null)
        if (acc != null && System.currentTimeMillis() < sp.getLong("auth.exp", 0L) - 60_000L) return acc
        val rt = sp.getString("auth.refresh", null) ?: return acc
        val (c, body) = form(
            "$AUTH/oidc/token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to APP_ID, "scope" to SCOPES),
        )
        if (c !in 200..299) {
            // ⚠️ refresh 被拒（撤销 / 过期）就是真的掉登录了，别装作还登着
            if (c == 400 || c == 401) { signOut(ctx) }
            return null
        }
        saveTokens(ctx, JSONObject(body))
        return sp.getString("auth.access", null)
    }

    // ── 会员服务 ───────────────────────────────────────────────────────────

    /** 拉一次 `GET /api/me`，顺手缓存。@return 出错原因，成功 null */
    suspend fun refresh(ctx: Context): String? = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: return@withContext "没登录"
        val (c, body) = req("$API/api/me", "GET", tk, null)
        if (c !in 200..299) return@withContext httpErr(c, body)
        runCatching {
            val o = JSONObject(body)
            me = parseMe(o)
            p(ctx).edit().putString("auth.me", body).apply()
            signedIn = true
        }.exceptionOrNull()?.let { return@withContext "读不懂服务器的回复" }
        null
    }

    /**
     * 改资料。⚠️ **一次提交算一次额度，不管改了几个字段** —— 所以界面是「一起改、一次保存」。
     * @return 出错原因（中文，可直接显示），成功 null
     */
    suspend fun saveProfile(ctx: Context, nickname: String?, signature: String?, avatar: String? = null): String? =
        withContext(Dispatchers.IO) {
            val tk = token(ctx) ?: return@withContext "没登录"
            val body = JSONObject().apply {
                nickname?.let { put("nickname", it) }
                signature?.let { put("signature", it) }
                avatar?.let { put("avatar", it) }
            }
            val (c, resp) = req("$API/api/me/profile", "PATCH", tk, body.toString())
            when {
                c in 200..299 -> {
                    runCatching { me = parseMe(JSONObject(resp)); p(ctx).edit().putString("auth.me", resp).apply() }
                    null
                }
                else -> httpErr(c, resp)
            }
        }

    /** 兑换码。成功回一段可以直接显示的话；失败回错误原因。 */
    suspend fun redeem(ctx: Context, code: String): Result<String> = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: return@withContext Result.failure(Exception("没登录"))
        val (c, resp) = req("$API/api/me/redeem", "POST", tk, JSONObject().put("code", code).toString())
        if (c !in 200..299) return@withContext Result.failure(Exception(httpErr(c, resp)))
        val o = runCatching { JSONObject(resp) }.getOrNull() ?: return@withContext Result.failure(Exception("读不懂服务器的回复"))
        refresh(ctx)
        val tier = o.optString("tier").uppercase()
        val days = o.optInt("days")
        // ⚠️ replay = 同一张码你自己重兑（断网重试就会这样）——**没有重复加天数**，得说清楚
        val msg = if (o.optBoolean("replay"))
            "这张码你已经兑过了，没有重复加天数（到期 ${o.optString("expiresAt").take(10)}）"
        else "兑换成功：$tier $days 天，到期 ${o.optString("expiresAt").take(10)}"
        Result.success(msg)
    }

    // ── 杂活 ───────────────────────────────────────────────────────────────

    /**
     * ⚠️ **契约里「正常就是 null」的字段**（logto_yxi 2026-09-04 明确给的，别再被咬第二次）：
     *  · `membership.expiresAt` —— 没兑过码，或后台手工永久授予（那时 `neverExpires:true`）
     *  · `quota.limit` / `remaining` / `nextRefreshAt` —— ultra 时全是 null（**先读 `unlimited`**，别拿 remaining 判断）
     *  · `profile.nickname` / `avatar` / `signature` —— 用户没设过（社交注册的通常有昵称和头像）
     *  · `profile.email` —— 只用社交登录、没绑邮箱的
     * **恒定有值**：`userId`(=sub)、`tier`、`isAdmin`、`quota.unlimited`、`quota.used`。
     *
     * 所以：所有可空字符串走 [str]（`optString` 读 null 会给字符串 `"null"`，见 #230），
     * 新用户四个 profile 字段可能全空 —— 界面每一处都得有空态（「点这里起个名」「写句个性签名」这些）。
     */
    private fun parseMe(o: JSONObject): Me {
        val ms = o.optJSONObject("membership")
        val pr = o.optJSONObject("profile")
        val q = o.optJSONObject("quota")
        return Me(
            userId = o.str("userId"),
            tier = when (o.optString("tier")) {
                "ultra" -> Tier.Ultra
                "pro" -> Tier.Pro
                else -> Tier.Free
            },
            expiresAt = ms.str("expiresAt").takeIf { it.isNotEmpty() },
            neverExpires = ms?.optBoolean("neverExpires") == true,
            // ⚠️ **`optString` 读 JSON null 会得到字符串 "null"**（不是空串）——
            //    实测签名没设的账号，编辑框里赫然写着 `null`。所有可空字符串都得过这一手。
            nickname = pr.str("nickname"),
            avatar = pr.str("avatar").takeIf { it.isNotEmpty() },
            signature = pr.str("signature"),
            email = pr.str("email"),
            quotaLimit = q?.optInt("limit") ?: 0,
            quotaUsed = q?.optInt("used") ?: 0,
            // ⚠️ **先读 `unlimited` 这个显式布尔**（服务端 2026-09-04 加的）。
            //    以前只能靠 `remaining: null` 判「不限」—— 而 `optInt` 读 null 会给 0，
            //    那是「用完了」，意思正好反过来：付费的 ultra 会被拦在门外（#229）。
            quotaRemaining = when {
                q == null -> null
                q.optBoolean("unlimited") -> null
                q.isNull("remaining") -> null
                else -> q.optInt("remaining")
            },
            nextRefreshAt = q.str("nextRefreshAt").takeIf { it.isNotEmpty() },
            quotaRule = q.str("rule"),
        )
    }

    /** JSON 里的可空字符串：null / "null" / 没这个键 一律当空串 */
    private fun JSONObject?.str(key: String): String {
        if (this == null || isNull(key)) return ""
        val v = optString(key)
        return if (v == "null") "" else v
    }

    /** 服务端的错都带中文 msg / 有约定的 error 码，翻译成一句人话 */
    private fun httpErr(c: Int, body: String): String {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val err = o.str("error")
        val msg = o.str("msg")
        return when {
            // ⚠️ **服务端的 `msg` 优先**（logto_yxi 2026-09-04）：它是中文、由他们维护、
            //    永远跟真实原因一致。比如同一个 409 会分「被别人使用了」和「名额已经领完了」——
            //    本地写死一句就会说错。下面那些只是**服务端没给 msg 时**的兜底。
            msg.isNotEmpty() -> msg
            err == "quota_exhausted" -> {
                val next = o?.optString("nextRefreshAt")?.take(10).orEmpty()
                if (next.isEmpty()) "这个月的修改次数用完了" else "这个月的修改次数用完了，$next 恢复"
            }
            err == "code_not_found" -> "没有这张兑换码"
            err == "code_redeemed" -> "这张码已经被人兑走了"
            err == "code_disabled" -> "这张码被停用了"
            err == "code_expired" -> "这张码过期了"
            err == "code_not_started" -> "这张码还没生效"
            c == 401 -> "登录过期了，重新登一次"
            c == 429 -> "太频繁了，等一分钟再试"
            else -> "服务器返回 $c"
        }
    }

    private fun req(url: String, method: String, token: String, body: String?): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 15_000
            // ⚠️ **必须发真正的 PATCH。** 试过 `POST + X-HTTP-Method-Override: PATCH`，
            //    服务端直接 404（只有 PATCH 那条路由存在）。Android 的 HttpURLConnection 是 OkHttp 实现的、
            //    认 PATCH；万一在某些 ROM 上被纯 Java 那套挡了（ProtocolException），就直接改私有字段兜底。
            runCatching { requestMethod = method }.onFailure {
                runCatching {
                    val f = javaClass.superclass?.superclass?.getDeclaredField("method")
                        ?: javaClass.getDeclaredField("method")
                    f.isAccessible = true
                    f.set(this, method)
                }
            }
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            body?.let { c.outputStream.use { os -> os.write(it.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } catch (e: Exception) {
            0 to """{"msg":"连不上服务器：${e.message}"}"""
        } finally { c.disconnect() }
    }

    private fun form(url: String, fields: Map<String, String>): Pair<Int, String> {
        val payload = fields.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 15_000; requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            c.outputStream.use { it.write(payload.toByteArray()) }
            val code = c.responseCode
            code to (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (e: Exception) {
            0 to """{"msg":"连不上登录服务：${e.message}"}"""
        } finally { c.disconnect() }
    }

    private fun randomUrlSafe(n: Int): String {
        val b = ByteArray(n); SecureRandom().nextBytes(b); return b64url(b)
    }

    private fun b64url(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
