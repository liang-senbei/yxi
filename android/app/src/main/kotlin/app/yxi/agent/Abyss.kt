package app.yxi.agent

import android.content.Context
import app.yxi.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * **深渊** —— 星穹铁道「忘却之庭 · 混沌回忆」的大幅简化版（老板 2026-09-05 拍板，PRD：
 * `Yxi_Entertainment/design/abyss-prd.md`；服务端契约 `logto_yxi/design/abyss.md`，**以契约为准**）。
 *
 * 一期（半月）一座 12 层的塔。每层分上半 / 下半，各派 0–[Rules.teamSize] 位自己拥有的角色（同层不重复），
 * 每半自带一个「旅人」底力单位。**结算是确定性的，没有随机数**：
 * 每半 `r = 战力 / 难度`，上半 r ≥ passRatio 一星、下半一星、两半都 ≥ bonusRatio 再一星，共 0–3 星。
 * 累计星数每过 rewardEvery 的倍数服务端自动发曦光，满星再加 fullReward。
 *
 * ⚠️ **服务端是唯一真相**：星数、发奖、归属全在服务端算。客户端只做两件事：
 *  1. 把服务端给的状态画出来；
 *  2. 配队时**预估**星数给人看（[estimate]）—— 公式照契约，**所有数值和性格词都用 GET 下发的**
 *     （`rules` / `roster[].traits` / `floors[].weak` / `season.turbulence.traits`），不自己维护一份，
 *     改名就脱节。结果页永远显示服务端回的星数；两边对不上就是 bug，E2E 会抓到。
 * ⚠️ 拿不到（没登录 / 网络不通 / 接口没上线）一律 null，界面说「取不到」不说「没有」。
 */
object Abyss {
    private const val BASE = "/api/abyss"

    /** 本期紊流：命中 [traits] 任一的角色战力 ×[Rules.turbMult]。[mult] 只用来显示。 */
    data class Turbulence(val name: String, val text: String, val traits: List<String>, val mult: Double)

    /** 一层的一半：两个弱点性格 + 难度 */
    data class Half(val weak: List<String>, val d: Int)

    data class Floor(
        val n: Int,
        val up: Half,
        val down: Half,
        /** 我这层的最佳星数 0–3 */
        val myStars: Int,
        /** 记录里的配队（角色 id） */
        val myUp: List<String>,
        val myDown: List<String>,
    )

    /** 我拥有的一位角色。[dup] = 命座；[power] = 服务端算好的**未乘倍率**的基础战力（已含命座） */
    data class Roster(val id: String, val name: String, val dup: Int, val traits: List<String>, val power: Int)

    data class Season(val id: String, val seq: Int, val name: String, val startsAt: String, val endsAt: String, val turbulence: Turbulence?)

    /** 满星那档额外发的：`tickets` 带 [amount]；`cosmetic` 带 [id]（素材没到时服务端先放 tickets 占位） */
    data class FullReward(val kind: String, val amount: Long, val id: String)

    /**
     * 规则数值，服务端 `abyss.json` 原样下发；客户端预估**只能**用这里的数。
     * [base] / [perDup] / [maxDup] 目前客户端不用（角色战力直接读服务端算好的 `roster[].power`），留着给以后的战力明细。
     */
    data class Rules(
        val base: Int, val perDup: Int, val maxDup: Int,
        val weakMult: Double, val turbMult: Double,
        val teamSize: Int, val passRatio: Double, val bonusRatio: Double,
        val rewardEvery: Int, val rewardTickets: Int, val maxStars: Int,
        val fullReward: FullReward,
    )

    data class State(
        val season: Season,
        val floors: List<Floor>,
        val totalStars: Int,
        /** 已经发过奖的星数阈值，如 [3, 6, 9] */
        val claimed: List<Int>,
        /** 本期打过没 —— 活动中心入口的红点就是它的反面 */
        val played: Boolean,
        val roster: List<Roster>,
        val travelerPower: Int,
        val rules: Rules,
    )

    data class Side(val power: Int, val ratio: Double)

    /** 一档发出去的东西 */
    data class GrantItem(val kind: String, val amount: Long, val id: String, val name: String)
    data class Grant(val threshold: Int, val items: List<GrantItem>)

    /** 一次挑战的结果。[best] = 这层的最佳星数（可能比 [stars] 高）；[granted] 只列**这次**新发的档 */
    data class Result(
        val floor: Int,
        val stars: Int,
        val best: Int,
        val up: Side,
        val down: Side,
        val totalStars: Int,
        val granted: List<Grant>,
    )

    suspend fun state(ctx: Context): State? = withContext(Dispatchers.IO) {
        val o = Account.apiGet(ctx, BASE) ?: return@withContext null
        runCatching { parseState(o) }.getOrNull()
    }

    sealed class Outcome {
        data class Ok(val result: Result) : Outcome()
        /** 给人看的原因：服务端 4xx 的 `msg` 原样，网络问题另说 */
        data class Failure(val message: String) : Outcome()
    }

    /** 挑战第 [n] 层。⚠️ 同层重复 / 没拥有 / 超员服务端回 400 —— 界面本来就不让选，这里只是把原因说出来。 */
    suspend fun challenge(ctx: Context, n: Int, up: List<String>, down: List<String>): Outcome = withContext(Dispatchers.IO) {
        val body = JSONObject().put("up", JSONArray(up)).put("down", JSONArray(down)).toString()
        val (code, resp) = Account.apiRaw(ctx, "$BASE/floors/$n", "POST", body)
            ?: return@withContext Outcome.Failure(t("没登录"))
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(resp).optString("msg") }.getOrNull().orEmpty()
            return@withContext Outcome.Failure(
                if (msg.isNotBlank()) msg else t("没打成 —— 网络不通，或者登录过期了"),
            )
        }
        runCatching { Outcome.Ok(parseResult(JSONObject(resp))) }
            .getOrElse { Outcome.Failure(t("读不懂服务器的回复")) }
    }

    // ── 客户端预估（只用于显示「预计」，服务端结果为准；公式照契约「规则」一节）────────

    /** 一半的预估战力：旅人 + Σ 角色（基础含命座 × 弱点命中 × 紊流命中），四舍五入到整数 */
    fun power(ids: List<String>, half: Half, st: State): Int {
        var p = st.travelerPower.toDouble()
        val turb = st.season.turbulence?.traits.orEmpty()
        for (id in ids) {
            val r = st.roster.firstOrNull { it.id == id } ?: continue
            var v = r.power.toDouble()
            if (r.traits.any { it in half.weak }) v *= st.rules.weakMult
            if (r.traits.any { it in turb }) v *= st.rules.turbMult
            p += v
        }
        return Math.round(p).toInt()
    }

    /** 星数：上半过 1、下半过 1、两半都 ≥ bonusRatio 再 1 */
    fun stars(rUp: Double, rDown: Double, rules: Rules): Int {
        var s = 0
        if (rUp >= rules.passRatio) s++
        if (rDown >= rules.passRatio) s++
        if (rUp >= rules.bonusRatio && rDown >= rules.bonusRatio) s++
        return s
    }

    /** 预估这一层用这两队能拿几星：`Triple(星数, 上半比, 下半比)` */
    fun estimate(f: Floor, up: List<String>, down: List<String>, st: State): Triple<Int, Double, Double> {
        val ru = if (f.up.d > 0) power(up, f.up, st) / f.up.d.toDouble() else 0.0
        val rd = if (f.down.d > 0) power(down, f.down, st) / f.down.d.toDouble() else 0.0
        return Triple(stars(ru, rd, st.rules), ru, rd)
    }

    // ── 解析（字段缺了给默认值，不抛；服务端多给字段随时可能发生）──────────────────

    internal fun parseState(o: JSONObject): State {
        val s = o.optJSONObject("season") ?: JSONObject()
        val tb = s.optJSONObject("turbulence")?.let {
            Turbulence(it.optString("name"), it.optString("text"), it.optJSONArray("traits").strings(), it.optDouble("mult", 1.0))
        }?.takeIf { it.traits.isNotEmpty() || it.text.isNotBlank() }
        val ru = o.optJSONObject("rules") ?: JSONObject()
        val fr = ru.optJSONObject("fullReward")
        return State(
            season = Season(s.optString("id"), s.optInt("seq"), s.optString("name"), s.optString("startsAt"), s.optString("endsAt"), tb),
            floors = o.optJSONArray("floors").list { f ->
                Floor(
                    n = f.optInt("n"),
                    up = half(f.optJSONObject("up")), down = half(f.optJSONObject("down")),
                    myStars = f.optInt("myStars"),
                    myUp = f.optJSONObject("myTeams")?.optJSONArray("up").strings(),
                    myDown = f.optJSONObject("myTeams")?.optJSONArray("down").strings(),
                )
            },
            totalStars = o.optInt("totalStars"),
            claimed = o.optJSONArray("claimed").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optInt(it) } },
            played = o.optBoolean("played"),
            roster = o.optJSONArray("roster").list { r ->
                Roster(r.optString("id"), r.optString("name"), r.optInt("dup"), r.optJSONArray("traits").strings(), r.optInt("power"))
            },
            travelerPower = o.optInt("travelerPower"),
            rules = Rules(
                base = ru.optInt("base", 120), perDup = ru.optInt("perDup", 30), maxDup = ru.optInt("maxDup", 6),
                weakMult = ru.optDouble("weakMult", 1.5), turbMult = ru.optDouble("turbMult", 1.5),
                teamSize = ru.optInt("teamSize", 2), passRatio = ru.optDouble("passRatio", 1.0), bonusRatio = ru.optDouble("bonusRatio", 1.3),
                rewardEvery = ru.optInt("rewardEvery", 3), rewardTickets = ru.optInt("rewardTickets", 2), maxStars = ru.optInt("maxStars", 36),
                fullReward = FullReward(fr?.optString("kind").orEmpty(), fr?.optLong("amount") ?: 0L, fr?.optString("id").orEmpty()),
            ),
        )
    }

    internal fun parseResult(o: JSONObject): Result = Result(
        floor = o.optInt("floor"),
        stars = o.optInt("stars"),
        best = o.optInt("best", o.optInt("stars")),
        up = side(o.optJSONObject("up")),
        down = side(o.optJSONObject("down")),
        totalStars = o.optInt("totalStars"),
        granted = o.optJSONArray("granted").list { g ->
            Grant(
                g.optInt("threshold"),
                g.optJSONArray("items").list { i -> GrantItem(i.optString("kind"), i.optLong("amount"), i.optString("id"), i.optString("name")) },
            )
        },
    )

    private fun half(o: JSONObject?): Half = Half(o?.optJSONArray("weak").strings(), o?.optInt("d") ?: 0)
    private fun side(o: JSONObject?): Side = Side(o?.optInt("power") ?: 0, o?.optDouble("ratio") ?: 0.0)

    private fun JSONArray?.strings(): List<String> =
        (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optString(i)?.takeIf { it.isNotEmpty() } }

    private inline fun <T> JSONArray?.list(f: (JSONObject) -> T): List<T> =
        (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i)?.let(f) }
}
