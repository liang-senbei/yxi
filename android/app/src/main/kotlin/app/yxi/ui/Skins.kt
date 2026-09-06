package app.yxi.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.yxi.agent.Wish
import app.yxi.ui.theme.TerminalBg
import app.yxi.ui.theme.TerminalFg

/**
 * 装扮 —— 祈愿抽到的终端配色 / 气泡配色 / 快捷语包，以及「我有哪些、当前用哪个」。
 *
 * ⚠️⚠️ **归属的真相源永远是服务端。** 这里 [Cosmetics.owned] 存的是
 * **「上次服务端说我有什么」的缓存，只用于渲染**（决定当前该画哪套皮肤）。
 * **发奖、判归属、卡牌库显示什么，一律读接口**（[Wish.collection]）；接口拿不到就按
 * 「拿不到」显示，**不拿这份缓存冒充**。
 *
 * 为什么渲染这一路要缓存（cc-Yxi 2026-09-04 批准，理由记这里免得后人误读成「归属可以存本机」）：
 * 两个方向的错**代价不对等**。缓存偏旧 → 最多让人多用几天一个纯外观的东西；
 * 不缓存 → 地铁里没网时 `collection()` 返回 null，终端从「琥珀」跳回默认深色 ——
 * 那个表现跟「我的收藏没了」一模一样，正是 [Wish] 注释里最怕的事。选便宜的那边错。
 *
 * ⚠️ **默认那套永远可用**（`id` 为空串），不受归属影响 —— 撤销 / 掉登录 / 缓存被清，
 * 都只是掉回默认，不会掉进「没有任何配色可用」的洞里。
 */
object Skins {

    /** 槽位。一个槽位同时只能用一套。 */
    const val TERMINAL = "terminal"
    const val BUBBLE = "bubble"
    const val FRAME = "frame"

    // ── 终端配色 ────────────────────────────────────────────────────────────

    /**
     * ⚠️ **终端不跟随浅色/深色皮肤**（STYLE.md §1.1：ANSI 彩色输出在浅底上读不了）。
     * 每套配色自带一对固定的前景/背景。
     */
    class Term(val id: String, private val zh: String, val fg: Color, val bg: Color) {
        val label: String get() = t(zh)
    }

    /**
     * 对比度都实测过（WCAG 相对亮度）：默认 14.96:1 · 琥珀 11.75 · 青 13.44 · 纸白 13.17 · 夜航 12.99。
     *
     * ⚠️ **「纸白」是浅底，跟「终端永远深底」那条规矩冲突。**
     * `termlib` 的 16 色 ANSI 表在 `ColorCache` 这个单例里，`Terminal()` 也没有传色板的参数 ——
     * **我们改不了 ANSI 颜色**。所以浅底下 ANSI 的亮黄/亮青/白会很难读。
     * 正文（fg/bg）本身对比度是够的，糊的只有带色的输出。留不留在奖池里由 cc-Yxi 定。
     * ponytail: 眼下就这么放着；真要修，得等 termlib 开放色板或自己 fork。
     *
     * ⚠️ **决定（cc-Yxi 2026-09-04）：「纸白」留在代码里，但不放进奖池。**
     * design/STYLE.md §1.1 写着「终端永远是深底 —— 彩色输出在浅底上读不了」，这是条有理由的规矩；
     * 发一个会让终端变难读的奖品，比不发更糟。留着代码是因为分析值钱：
     * 哪天 termlib 开放色板，把 id 加回 wish.json 就能上。
     */
    val TERMS = listOf(
        Term("", "默认", TerminalFg, TerminalBg),
        Term("term_amber", "终端·琥珀", Color(0xFFF2C879), Color(0xFF1A1206)),
        Term("term_teal", "终端·青", Color(0xFFA8E8DC), Color(0xFF071614)),
        Term("term_paper", "终端·纸白", Color(0xFF2B2622), Color(0xFFF4F0E8)),
        Term("term_night", "终端·夜航", Color(0xFFC9D8F0), Color(0xFF0B1220)),
    )

    // ── 气泡配色 ────────────────────────────────────────────────────────────

    /**
     * 你自己那条消息的气泡。
     *
     * ⚠️ **必须跟着浅色/深色皮肤走**。写死一套浅色的话，深色皮肤下满屏暗底上突然一块亮糖果 ——
     * 装扮是锦上添花，不该把主题掀了。所以每套都给两版，[bg]/[on] 按当前皮肤取。
     */
    class Bubble(
        val id: String,
        private val zh: String,
        private val lightBg: Color, private val lightOn: Color,
        private val darkBg: Color, private val darkOn: Color,
        /** 边 / 尾巴 / 描边 / 玻璃 / 角标，画法在 [BubbleBox]；默认 = 原来那种纯色圆角 */
        val style: BubbleStyle = BubbleStyle(),
    ) {
        val label: String get() = t(zh)
        // 读 Skin.style 是 Compose 状态：在组合里读到，切皮肤会自动重组
        val bg: Color get() = if (Skin.style.palette.light) lightBg else darkBg
        val on: Color get() = if (Skin.style.palette.light) lightOn else darkOn
    }

    /**
     * `id` 为空的那套 = 用主题自己的 `primaryContainer`，所以它的两对颜色**用不上**（接线处会走默认分支）。
     * 其余四套的文字/底色对比度实测：暖 9.36/8.83 · 冷 10.52/10.14 · 墨 11.38/10.15 · 晨 11.01/10.49（浅/深）。
     */
    val BUBBLES = listOf(
        Bubble("", "默认", Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified),
        Bubble("bubble_warm", "气泡·暖", Color(0xFFF7DFC4), Color(0xFF4A3116), Color(0xFF4A3620), Color(0xFFF5DFC6)),
        Bubble("bubble_cool", "气泡·冷", Color(0xFFD9E7F7), Color(0xFF16324A), Color(0xFF1B3350), Color(0xFFD8E6F8)),
        Bubble("bubble_ink", "气泡·墨", Color(0xFFE4E1DA), Color(0xFF2A2723), Color(0xFF343029), Color(0xFFE6E2DA)),
        Bubble("bubble_dawn", "气泡·晨", Color(0xFFF7DCE4), Color(0xFF4A1B2A), Color(0xFF4A2230), Color(0xFFF6DDE5)),
        // ── 2026-09-06 老板：照 QQ 的聊天气泡商店做几款（前两款是复刻他发的「心理活动框」「思考小睫」）──
        // 白底墨边、尾巴两个空心小圆圈；深色皮肤下反过来（墨底白边）
        Bubble("bubble_thought", "心理活动框", Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF1C1C21), Color(0xFFEDEDED),
            BubbleStyle(tail = BubbleTail.DOTS, corner = 22.dp, outline = 2.dp)),
        // 同上 + 右下角探出 Q 版云曦的头
        Bubble("bubble_yunxi_peek", "云曦想想", Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF1C1C21), Color(0xFFEDEDED),
            BubbleStyle(tail = BubbleTail.DOTS, corner = 22.dp, outline = 2.dp, peek = app.yxi.R.drawable.yunxi_q_head)),
        // 只有一圈蓝线，没有底色（字也是那个蓝）
        Bubble("bubble_outline", "素描边", Color.Transparent, Color(0xFF2F5FC0), Color.Transparent, Color(0xFF8AB4F8),
            BubbleStyle(outline = 1.5.dp, corner = 24.dp)),
        // 云朵边 —— 云曦的主题
        Bubble("bubble_cloud", "云朵", Color(0xFFFFFFFF), Color(0xFF3D5A80), Color(0xFF26303F), Color(0xFFDCE8FF),
            BubbleStyle(edge = BubbleEdge.CLOUD, tail = BubbleTail.DOTS, corner = 24.dp, outline = 1.5.dp)),
        // 玻璃：半透明 + 高光 + 细边，跟输入框那颗药丸一家
        Bubble("bubble_glass", "玻璃", Color(0xFFFFFFFF), Color(0xFF2A2F3A), Color(0xFF1C1C21), Color(0xFFECEAF2),
            BubbleStyle(glass = true, corner = 26.dp, outline = 1.dp)),
        // 像素：三级台阶的角 + 2dp 描边
        Bubble("bubble_pixel", "像素", Color(0xFFFFF6D6), Color(0xFF2B2622), Color(0xFF2A2418), Color(0xFFFFE9B0),
            BubbleStyle(edge = BubbleEdge.PIXEL, corner = 12.dp, outline = 2.dp)),
    )

    // ── 快捷语包 ────────────────────────────────────────────────────────────

    /**
     * 拥有了就接在 [Snippets] 默认那几条后面。
     * ⚠️ **只扩展默认列表**：用户自己编辑过常用语之后，那份是他的，别往里塞东西。
     */
    class Pack(val id: String, private val zh: String, val lines: List<String>) {
        val label: String get() = t(zh)
    }

    val PHRASE_PACKS = listOf(
        Pack("phrase_pack", "快捷语包·协作", listOf("先说结论", "给两个方案我选", "这段改小一点", "别改其它文件")),
        Pack("phrase_pack_debug", "快捷语包·排查", listOf("先复现再改", "根因是什么", "把报错原文贴出来", "加一行日志再跑")),
    )

    // ── 头像框 ──────────────────────────────────────────────────────────────

    enum class FrameKind { NONE, DAWN, ABYSS }

    /**
     * 戴在头像外圈的框，画法在 [drawAvatarFrame]。[season] 只对深渊那款有意义（转一下起始色相）。
     * ⚠️ 深渊的「王棋彩框」**不在祈愿奖池里**，只有满 36 星那档发（服务端 abyss.json 的 fullReward），
     *    每期一款、期末不再产出 —— 所以每期在这儿加一行，画法共用。
     */
    class Frame(val id: String, private val zh: String?, val kind: FrameKind, val season: Int = 0) {
        val label: String get() = zh?.let { t(it) } ?: t("深渊 · 王棋彩框 · 第 %d 期").format(season)
    }

    /** 固定的两枚：不戴、晨曦光环。深渊框不在这儿写死，见 [frames]。 */
    val FRAMES = listOf(
        Frame("", "不戴", FrameKind.NONE),
        Frame("halo_dawn", "晨曦光环", FrameKind.DAWN),
    )

    /**
     * 固定的 + 拥有的深渊框。服务端每期发 `halo_abyss_<期数>`（abyss.json 的 idTemplate，
     * cc-logto_yxi 2026-09-05），**按前缀识别、期数从 id 里读**，第 N 期的框不用客户端发版就能戴。
     */
    fun frames(ctx: Context): List<Frame> {
        val abyss = Cosmetics.owned(ctx).mapNotNull { id ->
            ABYSS_ID.matchEntire(id)?.groupValues?.get(1)?.toIntOrNull()?.let { n -> Frame(id, null, FrameKind.ABYSS, season = n) }
        }.sortedBy { it.season }
        return FRAMES + abyss
    }

    private val ABYSS_ID = Regex("""halo_abyss_(\d+)""")

    // ── 取当前该用哪套 ──────────────────────────────────────────────────────

    /** 当前戴的框；`kind == NONE` = 不戴。 */
    fun frame(ctx: Context): Frame = pick(ctx, FRAME, frames(ctx), FRAMES[0]) { it.id }


    fun terminal(ctx: Context): Term = pick(ctx, TERMINAL, TERMS, TERMS[0]) { it.id }

    /** @return `id` 为空的那套 = 用主题默认色，接线处该走 `MaterialTheme.colorScheme.primaryContainer`。 */
    fun bubble(ctx: Context): Bubble = pick(ctx, BUBBLE, BUBBLES, BUBBLES[0]) { it.id }

    /** 默认那几条 + 已拥有的语包。 */
    fun phrases(ctx: Context, base: List<String>): List<String> {
        val owned = Cosmetics.owned(ctx)
        return base + PHRASE_PACKS.filter { it.id in owned }.flatMap { it.lines }
    }

    /**
     * 选中的那套要**同时满足**：存在、且（是默认 或 归属缓存里有）。
     * 任一不满足就回默认 —— 撤销了、换手机了、缓存清了，都是掉回默认，不是崩或空白。
     */
    private inline fun <T> pick(ctx: Context, slot: String, all: List<T>, fallback: T, id: (T) -> String): T {
        val want = Cosmetics.picked(ctx, slot)
        if (want.isEmpty()) return fallback
        val owned = Cosmetics.owned(ctx)
        return all.firstOrNull { id(it) == want && want in owned } ?: fallback
    }
}

/**
 * 「我有哪些装扮」+「每个槽位当前选哪个」。
 * 见 [Skins] 顶上那段：**owned 只是渲染用的缓存，真相源是服务端。**
 */
object Cosmetics {

    /**
     * ⚠️⚠️ **换了装扮，画面要当场跟着变。**
     *
     * 选择存在 SharedPreferences 里，而 prefs **不是 Compose 状态** —— 光写 prefs 的话，
     * 终端和气泡要等到下一次「因为别的原因」重组才更新。而终端那一屏往往一直挂在组合树上，
     * 于是表现是「点了没反应，切出去再切回来才生效」。
     *
     * 所以加一个版本号：**读的人读它（就订阅了）**，[pick] / [refresh] 改完 +1，读的人自动重组。
     * prefs 仍然是落盘的地方，这个数只管「谁该重画」。
     */
    private var rev by mutableIntStateOf(0)

    /** 订阅上面那个版本号。**写成函数调用而不是裸读**，免得被看成没用的表达式删掉。 */
    private fun subscribe(): Int = rev

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    /** 上次服务端说我有什么。拿不到过就是空集 —— 那时只有默认那套可用，是安全的方向。 */
    fun owned(ctx: Context): Set<String> {
        subscribe()
        return p(ctx).getStringSet("cos.owned", null) ?: emptySet()
    }

    /**
     * 跟服务端对一次。**只有真的拿到了才覆盖缓存** ——
     * 拿不到就把缓存清空的话，一次没网就等于把人的皮肤全下架了。
     *
     * @return 拿到了没有。false = 这次没对上（没登录 / 没网 / 接口没上线），缓存原样保留。
     */
    suspend fun refresh(ctx: Context): Boolean {
        val s = Wish.collection(ctx) ?: return false
        p(ctx).edit().putStringSet("cos.owned", s).apply()
        rev++
        return true
    }

    fun picked(ctx: Context, slot: String): String {
        subscribe()
        return p(ctx).getString("cos.$slot", "").orEmpty()
    }

    fun pick(ctx: Context, slot: String, id: String) {
        p(ctx).edit().putString("cos.$slot", id).apply()
        rev++
    }
}
