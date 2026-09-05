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
        /**
         * 这个账号身上的封禁。⚠️ **恒为数组，没封时是空的**（logto_yxi 给的契约）。
         * 只有**按产品封**才会出现在这儿 —— 全局封会让 Logto 当场作废令牌，那时候连 `/api/me` 都进不去，
         * App 只看得到 401，拿不到原因，走的是「登录失效了」那条路。
         */
        val bans: List<Ban> = emptyList(),
        /**
         * 钱包（cc-logto_yxi 2026-09-04 上线）。
         * ⚠️ **`balanceCents` 是「分」**，字段名里带单位就是为了防「有人当成元」的 100 倍事故。
         *    整数，永远不出现浮点。要显示成元只在**显示的那一刻**除 100。
         * ⚠️ `autoRenew` 这一版**只读不写**：充值还没通，余额永远是 0，
         *    放个开关就是「点了没反应的假按钮」。等对方开了写入口再做。
         */
        val balanceCents: Long = 0,
        val currency: String = "CNY",
        val autoRenew: Boolean = false,
        /** 未读站内信条数 —— 图标上那个红点靠它，不然要进去才知道有信 */
        val unreadMail: Int = 0,
        /**
         * 有附件但**还没领**的信有几封。
         * ⚠️ 跟 [unreadMail] **是两回事**：读过了也可能没领。界面上分开说，别合并成一个数。
         */
        val unclaimedMail: Int = 0,
        /** 手上几张曦光（祈愿用） */
        val tickets: Int = 0,
        /** 离保底还差几抽。⚠️ 是「**还差**」不是「已累计」—— 歧义写进字段名里解决（对方定的）。 */
        val pityRemaining: Int = 0,
    )

    /** 一封站内信。[kind] 契约里固定四个：`redeem` / `expiry` / `notice` / `system`。 */
    data class Mail(
        val id: String,
        val kind: String,
        val title: String,
        val body: String,
        val createdAt: String,
        val readAt: String?,
        /** 发件人显示名（服务端解析的，历史信件跟着一起改） */
        val fromName: String = "",
        val fromAvatar: String = "",
        /** 附件；没有就是空表 */
        val attachments: List<Attach> = emptyList(),
        /** 领过没。⚠️ 跟 [readAt] 是两回事：**读过了也可能没领**。 */
        val claimedAt: String? = null,
    ) {
        val unread: Boolean get() = readAt.isNullOrEmpty()

        /** 有东西可领、且还没领 */
        val claimable: Boolean
            get() = claimedAt.isNullOrEmpty() && attachments.any { it.grantable }
    }

    /**
     * 信里的一件附件。
     * ⚠️ [kind] == `code` 的**什么都不发**，只是印一串码面给用户自己去兑 ——
     * 所以它不算 [grantable]，一封只带 code 的信没有「领取」这一步。
     */
    data class Attach(val kind: String, val amount: Long, val name: String) {
        val grantable: Boolean get() = kind == "tickets" || kind == "balance_cents" || kind == "membership_days"
    }

    /** 领取的结果。[replay] = 之前已经领过，**这次没有重复发**。 */
    data class Claim(
        val replay: Boolean,
        val tickets: Int,
        val balanceCents: Long,
        val unclaimed: Int,
    )

    /** 一条封禁。`reason` 可能为 null（管理员没填）；`until` 为 null = 永久。 */
    data class Ban(val productName: String, val reason: String?, val until: String?, val createdAt: String)

    /** 登录了没。⚠️ 是 Compose 状态，界面直接读。 */
    var signedIn by mutableStateOf(false)
        private set
    /** 服务端那份资料（可能是上次缓存的）。 */
    var me by mutableStateOf<Me?>(null)
        private set

    /** 浏览器登录完跳回来的那个 URI —— MainActivity 塞进来，界面层取走处理 */
    var pendingCallback by mutableStateOf<Uri?>(null)

    /**
     * 上一次「掉登录」的原因，界面拿去说一声。
     *
     * ⚠️ **不能默默把人登出。** refresh 被服务器明确拒绝（撤销 / 过期 / 账号被全局封）时，
     * 令牌当场作废，App 这边只看得到 401 —— 拿不到原因（logto_yxi 2026-09-04：全局封禁就是这个表现）。
     * 那也要说一句「登录失效了，重新登一次」，而不是让人看着一个突然变回「未登录」的页面猜。
     */
    var signedOutWhy by mutableStateOf<String?>(null)

    /**
     * 就地更新手上的曦光数。
     * ⚠️ 只给「服务端刚刚在别的接口里告诉我们新值」的场合用（签到、抽奖的返回值），
     * **不是让客户端自己算**。真相源永远是服务端。
     */
    fun setTickets(@Suppress("UNUSED_PARAMETER") old: Int, now: Int) {
        me = me?.copy(tickets = now)
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    /** 进程起来时调一次：把上次的登录状态和资料摆出来，界面不用等网络 */
    fun load(ctx: Context) {
        // ⚠️ **判据是「解得开」，不是「有这个键」。**
        //    令牌是用 Keystore 加密存的（[app.yxi.ssh.Vault]）。密钥没了而密文还在，是真会发生的
        //    （模拟器冷启、系统重置密钥、上一版存的明文）—— 这时候如果还当「登录着」：
        //    登录门禁不弹、每个接口都拿不到 token、界面全是兜底值，**用户没有任何办法自救**。
        //    2026-09-04 在模拟器上就是这个表现，查了半天：日志里只有一行「没拿到 token」。
        signedIn = p(ctx).getString("auth.refresh", null)
            ?.let { runCatching { app.yxi.ssh.Vault.open(it) }.getOrNull() } != null
        me = p(ctx).getString("auth.me", null)?.let { runCatching { parseMe(JSONObject(it)) }.getOrNull() }
    }

    // ── 登录 ───────────────────────────────────────────────────────────────

    /**
     * 拉起浏览器去登录。回来的是 [REDIRECT]，由 MainActivity 接住交给 [finishLogin]。
     * @return false = **浏览器都没拉起来**（设备上没有能开 http 的应用）。
     *   ⚠️ 以前这里是 `runCatching {}` 吞掉的 —— 表现是「点了登录什么都没发生」，
     *   而登录现在是进 App 的必经之路，静默失败等于 App 打不开（见 design/STYLE.md「一切失败都要说出来」）。
     */
    fun startLogin(ctx: Context): Boolean {
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
        return runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
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

    /**
     * ⚠️ 退出登录要**同时告诉服务端**。以前只删本机的键：手机借人/卖二手前点了退出，
     * (a) 之前泄漏过的 refresh token 依然有效，(b) 浏览器里的 Logto 会话还在 ——
     * 下一个人点「登录」，不问密码就登进上一个人的账号。
     */
    fun signOut(ctx: Context) {
        val rt = p(ctx).getString("auth.refresh", null)
            ?.let { runCatching { app.yxi.ssh.Vault.open(it) }.getOrNull() }
        if (rt != null) Thread {
            // 尽力而为：吊销失败也照样本地删干净，不能因为没网就退不出去
            runCatching { form("$AUTH/oidc/token/revocation", mapOf("token" to rt, "client_id" to APP_ID)) }
        }.start()
        signedOutWhy = null
        p(ctx).edit().remove("auth.verifier").remove("auth.state").remove("auth.access").remove("auth.refresh").remove("auth.exp").remove("auth.me").apply()
        signedIn = false; me = null
    }

    /**
     * ⚠️ **令牌必须加密落盘**（2026-09-04 安全审计）。以前是明文 SharedPreferences，
     * 而同一个 App 里 SSH 私钥和主机密码早就走 [app.yxi.ssh.Vault]（Keystore AES-GCM）了。
     * 讽刺的是：**加密的那些换机带不走**（Keystore 密钥不进备份），
     * **唯独明文的 refresh token 换机后照样能用** —— 荣耀「手机克隆」/云备份一搬，账号就没了。
     */
    private fun saveTokens(ctx: Context, o: JSONObject) {
        val e = p(ctx).edit()
        o.optString("access_token").takeIf { it.isNotEmpty() }
            ?.let { e.putString("auth.access", app.yxi.ssh.Vault.seal(it)) }
        o.optString("refresh_token").takeIf { it.isNotEmpty() }
            ?.let { e.putString("auth.refresh", app.yxi.ssh.Vault.seal(it)) }
        e.putLong("auth.exp", System.currentTimeMillis() + o.optLong("expires_in", 3600L) * 1000L)
        e.apply()
    }

    /**
     * 刷新令牌的锁。
     *
     * ⚠️⚠️ **刷新必须串行，这不是优化是必需**（logto_yxi 2026-09-04 在 Logto 源码和真跑里确认）：
     * 我们是 Native 公开客户端，`clientAuthMethod == none` 时 Logto **强制轮换** refresh token ——
     * 每次刷新都发一个新的，旧的只有**约 5 秒宽限**；过了宽限再用旧的不只是失败，
     * **整条授权当场被撤销**（刚发的新令牌也一起作废）。实测：rt1 刷新拿到 rt2 → 等 5 秒再用 rt1
     * → 400 invalid_grant → **rt2 也变成 400**。
     *
     * 两个线程同时进来 = 一个成功、另一个拿着旧的重用，还会互相盖掉对方存下的新令牌。
     * 冷启动同时打几个接口正是最容易撞的场景 —— 而现在没登录会被 [app.yxi.ui.LoginGate]
     * 挡住整个 App，掉登录 = **App 打不开**。
     */
    private val refreshLock = Any()

    /** 拿一把还能用的 access token；快过期就先续。拿不到 = 没登录 / 续不上。 */
    private fun token(ctx: Context): String? {
        val sp = p(ctx)
        // 解不开 = 上一版存的明文 / 换过机器 → 当没登录，重登一次即可（不写迁移代码）
        fun cached() = sp.getString("auth.access", null)
            ?.let { runCatching { app.yxi.ssh.Vault.open(it) }.getOrNull() }
        fun stillGood() = System.currentTimeMillis() < sp.getLong("auth.exp", 0L) - 60_000L
        cached()?.let { if (stillGood()) return it }
        synchronized(refreshLock) {
        // 拿到锁之后**再看一眼**：可能别的线程刚续过。少转一次就少一次踩宽限期的机会。
        cached()?.let { if (stillGood()) return it }
        val acc = cached()
        val sealed = sp.getString("auth.refresh", null)
        val rt = sealed?.let { runCatching { app.yxi.ssh.Vault.open(it) }.getOrNull() }
        if (rt == null) {
            // 有密文却解不开 → 这份登录**永远用不了了**，当场登出，让门禁把人接住去重登
            if (sealed != null) {
                signOut(ctx)
                signedOutWhy = "登录信息读不出来了，重新登一次"
            }
            return acc
        }
        val (c, body) = form(
            "$AUTH/oidc/token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to APP_ID, "scope" to SCOPES),
        )
        if (c !in 200..299) {
            android.util.Log.w("YxiAccount", "续令牌失败 HTTP $c: " + body.take(200))
            // ⚠️ refresh 被拒（撤销 / 过期 / 账号被全局封 / 旧令牌被重用过）就是真的掉登录了，
            //    别装作还登着，**更不能拿同一个令牌重试** —— 重试就是又一次重用。
            //    ⚠️ 但**网络不通（code 0）不算**：那种时候把人登出是最坏的处理。
            if (c == 400 || c == 401) {
                signOut(ctx)
                signedOutWhy = "登录失效了，重新登一次"
            }
            return null
        }
        // ⚠️ 响应里的**新** refresh token 必须存回去（saveTokens 会存）。漏了它，下次就是拿旧的重用。
        saveTokens(ctx, JSONObject(body))
        return cached()
        }
    }

    // ── 会员服务 ───────────────────────────────────────────────────────────

    /** 拉一次 `GET /api/me`，顺手缓存。@return 出错原因，成功 null */
    suspend fun refresh(ctx: Context): String? = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: run {
            android.util.Log.w("YxiAccount", "/api/me 没拿到 token（令牌解不开 / 续不上）")
            return@withContext "没登录"
        }
        val (c, body) = req("$API/api/me", "GET", tk, null)
        if (c !in 200..299) {
            android.util.Log.w("YxiAccount", "/api/me HTTP $c: " + body.take(200))
            return@withContext httpErr(c, body)
        }
        runCatching {
            val o = JSONObject(body)
            me = parseMe(o)
            p(ctx).edit().putString("auth.me", body).apply()
            signedIn = true
        }.exceptionOrNull()?.let {
            // ⚠️ **别把解析失败咽下去。** 咽下去的表现是：登录着、也不报错，
            //    但「我的」「会员中心」全是空的 —— 界面只能显示兜底值，谁也看不出发生了什么。
            android.util.Log.w("YxiAccount", "/api/me 解析失败", it)
            return@withContext "读不懂服务器的回复：" + (it.message ?: it.javaClass.simpleName)
        }
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
    /**
     * 兑换的结构化结果 —— 成功动效（[app.yxi.ui.RedeemSuccess]）要按 [kind] 和 [tier] 分两套文案和配色，
     * 光有一句话不够。[msg] 是给输入框下面那行小字用的。
     */
    data class Redeemed(
        val msg: String,
        /** `membership` / `balance` */
        val kind: String,
        val tier: Tier,
        val expiresAt: String,
        val amountCents: Long,
        val balanceCents: Long,
        /** 同一张码重兑 —— **不放动效**，那不是一件值得庆祝的事 */
        val replay: Boolean,
    )

    suspend fun redeem(ctx: Context, code: String): Result<Redeemed> = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: return@withContext Result.failure(Exception("没登录"))
        val (c, resp) = req("$API/api/me/redeem", "POST", tk, JSONObject().put("code", code).toString())
        if (c !in 200..299) return@withContext Result.failure(Exception(httpErr(c, resp)))
        val o = runCatching { JSONObject(resp) }.getOrNull() ?: return@withContext Result.failure(Exception("读不懂服务器的回复"))
        refresh(ctx)
        // 兑换后的余额两种码都给，直接用它刷钱包，不用再打一次 /api/me（logto_yxi 2026-09-04）
        if (o.has("balanceCents")) me?.let { m -> me = m.copy(balanceCents = o.optLong("balanceCents")) }
        val replay = o.optBoolean("replay")
        // ⚠️ **必须按 kind 分支**：余额券的 `tier` 给的是**当前档位**、`days` 给 0
        //    （对方为了不让老版本崩才保留这两个字段）。照老写法会说出
        //    「兑换成功：FREE 0 天」这种鬼话 —— 用户兑的明明是钱。
        val msg = when (o.optString("kind")) {
            "balance" -> {
                val got = yuan(o.optLong("amountCents"))
                val now = yuan(o.optLong("balanceCents"))
                if (replay) "这张码你已经兑过了，没有重复到账（当前余额 $now）"
                else "余额到账 $got —— 当前余额 $now"
            }
            else -> {
                val tier = o.optString("tier").uppercase()
                val days = o.optInt("days")
                val until = o.optString("expiresAt").take(10)
                // ⚠️ replay = 同一张码你自己重兑（断网重试就会这样）——**没有重复加天数**，得说清楚
                if (replay) "这张码你已经兑过了，没有重复加天数（到期 $until）"
                else "兑换成功：$tier $days 天，到期 $until"
            }
        }
        Result.success(
            Redeemed(
                msg = msg,
                kind = o.optString("kind").ifEmpty { "membership" },
                tier = when (o.optString("tier")) { "ultra" -> Tier.Ultra; "pro" -> Tier.Pro; else -> Tier.Free },
                expiresAt = o.optString("expiresAt").take(10),
                amountCents = o.optLong("amountCents"),
                balanceCents = o.optLong("balanceCents"),
                replay = replay,
            ),
        )
    }

    /** 一档卖多少钱 */
    data class Plan(val tier: String, val name: String, val price: Int, val days: Int, val desc: String)

    /** `GET /api/purchase` —— **公开接口，不要登录**（未登录也要看得到价格） */
    data class Purchase(
        val currency: String, val plans: List<Plan>, val shopUrl: String,
        val wechatId: String, val wechatName: String, val qrUrl: String, val note: String,
    )

    var purchase by mutableStateOf<Purchase?>(null)
        private set

    suspend fun loadPurchase(): Unit = withContext(Dispatchers.IO) {
        val (c, body) = req("$API/api/purchase", "GET", null, null)
        if (c !in 200..299) return@withContext
        runCatching {
            val o = JSONObject(body)
            val arr = o.optJSONArray("plans")
            val w = o.optJSONObject("wechat")
            purchase = Purchase(
                currency = o.str("currency").ifEmpty { "CNY" },
                plans = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    arr?.optJSONObject(i)?.let {
                        Plan(it.str("tier"), it.str("name"), it.optInt("price"), it.optInt("days"), it.str("desc"))
                    }
                },
                shopUrl = o.str("shopUrl"),
                wechatId = w.str("id"), wechatName = w.str("name"), qrUrl = w.str("qrUrl"),
                note = o.str("note"),
            )
        }
    }

    /**
     * 站内信一页。
     *
     * ⚠️ **游标是 `before`（取比它更旧的），不是 `after`** —— cc-logto_yxi 特意改过来的：
     * 收件箱是从顶上插新信的，用 page/pageSize 会重复或漏，`after` 的含义又容易理解反。
     * 往下翻就把**上一页最后一条的 id** 传进来。
     *
     * @return null = 拿不到（没登录 / 网络不通）。**别把「拿不到」画成「没有信」。**
     */
    /**
     * 领这封信里的附件。
     *
     * ⚠️ 服务端**幂等**：重复领回 `replay: true`，**不重复发**。所以网络超时后可以放心重试。
     * ⚠️ 领取会**顺带标记已读** —— 领了还算未读很奇怪。返回之后界面要把两个标记一起更新。
     * ⚠️ `code` 类附件不在这里发（它本来就什么都不发，只是一串码面）。
     *
     * @return null = 没领成（没登录 / 网络不通 / 服务端拒了）。**别当成领到了**。
     */
    suspend fun claimMail(ctx: Context, id: String): Claim? = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: return@withContext null
        val (c, body) = req("$API/api/mail/$id/claim", "POST", tk, "{}")
        if (c !in 200..299) return@withContext null
        runCatching {
            val o = JSONObject(body)
            val r = Claim(
                replay = o.optBoolean("replay"),
                tickets = o.optInt("tickets", -1),
                balanceCents = o.optLong("balanceCents", Long.MIN_VALUE),
                unclaimed = o.optInt("unclaimed", -1),
            )
            // ⚠️ **就地把服务端刚给的数字用上**，别等下一次 /api/me ——
            //    签到那次踩过：领了曦光，切到祈愿页还是「曦光 ×0」，按钮点不动（见 Wish.parse）。
            me?.let { m ->
                me = m.copy(
                    tickets = if (r.tickets >= 0) r.tickets else m.tickets,
                    balanceCents = if (r.balanceCents != Long.MIN_VALUE) r.balanceCents else m.balanceCents,
                    unclaimedMail = if (r.unclaimed >= 0) r.unclaimed else m.unclaimedMail,
                    unreadMail = m.unreadMail,
                )
            }
            r
        }.getOrNull()
    }

    suspend fun mail(ctx: Context, before: String? = null): Pair<List<Mail>, String?>? =
        withContext(Dispatchers.IO) {
            val tk = token(ctx) ?: return@withContext null
            val url = "$API/api/mail?limit=30" + (before?.let { "&before=$it" } ?: "")
            val (c, body) = req(url, "GET", tk, null)
            if (c !in 200..299) return@withContext null
            runCatching {
                val o = JSONObject(body)
                val arr = o.optJSONArray("items")
                val list = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    arr?.optJSONObject(i)?.let {
                        Mail(
                            id = it.str("id"), kind = it.str("kind"), title = it.str("title"),
                            body = it.str("body"), createdAt = it.str("createdAt"),
                            readAt = it.str("readAt").takeIf { r -> r.isNotEmpty() },
                            fromName = it.optJSONObject("from")?.str("name").orEmpty(),
                            fromAvatar = it.optJSONObject("from")?.str("avatar").orEmpty(),
                            attachments = it.optJSONArray("attachments").let { a ->
                                (0 until (a?.length() ?: 0)).mapNotNull { j ->
                                    a?.optJSONObject(j)?.let { x ->
                                        Attach(x.str("kind"), x.optLong("amount"), x.str("name"))
                                    }
                                }
                            },
                            claimedAt = it.str("claimedAt").takeIf { c -> c.isNotEmpty() },
                        )
                    }
                }
                // 顺手把两个数刷新到 me 上。
                // ⚠️⚠️ **未读和未领是两件事**（cc-logto_yxi 2026-09-05 明确提醒）：
                //    读过了也可能没领。**别合成一个数** —— 合了之后「红点消了但东西还在信里没拿」
                //    这件事就再也说不出来了。
                me?.let { m ->
                    me = m.copy(
                        unreadMail = o.optInt("unread", m.unreadMail),
                        unclaimedMail = o.optInt("unclaimed", m.unclaimedMail),
                    )
                }
                list to o.str("nextCursor").takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

    /**
     * 标一封已读。
     * ⚠️ 契约说它**幂等**，而且标别人的信也照样回 200（探不出 id 存不存在）——
     * 所以别拿返回值当「这封信存在」的证据。
     */
    suspend fun markMailRead(ctx: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: return@withContext false
        val (c, body) = req("$API/api/mail/$id/read", "POST", tk, "")
        if (c !in 200..299) return@withContext false
        runCatching {
            val left = JSONObject(body).optInt("unread", -1)
            if (left >= 0) me?.let { me = it.copy(unreadMail = left) }
        }
        true
    }

    /**
     * 带登录态打一个 GET，成功返回 JSON。
     * ⚠️ 拿不到一律 null（没登录 / 网络不通 / 接口还没上线），**调用方要把整块藏掉**，
     * 不是显示 0 或空列表 —— 「拿不到」和「没有」是两件事。
     */
    suspend fun apiGet(ctx: Context, path: String): JSONObject? = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: return@withContext null
        val (c, body) = req("$API$path", "GET", tk, null)
        if (c !in 200..299) return@withContext null
        runCatching { JSONObject(body) }.getOrNull()
    }

    /** 同 [apiGet]，POST。 */
    suspend fun apiPost(ctx: Context, path: String, body: String): JSONObject? = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: return@withContext null
        val (c, resp) = req("$API$path", "POST", tk, body)
        if (c !in 200..299) return@withContext null
        runCatching { JSONObject(resp) }.getOrNull()
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
            balanceCents = o.optJSONObject("wallet")?.optLong("balanceCents") ?: 0L,
            currency = o.optJSONObject("wallet").str("currency").ifEmpty { "CNY" },
            autoRenew = o.optJSONObject("wallet")?.optBoolean("autoRenew") == true,
            unreadMail = o.optInt("unreadMail", 0),
            unclaimedMail = o.optInt("unclaimedMail", 0),
            tickets = o.optJSONObject("wish")?.optInt("tickets") ?: 0,
            pityRemaining = o.optJSONObject("wish")?.optInt("pityRemaining") ?: 0,
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
            bans = o.optJSONArray("bans")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        Ban(
                            productName = it.str("productName"),
                            reason = it.str("reason").takeIf { r -> r.isNotEmpty() },
                            until = it.str("until").takeIf { u -> u.isNotEmpty() },
                            createdAt = it.str("createdAt"),
                        )
                    }
                }
            }.orEmpty(),
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

    private fun req(url: String, method: String, token: String?, body: String?): Pair<Int, String> {
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
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
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

    /** 分 → 「¥12.34」。⚠️ 只在**显示的这一刻**除 100，别在别处提前转成小数。 */
    /**
     * 分 → 「¥12.34」。⚠️ **余额可能是负数**（撤销兑换券能把它扣穿 —— 服务端只允许
     * `reason=refund` 那条路扣成负数，用户主动消费仍然不许透支）。所以负号在**货币符号前面**：
     * `-¥55.00`，不是 `¥-55.00` —— 后者读起来像这笔钱本身叫「-55」。
     *
     * ⚠️⚠️ **全项目只有这一份。** 2026-09-05 查出来时同一个语义写了**三份**、错了两份，
     * 而且第三份在另一个包里还是 private —— 从 bug 现场根本数不出来。
     * 教训（cc-Yxi_pilot）：**这类收敛做完要再 grep 函数名本身（`fun yuan`），不是 grep 使用现场**；
     * 「同一个语义各写一份」的真实份数往往比你数出来的多一份。
     *
     * ⚠️ 放在 agent 层是因为**依赖方向**：agent 不能反过来 import ui，而 ui import agent 到处都是。
     */
    internal fun yuan(cents: Long): String =
        (if (cents < 0L) "-¥" else "¥") + "%.2f".format(kotlin.math.abs(cents) / 100.0)

    private fun randomUrlSafe(n: Int): String {
        val b = ByteArray(n); SecureRandom().nextBytes(b); return b64url(b)
    }

    private fun b64url(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
