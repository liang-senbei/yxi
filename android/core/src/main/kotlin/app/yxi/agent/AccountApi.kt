package app.yxi.agent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * **账号服务的可共用那一层**:数据模型 + HTTP + 解析。手机和 Windows 桌面**共用同一份**。
 *
 * 为什么要拆出来(老板 2026-09-08 派的桌面版):`Account` 里一半是 Android
 * (Context / SharedPreferences / Intent / Compose 状态),一半是纯逻辑(打哪个 URL、
 * 怎么解 `/api/me`、错误码翻成哪句人话)。**后一半两个端一模一样** ——
 * 各写一份的话,服务端加个字段就要改两处,迟早分叉(这个项目已经吃过一次:
 * 同一个「分转元」写了三份、错了两份)。
 *
 * ⚠️ **这里不许有任何登录态**:每个接口都收一个 `token`,谁去拿、存哪儿,由各端自己决定
 * (Android 存加密的 SharedPreferences,桌面存 `%APPDATA%\\Yxi`)。
 * ⚠️ **不许 import android.\***(core 的铁律)。Base64 用 `java.util.Base64`,
 * 日志走 `Plat`,翻译走 `Tr`。
 */
object AccountApi {

    const val AUTH = "https://auth.yxi.keuury.com"
    const val API = "https://api.yxi.keuury.com"
    const val APP_ID = "g6ydpvvn833z0ua19n6hz"
    const val SCOPES = "openid offline_access profile email custom_data roles"

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
        /**
         * 登录方式，给「账号中心」显示「这个邮箱哪来的」用（契约 `logto_yxi/design/account.md`，
         * 2026-09-06）：`email` | `google` | `github` | **以后还会冒出新的连接器名**。
         * ⚠️ **别写穷举 `when`**：认不出来的值就只显示邮箱、不显示来源（logto_yxi 明确交代过）。
         * 空 = 服务端还没给这个字段（老版本），同样按「不显示来源」处理。
         */
        val signInWith: String = "",
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
        /** 当前档续一期要多少分（服务端算的，free 是 null）。开关文案「到期自动扣 ¥30」用它，**不自己乘** */
        val autoRenewPriceCents: Long? = null,
        /** 未读站内信条数 —— 图标上那个红点靠它，不然要进去才知道有信 */
        val unreadMail: Int = 0,
        /**
         * 有附件但**还没领**的信有几封。
         * ⚠️ 跟 [unreadMail] **是两回事**：读过了也可能没领。界面上分开说，别合并成一个数。
         */
        val unclaimedMail: Int = 0,
        /** 有我没看过的官方回复的工单数 —— 工单格红点靠它。同 [unreadMail]：服务端给的，客户端不自己数。 */
        val unreadTickets: Int = 0,
        /** 手上几张曦光（祈愿用） */
        val tickets: Int = 0,
        /**
         * 手上几点微曦 —— 重复返还的**零头**，满 [microPerTicket] 点服务端**自动**变成 1 张曦光，
         * 所以这个数永远小于换算基数。⚠️ 它不是"第二种货币"，只是曦光的小数位。
         */
        val micro: Int = 0,
        /** 几点微曦换 1 张曦光（服务端给，**别在客户端写死 10**——这是奖池旋钮，改了不发版）。 */
        val microPerTicket: Int = 10,
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
        /**
         * 这封信什么时候会被自动清掉（= 发信时间 + 保留期）。空 = 服务端没给（老版本）。
         * ⚠️ 到期时**能领的三种附件会自动入账**（曦光 / 余额 / 会员天数），用户不点也不亏；
         *    **兑换码不会** —— 码在后台照旧有效，但信删了就不再显示，
         *    送的码只有信这一处（买来的在钱包 → 订单记录里另有一份）。
         */
        val expiresAt: String? = null,
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
    data class Attach(val kind: String, val amount: Long, val name: String, val code: String = "") {
        /**
         * 给用户看、给用户抄的那一行。
         * ⚠️ **码在 [code] 字段里，不在 [name] 里** —— `name` 是「兑换码」或「PRO 会员 30 天」这种标签。
         *    原来界面显示和长按复制的都是 `name`，等于让人抄一个屏幕上没有的东西
         *    （买来的信更荒唐：复制到的是「PRO 会员 30 天」这七个字）。
         */
        val shown: String get() = code.ifEmpty { name }

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

    /** 一档卖多少钱 */
    data class Plan(val tier: String, val name: String, val price: Int, val days: Int, val desc: String)

    /** `GET /api/purchase` —— **公开接口，不要登录**（未登录也要看得到价格） */
    data class Purchase(
        val currency: String, val plans: List<Plan>, val shopUrl: String,
        val wechatId: String, val wechatName: String, val qrUrl: String, val note: String,
    )

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
    fun parseMe(o: JSONObject): Me {
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
            signInWith = pr.str("signInWith"),
            balanceCents = o.optJSONObject("wallet")?.optLong("balanceCents") ?: 0L,
            autoRenewPriceCents = o.optJSONObject("wallet")?.let { w -> if (w.isNull("autoRenewPriceCents")) null else w.optLong("autoRenewPriceCents") },
            currency = o.optJSONObject("wallet").str("currency").ifEmpty { "CNY" },
            autoRenew = o.optJSONObject("wallet")?.optBoolean("autoRenew") == true,
            unreadMail = o.optInt("unreadMail", 0),
            unclaimedMail = o.optInt("unclaimedMail", 0),
            unreadTickets = o.optInt("unreadTickets", 0),
            tickets = o.optJSONObject("wish")?.optInt("tickets") ?: 0,
            micro = o.optJSONObject("wish")?.optInt("micro") ?: 0,
            // ⚠️ 默认值要写在 `optInt` 里,不能只靠 `?:` —— elvis 只在 `wish` **整个对象缺失**时触发;
            //    对象在、键不在时 `optInt(name)` 返回 **0**,于是换算基数成 0,
            //    文案会变成「折 5 微曦（）」「满 0 点自动换 1 张」。
            microPerTicket = o.optJSONObject("wish")?.optInt("microPerTicket", 10) ?: 10,
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


    /** 服务端的错都带中文 msg / 有约定的 error 码，翻译成一句人话 */
    /**
     * 服务端的错都带中文 msg / 有约定的 error 码,翻译成一句人话。
     * @param fmtDate 把 ISO 时间格式化成人看的样子。**由调用方传** —— 手机端那份带时区设置(Tz),
     *   是 Android 状态,不能进 core;不传就原样显示,不影响意思。
     */
    fun httpErr(c: Int, body: String, fmtDate: (String) -> String = { it }): String {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val err = o.str("error")
        val msg = o.str("msg")
        return when {
            // ⚠️ **服务端的 `msg` 优先**（logto_yxi 2026-09-04）：它是中文、由他们维护、
            //    永远跟真实原因一致。比如同一个 409 会分「被别人使用了」和「名额已经领完了」——
            //    本地写死一句就会说错。下面那些只是**服务端没给 msg 时**的兜底。
            msg.isNotEmpty() -> msg
            err == "quota_exhausted" -> {
                val next = o?.optString("nextRefreshAt")?.takeIf { it.isNotEmpty() }?.let(fmtDate).orEmpty()
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

    fun req(url: String, method: String, token: String?, body: String?): Pair<Int, String> {
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

    fun form(url: String, fields: Map<String, String>): Pair<Int, String> {
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
    fun yuan(cents: Long): String =
        (if (cents < 0L) "-¥" else "¥") + "%.2f".format(kotlin.math.abs(cents) / 100.0)

    /** URL-safe Base64,不带 padding。⚠️ 用 `java.util.Base64`,不是 `android.util.Base64` —— core 不许碰 android.* */
    fun b64url(b: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun randomUrlSafe(n: Int): String {
        val b = ByteArray(n); SecureRandom().nextBytes(b); return b64url(b)
    }

    /** PKCE 的 code_challenge(S256) */
    fun challengeOf(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
}

/**
 * JSON 里的可空字符串:null / "null" / 没这个键 一律当空串。
 *
 * ⚠️ **写在包级不是 object 里**:写进 object 的扩展函数只有 object 内部能用,
 * 而 app 和 desktop 都要用它解自己的 JSON(2026-09-08 抽 core 时就是这么撞的,203 条报错全从它来)。
 * ⚠️ 为什么需要它:`optString` 读到 JSON null 会返回**字符串 "null"**(不是空串)——
 * 实测签名没设的账号,编辑框里赫然写着 `null`(#230)。
 */
fun JSONObject?.str(key: String): String {
    if (this == null || isNull(key)) return ""
    val v = optString(key)
    return if (v == "null") "" else v
}
