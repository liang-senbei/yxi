package app.yxi.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 祈愿（抽奖）与签到。接口契约：`logto_yxi/design/wish-checkin.md`（2026-09-04 定稿）。
 *
 * ⚠️⚠️ **摇号在服务端，客户端只放动画。**
 * 奖池里有真东西（会员天数），客户端摇 = 改个包就能中头奖。
 * 这里每个函数都只是「把服务端的结果取回来」，**一行随机数都没有**，以后也别加。
 *
 * ⚠️ **概率由服务端给**（[Item.rate]），概率公示页读的**就是这个接口**。
 * 公示页自己写死数字 = 改了概率忘了改页面就是骗人。
 *
 * ⚠️ 接口没上线时所有函数返回 null，界面显示「还没开通」——
 * **不显示 0、不画空日历**（跟 [Usage] 同一条规矩：「拿不到」和「没有」是两件事）。
 */
object Wish {

    /** 奖池。[pityAt] = 多少抽必出稀有；⚠️ 这个数**只在这里**有，`/api/me` 里不重复放。 */
    data class Pool(
        val name: String,
        val version: String,
        val pityAt: Int,
        val items: List<Item>,
        /**
         * **这个人自己的价**（ultra 十连 9，其余 10）。
         * ⚠️ **照抄服务端给的数，别自己乘 0.9** —— 客户端算折扣 = 改包就能白嫖；
         * 而且 cc-logto_yxi 实现时踩到过「页面说 9、实际扣 10」（两处判据不一致），
         * 唯一不出错的办法就是**显示的和扣的是同一个数**。
         */
        val tenPullCost: Int = 10,
        val singlePullCost: Int = 1,
    )

    /** 奖池里的一项。[rate] 是服务端给的中奖率（0~1），只用来公示。 */
    data class Item(
        val id: String,
        val name: String,
        val rarity: String,
        val rate: Double,
        val kind: String,
        val amount: Long,
    )

    /**
     * 抽出来的一个。[kind] ∈ `cosmetic` / `tickets` / `membership_days`
     * （**没有 `balance_cents`** —— 余额等价现金，不能挂在签到这个免费水龙头下游）。
     * [dupConvertedTo] = 重复的装饰品折成了什么，`new = false` 时才有。
     */
    data class Got(
        val id: String,
        val name: String,
        val rarity: String,
        val kind: String,
        val amount: Long,
        val isNew: Boolean,
        val dupConvertedTo: Pair<String, Long>?,
    )

    /** 一次祈愿的结果。[replay] = 这次是重试命中了幂等，**没有再扣曦光**。 */
    data class Draw(
        val results: List<Got>,
        val tickets: Int,
        val pityRemaining: Int,
        val drawId: Long,
        val replay: Boolean,
        /** 这一次**真扣了**几张曦光（服务端算的，含 ultra 折扣） */
        val cost: Int = 0,
    )

    /** 签到状态。[calendar] 最近 30 天，新到旧。 */
    data class CheckIn(
        val checkedInToday: Boolean,
        val streak: Int,
        val nextReward: String,
        val calendar: List<Boolean>,
        val replay: Boolean = false,
        /** 签完之后手上有几张曦光 —— 服务端顺带给的，**拿它就地更新，别等下一次 /api/me** */
        val tickets: Int = -1,
    )

    suspend fun pool(ctx: Context): Pool? = withContext(Dispatchers.IO) {
        val o = Account.apiGet(ctx, "/api/wish/pool") ?: return@withContext null
        runCatching {
            Pool(
                name = o.optString("name"),
                version = o.optString("version"),
                pityAt = o.optInt("pityAt"),
                tenPullCost = o.optInt("tenPullCost", 10),
                singlePullCost = o.optInt("singlePullCost", 1),
                items = o.optJSONArray("items").list {
                    Item(
                        it.optString("id"), it.optString("name"), it.optString("rarity"),
                        it.optDouble("rate"), it.optString("kind"), it.optLong("amount"),
                    )
                },
            )
        }.getOrNull()
    }

    /**
     * 祈愿一次或十次。
     *
     * ⚠️⚠️ **[requestId] 是必须的，而且重试要用同一个。** 抽奖**不是**幂等操作 ——
     * 每抽扣一张曦光，网络超时后再点一次就会**扣两次**。服务端按它去重，
     * 重试会返回同一批结果并带 `replay: true`。
     * （跟钱包流水 `(reason, ref)` 唯一索引是同一招：**凡是会扣掉东西的接口都要带幂等键**。）
     */
    suspend fun draw(ctx: Context, count: Int, requestId: String): Draw? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("count", count).put("requestId", requestId).toString()
        val o = Account.apiPost(ctx, "/api/wish/draw", body) ?: return@withContext null
        runCatching {
            Draw(
                results = o.optJSONArray("results").list {
                    Got(
                        it.optString("id"), it.optString("name"), it.optString("rarity"),
                        it.optString("kind"), it.optLong("amount"), it.optBoolean("new"),
                        it.optJSONObject("dupConvertedTo")?.let { d -> d.optString("kind") to d.optLong("amount") },
                    )
                },
                tickets = o.optInt("tickets"),
                pityRemaining = o.optInt("pityRemaining"),
                drawId = o.optLong("drawId"),
                replay = o.optBoolean("replay"),
                cost = o.optInt("cost"),
            )
        }.getOrNull()
    }

    /**
     * 我拥有哪些角色 / 装扮。
     *
     * ⚠️⚠️ **归属存服务端，不存本机**（cc-logto_yxi 2026-09-04 定的，理由很硬）：
     * 素材在包里没问题，但「有没有」只能是服务端说了算 —— 今天刚因为换签名密钥
     * 让所有人卸载重装过一次，本地存的收藏那会儿就全没了。**收藏是抽卡的全部意义。**
     *
     * @return null = 拿不到（没登录 / 接口没上线）。**别把「拿不到」当成「一张都没有」**，
     *   那会让人以为收藏丢了。
     */
    suspend fun collection(ctx: Context): Set<String>? = withContext(Dispatchers.IO) {
        val o = Account.apiGet(ctx, "/api/wish/collection") ?: return@withContext null
        runCatching {
            val a = o.optJSONArray("items") ?: o.optJSONArray("owned")
            (0 until (a?.length() ?: 0)).mapNotNull { i ->
                a?.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }
                    ?: a?.optString(i)?.takeIf { it.isNotEmpty() }
            }.toSet()
        }.getOrNull()
    }

    suspend fun checkIn(ctx: Context): CheckIn? = withContext(Dispatchers.IO) {
        parse(Account.apiGet(ctx, "/api/checkin") ?: return@withContext null)
    }

    /**
     * 签今天。
     * ⚠️ 服务端幂等，唯一键是 `(用户, 北京时间的日期)`：重复签回 `replay: true`，**不重复发奖**。
     * ⚠️ 连续天数的规则是定死的：按 **+08:00** 自然日、**断一天归零**、无补签无宽限。
     */
    suspend fun signToday(ctx: Context): CheckIn? = withContext(Dispatchers.IO) {
        parse(Account.apiPost(ctx, "/api/checkin", "{}") ?: return@withContext null)
    }

    private fun parse(o: JSONObject): CheckIn? = runCatching {
        CheckIn(
            checkedInToday = o.optBoolean("checkedInToday"),
            streak = o.optInt("streak"),
            nextReward = o.optJSONObject("nextReward")?.let { r ->
                val n = r.optLong("amount")
                when (r.optString("kind")) {
                    "tickets" -> "曦光 ×$n"
                    "membership_days" -> "会员 $n 天"
                    "cosmetic" -> r.optString("name")
                    else -> ""
                }
            }.orEmpty(),
            // GET 给的是 [{date, checked}]，POST 不带日历
            calendar = o.optJSONArray("calendar").list { it.optBoolean("checked") },
            replay = o.optBoolean("replay"),
            tickets = o.optInt("tickets", -1),
        ).also { ci ->
            // ⚠️ **签到发的曦光要立刻回灌到 [Account.me]**。不然「签到拿了 1 张曦光」之后
            //    切到祈愿页还是「曦光 ×0」，按钮点不动 —— 实测就是这个表现。
            //    真相源仍然是服务端，这里只是把它刚给的数字就地用上，不等下一次 /api/me。
            if (ci.tickets >= 0) Account.me?.let { m -> Account.setTickets(m.tickets, ci.tickets) }
        }
    }.getOrNull()

    private inline fun <T> JSONArray?.list(f: (JSONObject) -> T): List<T> =
        (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i)?.let(f) }
}
