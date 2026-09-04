package app.yxi.agent

import app.yxi.ssh.SshSession

/**
 * 换模型。**手机上不该让人去 TUI 里翻选单。**
 *
 * `/model` 打开的是个带编号的选单，形状跟权限提示一模一样：
 * ```
 *    Select model
 *      1. Default (recommended)  Opus 5 with 1M context · …
 *    ❯ 2. Opus (1M context) ✔    Opus 5 with 1M context · …
 *      3. Fable                  Fable 5 · …
 *    Enter to set as default · s to use this session only · Esc to cancel
 * ```
 *
 * ⚠️ **两种提交方式后果差很远，实测确认过：**
 *   · **直接送数字** = 选中并「saved as your default for **new sessions**」——
 *     它改的是**账号级默认**，以后新开的每个会话都跟着变。
 *   · **方向键挪到目标再送 `s`** = 「for **this session only**」——
 *     只影响当前会话，不碰默认。
 *
 * 所以界面上**点一下 = 只换这个会话**（可逆、影响面小），
 * 「设为默认」单独一个动作、单独说清楚。
 * 在手机上顺手一点就把账号默认改掉，是那种事后想不起来为什么的坑。
 */
object Model {

    data class Choice(
        val number: Int,
        val name: String,
        val desc: String,
        /** 打了 `✔` 的那个 —— **是这个会话当前用的**，不一定等于账号默认 */
        val current: Boolean,
    )

    /**
     * `  ❯ 2. Opus (1M context) ✔    Opus 5 with 1M context · …`
     *
     * ⚠️ 行首那个记号有三种：`❯`（光标）、`↓`/`↑`（列表还能往下/上滚）。
     * 只放 `❯` 的话，窄窗口下带 `↓` 的那一项**会被整条漏掉** ——
     * 界面上少一个模型，而且不报错。测试 [ModelTest.窄窗口下折行的描述也要认] 盯着它。
     */
    private val ROW = Regex("""^\s*[❯↓↑]?\s*(\d+)\.\s+(\S.*?)\s{2,}(\S.*)$""")

    /**
     * 从屏幕上解出选单。**不是这个面板就返回 null。**
     *
     * ⚠️ 必须同时看到标题和脚注才算数。只认编号行的话，
     * 权限提示、计划审批那些**也是编号列表**，会被当成模型选单，
     * 然后用户一点就把选项送进了一个完全不同的提示里。
     */
    /** 模型选单副标题的几种措辞。**只增不删** —— 老版本的会话还在跑。 */
    private val SUBTITLE = listOf(
        "to use this session only",                 // 老版；新版脚注里也有这句
        "Switch between Claude models",             // 2.1.24x 的副标题
        "becomes the default for new sessions",     // 同上，另一句
    )

    /**
     * ⚠️⚠️ **匹配前必须把换行和多余空白压平。**
     *
     * 手机上的 tmux 窗格很窄，TUI 会折行。真机 46 列抓屏实测，脚注变成：
     * ```
     * Enter to set as default · s to use this
     * session only · Esc to cancel
     * ```
     * —— `to use this session only` **被换行劈成两半**，子串匹配必然失败。
     * 于是这个护栏在**手机上**（也就是唯一会用到它的地方）形同虚设：
     * 模型选单被当成普通「等你选」卡片画出来，用户点一下**就把账号默认模型改了**。
     *
     * ⚠️ 加更多短语**治不了这个** —— 任何一句都可能被折行劈开。
     * 压平才是对的。这跟 #113（窄屏脚注被截断导致判不出「在忙」）、
     * #133（窄屏折行让选项标题错位）是**同一个病根**。
     */
    private fun flat(screen: String) = screen.replace(Regex("""\s+"""), " ")

    fun parse(screen: String): List<Choice>? {
        if ("Select model" !in flat(screen)) return null
        // ⚠️⚠️ **第二句判据必须能认多种措辞。** Claude Code 改过这段说明文字：
        //   老版：「… to use this session only」
        //   新版：「Switch between Claude models. Your pick becomes the default
        //          for new sessions.」
        // 只认老版的后果**很严重**：判据失效 → 这个护栏不再拦 →
        // 模型选单被通用解析当成普通「等你选」卡片画出来 → 用户点一下
        // **就把账号默认模型改了**，而且不报错。真机截图逮到的。
        //
        // ⚠️ 只认「Select model」一句不够：正文里提到这两个词的普通对话会被误判成选单，
        // 那时真正的待答卡片会凭空消失。所以要两句都在。
        val f = flat(screen)
        if (SUBTITLE.none { it in f }) return null
        val out = screen.split('\n').mapNotNull { line ->
            val m = ROW.matchEntire(line.trimEnd()) ?: return@mapNotNull null
            val rawName = m.groupValues[2]
            Choice(
                number = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null,
                name = rawName.replace("✔", "").trim(),
                desc = m.groupValues[3].trim(),
                current = "✔" in rawName,
            )
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** 面板底下的「… +2 models」——**列表被窗口高度截断了**，还有几个没露出来。 */
    private val MORE = Regex("""\+\s*(\d+)\s+models?""")

    /**
     * 这个会话现在能不能安全地借来送键（打开 `/model` 选单）。
     *
     * ⚠️ **只放行「输入框里除了提示符什么都没有」**。里面有半截草稿的话，
     * 送进去的东西会接在后面，回车就把用户没写完的话连带发出去 —— 唯一会造成真实损失的一步。
     * 忙着的时候也不碰（打字会进队列）。
     *
     * ⚠️ 额度查询早先也用它，现在改走 `claude -p "/usage"`（[Quota]）不借会话了；
     * 只剩 `/model` 这种**必须在活会话里操作**的还用得着。
     */
    fun borrowable(screen: String): Boolean {
        if (Live.parse(screen).busy) return false
        val lines = screen.split('\n').map { it.trimEnd() }
        val dividers = lines.indices.filter { l -> lines[l].trim().let { it.length >= 8 && it.all { c -> c == '─' } } }
        if (dividers.size < 2) return false
        val body = lines.subList(dividers[dividers.size - 2] + 1, dividers.last())
        return body.size == 1 && body[0].trimStart().removePrefix("❯").isBlank()
    }

    /**
     * 打开选单并把它读回来。⚠️ 借会话的规矩：忙着 / 输入框有字就不碰。
     *
     * ⚠️ **要重试着抓。** 面板不是一瞬间画完的，抓早了拿到半个 ——
     * 表现是「点了没反应，然后过一会儿会话里冒出个选单」。一次 1.8 秒不够，实测过。
     *
     * ⚠️ **窗口矮的时候列表会被截断**（底下写着 `… +2 models`）。
     * 手机上的终端本来就矮，这是常态不是例外。
     * 所以截断了就按方向键把剩下的滚出来，边滚边收，最后再滚回原位 ——
     * **必须滚回去**，否则下面 [pick] 按相对步数算光标就全错了。
     */
    suspend fun open(ssh: SshSession, target: String): List<Choice>? {
        if (!borrowable(ssh.exec("tmux capture-pane -p -t ${app.yxi.ssh.Shell.q(target)}"))) return null
        ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} -l '/model'")
        ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} Enter")

        var screen = ""
        var got: List<Choice>? = null
        repeat(4) {
            kotlinx.coroutines.delay(900)
            screen = ssh.exec("tmux capture-pane -p -t ${app.yxi.ssh.Shell.q(target)}")
            got = parse(screen)
            if (got != null) return@repeat
        }
        val first = got ?: return null

        val hidden = MORE.find(screen)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (hidden <= 0) return first

        // 滚出剩下的。用 LinkedHashMap 按编号去重，滚动过程中同一条会反复出现
        val all = LinkedHashMap<Int, Choice>()
        first.forEach { all[it.number] = it }
        repeat(hidden) {
            ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} Down")
            kotlinx.coroutines.delay(180)
            parse(ssh.exec("tmux capture-pane -p -t ${app.yxi.ssh.Shell.q(target)}"))?.forEach { all[it.number] = it }
        }
        // ⚠️ 滚回去，让光标停在原来那一项上 —— [pick] 的相对步数依赖这个前提
        repeat(hidden) { ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} Up") }
        return all.values.sortedBy { it.number }
    }

    /**
     * 选一个。
     *
     * @param asDefault true = 连账号默认一起改（送数字）；false = 只换这个会话（挪到目标再送 `s`）
     *
     * ⚠️ 挪光标用**相对步数**：`to - from`。选单里没有「跳到第 N 项」的键，
     * 而送数字就直接提交成默认了 —— 那正是我们想避开的。
     */
    suspend fun pick(ssh: SshSession, target: String, from: Int, to: Int, asDefault: Boolean) {
        if (asDefault) {
            ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} '$to'")
            return
        }
        val steps = to - from
        val key = if (steps > 0) "Down" else "Up"
        repeat(kotlin.math.abs(steps)) { ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} $key") }
        ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} 's'")
    }

    /** 关掉选单不做任何改动。⚠️ **取消也必须发** —— 面板不关，抓屏会一直看到它。 */
    suspend fun cancel(ssh: SshSession, target: String) {
        ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} Escape")
    }
}
