package app.yxi.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 音游「云曦节拍」的数据层：曲目、谱面、成绩、判定与计分规则。
 * 玩法和渲染在 [app.yxi.ui.RhythmScreen]，PRD 在 `Yxi_Entertainment/design/rhythm-prd.md`。
 *
 * ⚠️ **曲子是我们自己合成的**（生成脚本 `Yxi_Entertainment/design/music/make_songs.py`，
 *    音符和弦鼓点都写在脚本里），版权归我们。老板 2026-09-06 问过侵权的事：
 *    别人的歌一首都不能扒 —— 词曲著作权 + 录音制作者权是两层权利，音游还是完整播放、
 *    曲名曲师明写在选曲页，取证成本为零。要真人做的曲子走约稿买断，别从音乐平台拿。
 *
 * ⚠️ 谱面是从**同一份乐谱**导出来的，所以和音频天生对齐，不存在"手动对拍对歪了"这种事。
 */
object Rhythm {

    private const val BASE_PATH = "/api/rhythm"

    /**
     * 计分参数。⚠️ **服务端才是唯一的一份**（`logto_yxi/design/rhythm.md`）：
     * 结算页显示的分数以 [submit] 的返回为准，这里的默认值只用来在服务端回话之前先给玩家看个数。
     * 分母一律是 [Chart.units]（长按算两个判定），跟服务端一致。
     */
    data class Rules(
        val base: Float = 1_000_000f, val goodFactor: Float = 0.65f, val comboBonus: Float = 100_000f,
        val gradeS: Float = 0.96f, val gradeA: Float = 0.90f, val gradeB: Float = 0.80f,
    )

    @Volatile var rules = Rules(); private set

    /** 判定窗口（毫秒）。比常见音游宽一点：这是活动里随手玩两把的东西，不是竞技谱 */
    const val PERFECT_MS = 80f
    const val GOOD_MS = 160f

    /** 音符从冒头到判定线的时间（秒）。越大越"慢"、越好读谱 */
    const val APPROACH = 1.6f

    enum class Judge { PERFECT, GOOD, MISS }

    data class Note(
        val t: Float,          // 秒，音符该被击中的时刻
        val lane: Int,         // 0..3
        val hold: Boolean,
        val dur: Float,        // 秒，长按时长；不是长按就是 0
    ) {
        var judged: Judge? = null
        var tailDone = false   // 长按尾巴判过没有
    }

    data class Chart(
        val song: String, val zh: String, val bpm: Int,
        val difficulty: String, val offset: Float, val notes: List<Note>,
    ) {
        val id get() = "${song}_$difficulty"
        /** 长按算两个判定（头 + 尾），满分按这个数分 */
        val units get() = notes.size + notes.count { it.hold }
    }

    data class Song(val id: String, val zh: String, val raw: Int, val bpm: Int, val seconds: Int)

    /** 三首曲子。加曲子 = 跑一次生成脚本 + 往这儿加一行 */
    val SONGS = listOf(
        Song("snow", "初雪", app.yxi.R.raw.yx_snow, 96, 63),
        Song("yunxi", "云曦", app.yxi.R.raw.yx_yunxi, 128, 63),
        Song("abyss", "入渊", app.yxi.R.raw.yx_abyss, 152, 66),
    )

    val DIFFS = listOf("easy", "hard")

    fun load(ctx: Context, song: String, difficulty: String): Chart {
        val j = JSONObject(ctx.assets.open("charts/chart_${song}_$difficulty.json").use { it.readBytes() }.decodeToString())
        val arr = j.getJSONArray("notes")
        val notes = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dur = o.optDouble("dur", 0.0).toFloat()
            notes += Note(o.getDouble("t").toFloat(), o.getInt("lane"), o.optString("type") == "hold", dur)
        }
        return Chart(j.getString("song"), j.getString("zh"), j.getInt("bpm"),
            j.getString("difficulty"), j.optDouble("offset", 0.0).toFloat(), notes)
    }

    // ── 计分（确定性，跟深渊一个原则：不掺随机）─────────────────────────────
    data class Result(
        val score: Int, val acc: Float, val rank: String,
        val perfect: Int, val good: Int, val miss: Int, val maxCombo: Int,
        /** 这局所有命中的偏差中位数（毫秒，正 = 偏晚）。用来提示校准 */
        val medianErrMs: Int,
    )

    fun score(units: Int, perfect: Int, good: Int, maxCombo: Int): Int {
        if (units <= 0) return 0
        val per = rules.base / units
        return (per * perfect + per * rules.goodFactor * good + maxCombo.toFloat() / units * rules.comboBonus).roundToInt()
    }

    fun accuracy(units: Int, perfect: Int, good: Int): Float =
        if (units <= 0) 0f else (perfect + rules.goodFactor * good) / units

    fun rank(acc: Float): String = when {
        acc >= rules.gradeS -> "S"
        acc >= rules.gradeA -> "A"
        acc >= rules.gradeB -> "B"
        else -> "C"
    }

    fun judge(errMs: Float): Judge = when {
        kotlin.math.abs(errMs) <= PERFECT_MS -> Judge.PERFECT
        kotlin.math.abs(errMs) <= GOOD_MS -> Judge.GOOD
        else -> Judge.MISS
    }

    // ── 服务端（契约 logto_yxi/design/rhythm.md）─────────────────────────────
    /**
     * 拉规则和各谱最好成绩。**分数不是客户端说了算**：cc-logto_yxi 2026-09-06 定的，
     * 客户端只报判定计数，分数/准度/评级由服务端用同一套公式算 —— 不然改个包就能把六张谱全 S 领完。
     * 拿不到就用本地那份（离线也能玩，只是不发奖）。
     */
    suspend fun sync(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val o = Account.apiGet(ctx, BASE_PATH) ?: return@withContext false
        runCatching {
            o.optJSONObject("rules")?.let { r ->
                val g = r.optJSONObject("grades")
                rules = Rules(
                    r.optDouble("base", 1_000_000.0).toFloat(),
                    r.optDouble("goodFactor", 0.65).toFloat(),
                    r.optDouble("comboBonus", 100_000.0).toFloat(),
                    (g?.optDouble("S", 0.96) ?: 0.96).toFloat(),
                    (g?.optDouble("A", 0.90) ?: 0.90).toFloat(),
                    (g?.optDouble("B", 0.80) ?: 0.80).toFloat(),
                )
            }
            val arr = o.optJSONArray("charts") ?: return@runCatching true
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val b = c.optJSONObject("best") ?: continue
                // 服务端的成绩是权威的，本地存档只是离线时的镜子
                p(ctx).edit().putString(
                    "rhythm.best.${c.getString("id")}",
                    "${b.optInt("score")}|${b.optDouble("accuracy").toFloat()}|${b.optString("rating")}|${b.optInt("maxCombo")}",
                ).apply()
            }
            true
        }.getOrDefault(false)
    }

    /** 交这一局的判定计数。返回服务端算出来的成绩 + 这次发了什么；网络不通返回 null（成绩只留本地）。 */
    suspend fun submit(
        ctx: Context, chartId: String, perfect: Int, good: Int, miss: Int, maxCombo: Int, elapsedMs: Int,
    ): Submitted? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("chartId", chartId).put("perfect", perfect).put("good", good).put("miss", miss)
            .put("maxCombo", maxCombo).put("elapsedMs", elapsedMs)
            .put("requestId", UUID.randomUUID().toString()).toString()
        val (code, resp) = Account.apiRaw(ctx, "$BASE_PATH/plays", "POST", body) ?: return@withContext null
        runCatching {
            val o = JSONObject(resp)
            if (code !in 200..299) return@runCatching Submitted(null, o.optString("msg").ifBlank { null }, emptyList())
            val items = ArrayList<String>()
            o.optJSONArray("granted")?.let { g ->
                for (i in 0 until g.length()) {
                    val one = g.getJSONObject(i).optJSONArray("items") ?: continue
                    for (k in 0 until one.length()) {
                        val it = one.getJSONObject(k)
                        items += if (it.optString("kind") == "cosmetic") it.optString("name")
                        else "${it.optString("kind")} ×${it.optInt("amount")}"
                    }
                }
            }
            Submitted(
                Result(o.optInt("score"), o.optDouble("accuracy").toFloat(), o.optString("rating"),
                    perfect, good, miss, o.optInt("maxCombo", maxCombo), 0),
                null, items,
            )
        }.getOrNull()
    }

    /** [result] 有值 = 服务端算好的成绩；[error] 有值 = 服务端不认这局（原文直接给人看） */
    data class Submitted(val result: Result?, val error: String?, val granted: List<String>)

    // ── 存档（本地镜子；服务端在的时候以服务端为准）────────────────────────────
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    /** 某张谱的最好成绩，没打过是 null */
    fun best(ctx: Context, chartId: String): Result? {
        val s = p(ctx).getString("rhythm.best.$chartId", null) ?: return null
        val a = s.split('|')
        if (a.size < 4) return null
        return Result(a[0].toInt(), a[1].toFloat(), a[2], 0, 0, 0, a[3].toInt(), 0)
    }

    /** 比原来好才覆盖（跟深渊「打平或更好才更新」一致），返回是不是新纪录 */
    fun saveBest(ctx: Context, chartId: String, r: Result): Boolean {
        val old = best(ctx, chartId)
        if (old != null && old.score >= r.score) return false
        p(ctx).edit().putString("rhythm.best.$chartId", "${r.score}|${r.acc}|${r.rank}|${r.maxCombo}").apply()
        return true
    }

    /**
     * 手动/自动校准出来的偏移（毫秒，正 = 玩家习惯按晚了，判定往后挪）。
     * 不同手机音频输出延迟能差几十毫秒，不给这个旋钮就是「明明按准了却 Good」。
     */
    fun offsetMs(ctx: Context): Int = p(ctx).getInt("rhythm.offset", 0)

    fun setOffsetMs(ctx: Context, ms: Int) = p(ctx).edit().putInt("rhythm.offset", ms.coerceIn(-200, 200)).apply()
}
