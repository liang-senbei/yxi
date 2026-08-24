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
    fun parse(screen: String): List<Choice>? {
        if ("Select model" !in screen) return null
        if ("to use this session only" !in screen) return null
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
        if (!borrowable(ssh.exec("tmux capture-pane -p -t '$target'"))) return null
        ssh.exec("tmux send-keys -t '$target' -l '/model'")
        ssh.exec("tmux send-keys -t '$target' Enter")

        var screen = ""
        var got: List<Choice>? = null
        repeat(4) {
            kotlinx.coroutines.delay(900)
            screen = ssh.exec("tmux capture-pane -p -t '$target'")
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
            ssh.exec("tmux send-keys -t '$target' Down")
            kotlinx.coroutines.delay(180)
            parse(ssh.exec("tmux capture-pane -p -t '$target'"))?.forEach { all[it.number] = it }
        }
        // ⚠️ 滚回去，让光标停在原来那一项上 —— [pick] 的相对步数依赖这个前提
        repeat(hidden) { ssh.exec("tmux send-keys -t '$target' Up") }
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
            ssh.exec("tmux send-keys -t '$target' '$to'")
            return
        }
        val steps = to - from
        val key = if (steps > 0) "Down" else "Up"
        repeat(kotlin.math.abs(steps)) { ssh.exec("tmux send-keys -t '$target' $key") }
        ssh.exec("tmux send-keys -t '$target' 's'")
    }

    /** 关掉选单不做任何改动。⚠️ **取消也必须发** —— 面板不关，抓屏会一直看到它。 */
    suspend fun cancel(ssh: SshSession, target: String) {
        ssh.exec("tmux send-keys -t '$target' Escape")
    }
}
