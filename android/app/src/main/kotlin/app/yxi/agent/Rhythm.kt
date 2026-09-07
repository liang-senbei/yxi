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

    /**
     * 连击档位（老板 2026-09-06：连击 ×8 起分数倍率 1.2，×15、×30 再往上）。
     * **从服务端下发**（rhythm.json），客户端不写死门槛和倍率 —— 老板玩过觉得不对，服务端改一行就生效。
     *
     * ⚠️ **服务端没下发就等于没有这套机制**：那时客户端**不乘倍率**，显示的分数和结算页一致。
     *    宁可暂时没有倍率，也不能出现"游戏里 180 万、结算页 92 万"。
     */
    data class Tier(val combo: Int, val mult: Float, val word: String)   // 契约里叫 {at, mult, label}

    @Volatile var tiers: List<Tier> = emptyList(); private set

    /** [combo] 连时的倍率；没配置档位就是 1 */
    fun multAt(combo: Int): Float = tiers.lastOrNull { combo >= it.combo }?.mult ?: 1f

    /** 刚好踩到某一档的门槛 → 返回该档的词（用来跳夸奖词）；否则 null */
    fun tierWord(combo: Int): String? = tiers.firstOrNull { it.combo == combo }?.word

    @Volatile var rules = Rules(); private set

    /**
     * 判定窗口（毫秒）。比常见音游宽不少：这是活动里随手玩两把的东西，不是竞技谱。
     *
     * **老板 2026-09-06 拍板再放宽 50%**（80→120 / 160→240）。三件事说清楚：
     * · 这是改**默认难度**，不是把窗口开放给用户调 —— 手感面板里依然没有这一项，
     *   放开了等于自己给自己发 S，而服务端算分用的就是这套判定计数。
     * · **旧成绩不用清**：分数公式、分母（units）、评级门槛一个都没动，尺度没变，
     *   只是当时更难拿。调平衡不清档，这跟上次"units 变了要清"不是一回事（cc-logto_yxi 2026-09-06）。
     * · 服务端**没有任何按窗口设的阈值**，窗口纯在客户端；准度普遍上升 = S 更好拿，
     *   这正是老板要的方向。真觉得 S 太便宜了，服务端改一行 `grades` 配置就行，不用发版。
     */
    const val PERFECT_MS = 120f
    const val GOOD_MS = 240f
    /** swipe 的窗口放宽到 ±360（老板 2026-09-06：「滑动的判定可以放宽松点」）；手势门槛也放宽（见 RhythmScreen） */
    const val SWIPE_MS = 360f

    /** 判定窗口按种类：swipe 宽一档 */
    fun windowMs(kind: Kind): Float = if (kind == Kind.SWIPE) SWIPE_MS else GOOD_MS

    /**
     * 轨道数（老板 2026-09-07：「不一定只有 4 条，手机总长度除以音符长度能放几条就放几条」）。
     * 音符长度 = 屏宽 × 0.0811（原来的 0.0624 加长 30%），1 / 0.0811 = 12.3 → 12 条，和机型无关（都按屏宽比例）。
     * 谱面里的 lane 就是 0..11。手指判定放宽到相邻一条（±1），不然 200px 的轨太考验准头。
     */
    const val LANES = 12

    /** 音符从冒头到判定线的时间（秒）。越大越"慢"、越好读谱 */
    const val APPROACH = 1.55f   // 老板 09-06 在试验台上定的；新谱自带 approach，这只是旧谱和「试一下」的默认

    enum class Judge { PERFECT, GOOD, MISS }

    /**
     * 四种音块（老板 2026-09-06 定的规格）。**颜色之外还要靠形状分得出** —— 色盲和强光下只看形状也行。
     *
     * | 名字 | 怎么打 | 判定数 |
     * |---|---|---|
     * | [TICK]  | 点一下 | 1 |
     * | [SLIDE] | 长按 + 滑（按住不松） | **2**（头 + 尾） |
     * | [TRACE] | 拖到目标轨 | 1 |
     * | [SWIPE] | 落下时左滑或右滑 | 1 |
     *
     * ⚠️ 判定数（[Chart.units]）是服务端算分、校验计数、卡 maxCombo 上限的分母。
     *    改了音块构成 = 改了 units，**必须先给 cc-logto_yxi 新的 charts-meta.json**，
     *    等他更新 rhythm.json 之后客户端才能发版，否则线上提交全被判 bad_counts。
     */
    enum class Kind { TICK, SLIDE, TRACE, SWIPE }

    data class Note(
        val t: Float,          // 秒，音符该被击中的时刻
        val lane: Int,         // 0 until LANES
        val kind: Kind,
        val dur: Float,        // 秒，SLIDE 的长度；其余为 0
        /** TRACE 要拖到哪条轨、SWIPE 要往哪边滑：-1 左 / +1 右；其余 0 */
        val dir: Int = 0,
    ) {
        val hold get() = kind == Kind.SLIDE
        var judged: Judge? = null
        var tailDone = false   // SLIDE 的尾巴判过没有
        /** 画面用的、跟判定无关的东西：swipe 用哪款标记（0/1）、特效的随机种子、长按复发爆点的上次时刻 */
        var markIdx = 0
        var seed = 0.5f
        var lastPulse = -1f
    }

    /**
     * 判定线事件：让线**动起来**（老板 2026-09-06 看了 Phigros 之后要的）。
     *
     * 只有两种：绕线中心转 [ROTATE]（度，逆时针为正）、整条线上下挪 [MOVE_Y]（屏幕高度的比例，正 = 往下）。
     * 横向平移和淡入淡出**先不做** —— 谱面里没有就别造。
     *
     * ⚠️ 关键在于**音符活在"线的坐标系"里**：它们垂直于线、跟着线一起转，沿着线的法线匀速过来。
     *    所以一个线事件会把**全场还在飞的音符一起重新指向**，但**不改变任何一个音符的到达时刻** ——
     *    判定完全不受影响。这也是为什么服务端不用改：判定计数一个都没变。
     *
     * ⚠️ 数值是**我们自己定的**（见 `design/music/make_songs.py`）。参考视频量出来的是"人家怎么做"，
     *    具体的角度和时间属于谱面数据，不抄。
     */
    enum class LineOp { ROTATE, MOVE_Y, MOVE_X }

    data class LineEvent(val t: Float, val dur: Float, val op: LineOp, val from: Float, val to: Float, val ease: String) {
        /** [now] 时刻这条事件贡献的值 */
        fun valueAt(now: Float): Float {
            if (now <= t) return from
            if (now >= t + dur || dur <= 0f) return to
            val k = (now - t) / dur
            val e = when (ease) {
                // 缓入缓出（三次）：位移的每帧增量是个钟形，起步和收尾都软 —— 线性看着像机器在推
                "cubicInOut" -> if (k < 0.5f) 4f * k * k * k else 1f - ((-2f * k + 2f).let { it * it * it }) / 2f
                "linear" -> k                                     // 匀速：狂热谱整段 360° 旋转用
                // 只缓出：一下子过去、慢慢回来（踩点那种"沉一下"）
                else -> 1f - (1f - k) * (1f - k) * (1f - k)
            }
            return from + (to - from) * e
        }
    }

    data class Chart(
        val song: String, val zh: String, val bpm: Int,
        val difficulty: String, val offset: Float, val notes: List<Note>,
        /** 判定线的动作，按时间排好 */
        val lines: List<LineEvent> = emptyList(),
        /** 曲子能量包络（design/music/energy_all.py，每秒 [energyHz] 个 0..1）：进高潮底光更亮、色彩层次更足（老板 09-07） */
        val energy: FloatArray = FloatArray(0), val energyHz: Float = 4f,
        /**
         * 下落时长（秒）。**谱面 / 难度的参数**（老板 2026-09-06：「根据关卡和难度来定义更好，比较容易随时改动」），
         * 谱面里没写的老谱用手感面板那个值。
         */
        val approach: Float? = null,
    ) {
        /**
         * [now] 时刻的线姿态：转了多少度（**不限幅**，老板 2026-09-06 定的编舞：可以 ±90° 立成竖线、180° 翻面）、
         * 横向挪多少（屏宽比例）、往下挪多少（屏高比例）。
         */
        /** [now] 秒时的能量 0..1（线性插值；没有包络就 0.6） */
        fun energyAt(now: Float): Float {
            if (energy.isEmpty()) return 0.6f
            val x = (now * energyHz).coerceIn(0f, (energy.size - 1).toFloat())
            val i = x.toInt(); val f = x - i
            return if (i + 1 < energy.size) energy[i] * (1f - f) + energy[i + 1] * f else energy[i]
        }
        fun poseAt(now: Float): Pose {
            var deg = 0f; var dx = 0f; var dy = 0f
            for (e in lines) {
                if (e.t > now) break
                when (e.op) {
                    LineOp.ROTATE -> deg = e.valueAt(now)
                    LineOp.MOVE_Y -> dy = e.valueAt(now)
                    LineOp.MOVE_X -> dx = e.valueAt(now)
                }
            }
            return Pose(deg, dx, dy)
        }
        val id get() = "${song}_$difficulty"
        /** SLIDE 算两个判定（头 + 尾），满分按这个数分 */
        val units get() = notes.size + notes.count { it.hold }
    }

    /**
     * @param credit 外来曲子的署名（授权条款要求的原话，选曲页原样显示）；自家曲子为空。
     *               魔王魂：「音楽：魔王魂」（规约要求尽量署名；商用 / 游戏免费，禁流媒体发行和转卖）。
     *               授权证据在 `design/music/licenses/`。
     */
    data class Pose(val deg: Float, val dx: Float, val dy: Float)

    data class Song(
        val id: String, val zh: String, val raw: Int, val bpm: Int, val seconds: Int, val credit: String = "",
        /** 这首有哪几张谱，按由易到难；「演示」放最难的那张 */
        val diffs: List<String> = DIFFS,
    )
    fun diffName(d: String) = when (d) { "hard" -> "认真"; "frenzy" -> "狂热"; else -> "轻松" }

    /** 曲子列表。加曲子的完整步骤见 design/rhythm-spec.md §6.3（谱面 + 服务端 charts-meta 都要跟上） */
    val SONGS = listOf(
        Song("snow", "初雪", app.yxi.R.raw.yx_snow, 96, 63),
        Song("yunxi", "云曦", app.yxi.R.raw.yx_yunxi, 128, 63),
        Song("abyss", "入渊", app.yxi.R.raw.yx_abyss, 152, 66),
        // 老板 2026-09-06：从免费商用池挑五首做关卡。全部来自魔王魂（短版），带人声
        Song("shining", "シャイニングスター", app.yxi.R.raw.yx_shining, 158, 94, "音楽：魔王魂"),
        Song("burning", "Burning Heart", app.yxi.R.raw.yx_burning, 142, 118, "音楽：魔王魂"),
        Song("count", "12345", app.yxi.R.raw.yx_count, 195, 93, "音楽：魔王魂", diffs = listOf("easy", "hard", "frenzy")),   // 狂热谱：老板 09-07 要的「非常非常难且有观赏性」
        Song("hikari", "ヒカリトリガー", app.yxi.R.raw.yx_hikari, 108, 99, "音楽：魔王魂"),
        Song("piece", "Piece Maker", app.yxi.R.raw.yx_piece, 117, 101, "音楽：魔王魂"),
    )

    val DIFFS = listOf("easy", "hard")

    fun load(ctx: Context, song: String, difficulty: String): Chart {
        val j = JSONObject(ctx.assets.open("charts/chart_${song}_$difficulty.json").use { it.readBytes() }.decodeToString())
        val arr = j.getJSONArray("notes")
        val notes = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dur = o.optDouble("dur", 0.0).toFloat()
            // 兼容老谱面：只有 tap / hold 的那版，读成 TICK / SLIDE
            val kind = when (o.optString("type")) {
                "slide", "hold" -> Kind.SLIDE
                "trace" -> Kind.TRACE
                "swipe" -> Kind.SWIPE
                else -> Kind.TICK
            }
            notes += Note(o.getDouble("t").toFloat(), o.getInt("lane"), kind, dur, o.optInt("dir", 0)).also { n ->
                n.markIdx = (i * 7919) % 2                      // swipe 两款标记随机（按下标定死，同一张谱每次一样）
                n.seed = ((i * 2654435761L) % 100000L) / 100000f
            }
        }
        val lines = ArrayList<LineEvent>()
        j.optJSONArray("lines")?.let { la ->
            for (i in 0 until la.length()) {
                val o = la.getJSONObject(i)
                val op = when (o.getString("op")) { "rotate" -> LineOp.ROTATE; "move_x" -> LineOp.MOVE_X; else -> LineOp.MOVE_Y }
                lines += LineEvent(
                    o.getDouble("t").toFloat(), o.optDouble("dur", 0.4).toFloat(), op,
                    o.optDouble("from", 0.0).toFloat(), o.optDouble("to", 0.0).toFloat(),
                    o.optString("ease", "cubicInOut"),
                )
            }
        }
        return Chart(j.getString("song"), j.getString("zh"), j.getInt("bpm"),
            j.getString("difficulty"), j.optDouble("offset", 0.0).toFloat(), notes, lines.sortedBy { it.t },
            energy = j.optJSONObject("energy")?.optJSONArray("v")?.let { v -> FloatArray(v.length()) { v.getDouble(it).toFloat() } } ?: FloatArray(0),
            energyHz = j.optJSONObject("energy")?.optDouble("hz", 4.0)?.toFloat() ?: 4f,
            approach = if (j.has("approach")) j.getDouble("approach").toFloat() else null)
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
            // 档位在 rules.comboTiers 里（契约 logto_yxi/design/rhythm.md）：[{at, mult, label}, …]
            o.optJSONObject("rules")?.optJSONArray("comboTiers")?.let { ta ->
                val list = ArrayList<Tier>()
                for (i in 0 until ta.length()) {
                    val e = ta.getJSONObject(i)
                    list += Tier(e.optInt("at"), e.optDouble("mult", 1.0).toFloat(), e.optString("label"))
                }
                tiers = list.sortedBy { it.combo }
            }
            val arr = o.optJSONArray("charts") ?: return@runCatching true
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val b = c.optJSONObject("best") ?: continue
                // ⚠️ 显示的评级用 **bestRating**（历史最佳评级），不是 best.rating。
                //    分数含连击加成、评级只看准度，两者会背离：104.4 万/A 会盖掉 102.5 万/S，
                //    于是"明明打出过 S 也领过奖"的谱在选曲页显示成 A —— 那是骗人。
                val shown = c.optString("bestRating").ifBlank { b.optString("rating") }
                // 服务端的成绩是权威的，本地存档只是离线时的镜子
                p(ctx).edit().putString(
                    "rhythm.best.${c.getString("id")}",
                    "${b.optInt("score")}|${b.optDouble("accuracy").toFloat()}|$shown|${b.optInt("maxCombo")}",
                ).apply()
            }
            true
        }.getOrDefault(false)
    }

    /** 交这一局的判定计数。返回服务端算出来的成绩 + 这次发了什么；网络不通返回 null（成绩只留本地）。 */
    /**
     * 交这一局。[hits] 是**判定序列**（每个判定一个字符 P/G/M，按判定发生的时间顺序，长度 == units）——
     * 服务端拿它自己算连击、倍率、分数、准度、评级（cc-logto_yxi 2026-09-06 定的：
     * 序列是原始事实，比"客户端算完的结论"少一个可以撒谎的入口，以后改计分规则也不用改协议）。
     * 聚合计数照旧一起报，服务端两边一对，不一致就打回 —— 就是抓到过我长按尾判丢一个的那道闸门。
     */
    suspend fun submit(
        ctx: Context, chartId: String, perfect: Int, good: Int, miss: Int, maxCombo: Int, elapsedMs: Int,
        hits: String,
    ): Submitted? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("chartId", chartId).put("perfect", perfect).put("good", good).put("miss", miss)
            .put("maxCombo", maxCombo).put("elapsedMs", elapsedMs).put("hits", hits)
            // ⚠️ #297：requestId 在这里现生成 = 只跟「这次调用」绑定，不跟「这一局」绑定。今天没事全靠
            //    ResultCard 一局只交一次、apiRaw 不重试；谁要加「重交」按钮或给 apiRaw 加重试，先把 id
            //    挪到结算页 remember 里当参数传进来（祈愿 WishScreen 的 pendingId 写法），否则一局算两局。
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

    private val RANKS = listOf("C", "B", "A", "S")

    /**
     * 比原来好才覆盖（跟深渊「打平或更好才更新」一致），返回是不是新纪录。
     * ⚠️ **评级单独取历史最好的那个**：分数含连击加成、评级只看准度，一局可能分更高但评级更低，
     *    直接跟着分走会把已经拿到手的 S 显示成 A（服务端同理，用 bestRating）。
     */
    fun saveBest(ctx: Context, chartId: String, r: Result): Boolean {
        val old = best(ctx, chartId)
        val rank = if (old == null) r.rank
        else RANKS[maxOf(RANKS.indexOf(old.rank), RANKS.indexOf(r.rank)).coerceAtLeast(0)]
        if (old != null && old.score >= r.score) {
            if (rank != old.rank) {                              // 分没破纪录但评级破了，也得记下来
                p(ctx).edit().putString("rhythm.best.$chartId", "${old.score}|${old.acc}|$rank|${old.maxCombo}").apply()
            }
            return false
        }
        p(ctx).edit().putString("rhythm.best.$chartId", "${r.score}|${r.acc}|$rank|${r.maxCombo}").apply()
        return true
    }

    /**
     * 手动/自动校准出来的偏移（毫秒，正 = 玩家习惯按晚了，判定往后挪）。
     * 不同手机音频输出延迟能差几十毫秒，不给这个旋钮就是「明明按准了却 Good」。
     */
    fun offsetMs(ctx: Context): Int = p(ctx).getInt("rhythm.offset", 0)

    /**
     * ── 手感（老板 2026-09-06：「这些你都让我自己调节，我试试那个最舒服就直接可以上线」）──
     *
     * 全部存在本地、当场生效。**默认值就是我调好的那一组**；老板改完觉得好，
     * 把默认值改成他那组即可（一行常量），不用改逻辑。
     *
     * ⚠️ **判定窗口不给调**（完美 ±80ms / 不错 ±160ms 写死）：那不是手感是难度，
     *    放宽了等于自己给自己发 S，服务端算分用的是同一套计数，改这个就是作弊。
     */
    /** 音符从冒头到判定线要多久（秒）。越大越慢、越好读谱 */
    fun approach(ctx: Context): Float = p(ctx).getFloat("rhythm.approach", APPROACH)
    fun setApproach(ctx: Context, v: Float) = p(ctx).edit().putFloat("rhythm.approach", v.coerceIn(0.9f, 2.6f)).apply()

    /** 打击音音量 0–1 */
    fun soundVol(ctx: Context): Float = p(ctx).getFloat("rhythm.vol", 0.9f)
    fun setSoundVol(ctx: Context, v: Float) = p(ctx).edit().putFloat("rhythm.vol", v.coerceIn(0f, 1f)).apply()

    /** 震动轻重：0 关 · 1 轻 · 2 中 · 3 重（走系统触感常量，不申请震动权限，所以系统里关了触感就自动不震） */
    fun haptic(ctx: Context): Int = p(ctx).getInt("rhythm.haptic.level", 2)
    fun setHaptic(ctx: Context, v: Int) = p(ctx).edit().putInt("rhythm.haptic.level", v.coerceIn(0, 3)).apply()

    /** 命中特效的大小：0 = 不放，1 = 标准 */
    fun fxScale(ctx: Context): Float = p(ctx).getFloat("rhythm.fx", 1.1f)   // 老板 09-06 定的爆点默认 1.1
    fun setFxScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("rhythm.fx", v.coerceIn(0f, 1.6f)).apply()

    /** 判定线晃动的幅度：0 = 完全不动，1 = 谱面里写的那么大 */
    fun lineScale(ctx: Context): Float = p(ctx).getFloat("rhythm.line", 1f)
    fun setLineScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("rhythm.line", v.coerceIn(0f, 1.5f)).apply()

    /** 打击音效 / 震动的开关（默认都开；有人要安静地玩） */
    fun soundOn(ctx: Context) = p(ctx).getBoolean("rhythm.sound", true)
    fun setSoundOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("rhythm.sound", on).apply()
    fun hapticOn(ctx: Context) = p(ctx).getBoolean("rhythm.haptic", true)
    fun setHapticOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("rhythm.haptic", on).apply()

    fun setOffsetMs(ctx: Context, ms: Int) = p(ctx).edit().putInt("rhythm.offset", ms.coerceIn(-200, 200)).apply()
}
