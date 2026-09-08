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
        // ⚠️ 用 [Live.isDivider]，别在这儿重写一份 —— 原来这里要求「整行纯横线」，
        //    而 Claude Code 把会话名画进了上边框，于是找不到上边框、这里永远 return false，
        //    表现是「点切模型永远说它正忙着」（老板 2026-09-06 报的，根因见 Live.isDivider）。
        // 「输入框空不空」这一半交给 [Live.inputEmpty]（同一份判据，别再抄）；
        // 判断不了（null）时保守当成「不能借」。
        return Live.inputEmpty(screen) == true
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

    // ── 快路：不开 TUI 选单，直接发 `/model <名字>` ──
    //
    // ⚠️⚠️ **两条路语义不同，2026-09-05 用一次性会话实测的（/tmp/modelprobe.log）：**
    //   · 上面那条「开选单 → 挪光标 → 送 s」= **只换这个会话**，但要抓屏 4 次、等 1~4 秒，
    //     用户嫌慢（「切换模型选单延迟很高」）。
    //   · `/model <名字>` = 弹一个「Switch model? This conversation is cached for the current
    //     model…」的确认框，**Enter 确认后既换这个会话、也写进 ~/.claude/settings.json 当账号默认**
    //     （settings.json 的 model 实测变了）。`/effort <级别>` 同理，回显直接写着
    //     「saved as your default for new sessions」。
    //   · 直接改 settings.json / 项目级 settings.local.json 的 model、effortLevel：
    //     **对跑着的会话不生效**（实测改完再问，模型和强度都没变）—— 只有 env 那一段会热加载。
    // 所以快路 = 快 + 改默认；界面上要**把「会改默认」写在按钮旁边**，不能藏。
    //
    // ⚠️ **发完 `/model X` 必须补一个 Enter 把确认框按掉。** 不按的话那个框一直开着，
    //    用户下一条消息会被它吃掉（探针里「ok」那条就是这么丢的）—— ModeSheet 原来的
    //    「Opus 5 · 1M」芯片就有这个毛病，现在统一走这儿。

    /** `~/.claude/settings.json` 里的 `availableModels` + 当前默认 `model`。按主机缓存，一次 cat 就够。 */
    data class Available(val models: List<String>, val default: String, val effort: String = "")
    private val availCache = HashMap<String, Available>()

    /**
     * 这台机器上能选哪些模型。**读的是 settings.json 的白名单**，不抓 TUI；拿不到就退回一份通用别名表。
     * ⚠️ 这里出来的是**白名单原样的短名**（`fable-5-1[1m]` / `opus-4-6[1m]` / `sonnet[1m]` / `default`）——
     *    界面上照着显示没问题，但**送进 `/model` 之前必须过 [canonical]**，短名它不认。
     */
    suspend fun available(ssh: SshSession, hostId: String, force: Boolean = false): Available {
        if (!force) availCache[hostId]?.let { return it }
        val raw = runCatching { ssh.exec("cat \"\$HOME/.claude/settings.json\" 2>/dev/null") }.getOrDefault("")
        val got = runCatching {
            val o = org.json.JSONObject(raw)
            val arr = o.optJSONArray("availableModels")
            val list = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf { s -> s.isNotBlank() } }
            Available(list, o.optString("model"), o.optString("effortLevel"))
        }.getOrNull()
        val fallback = listOf("default", "opus", "sonnet", "haiku")
        val a = if (got == null || got.models.isEmpty()) Available(fallback, got?.default.orEmpty(), got?.effort.orEmpty()) else got
        availCache[hostId] = a
        return a
    }

    /**
     * ⚠️⚠️ **`availableModels` 里的名字不能原样喂给 `/model`。**（老板 2026-09-06 报的）
     *
     * settings.json 白名单写的是**短名** `fable-5-1[1m]`，而 `/model` 只吃三种：
     *   · 别名 —— `sonnet` `opus` `haiku` `fable` `best` `opusplan`（可带 `[1m]`）
     *   · `default`
     *   · **全名** —— `claude-fable-5-1[1m]`
     * 短名进去回一句「Model 'fable-5-1[1m]' not found」就完了，模型**没换**。
     *
     * 本机 2.1.259 逐个实测（`claude --model X -p`，不是推的）：
     * ```
     * fable-5-1[1m]  ✗    claude-fable-5-1[1m]  ✓
     * opus-4-6[1m]   ✗    claude-opus-4-6[1m]   ✓
     * sonnet[1m] ✓  ·  default ✓  ·  haiku ✓
     * ```
     * 差别就是那个 `claude-` 前缀。⚠️ **别名不能加前缀** —— `claude-sonnet[1m]` 不是模型名。
     */
    private val ALIAS = setOf("default", "sonnet", "opus", "haiku", "fable", "best", "opusplan")
    private val ONE_M = Regex("""\[1m\]$""", RegexOption.IGNORE_CASE)

    fun canonical(name: String): String =
        if (name.startsWith("claude-") || ONE_M.replace(name, "").lowercase() in ALIAS) name
        else "claude-$name"

    /**
     * 送完 `/model` 之后屏幕上出现这几句 = **没切成**。
     * `not found` = 名字不认；`Kept model as …` = 认得但没换（多半不在 availableModels 白名单里）。
     */
    private val FAILED = Regex("""Model '[^']*' not found|unrecognized_model|Kept model as [\w .()\[\]-]+""")

    /**
     * 快路换模型：`/model <名字>` + 1.5 秒后 Enter 按掉确认框。
     * @return 出错原因；null = 真的切了。⚠️ 只在 [borrowable] 时发 —— 输入框里有半截草稿会被接上。
     */
    suspend fun switchFast(ssh: SshSession, target: String, name: String): String? {
        val alias = canonical(name)
        if (!Regex("""^[A-Za-z0-9\-\[\]._]+$""").matches(alias)) return "模型名有怪字符：$alias"
        if (!borrowable(ssh.exec("tmux capture-pane -p -t ${app.yxi.ssh.Shell.q(target)}"))) return "它正忙着，或者输入框里有没发完的字 —— 等一下再点"
        val t = app.yxi.ssh.Shell.q(target)
        ssh.exec("tmux send-keys -t $t -l '/model $alias'; sleep 0.3; tmux send-keys -t $t Enter")
        // ⚠️ `/model` 会把新模型写成账号默认（settings.json 的 `model`），
        //    缓存不作废的话「默认」那个标记会一直挂在旧那行上。作废一次比每次开选单重读便宜。
        availCache.keys.removeAll { true }
        kotlinx.coroutines.delay(1500)
        // 同一个模型再发一次不弹框；弹了就按掉，没弹这个 Enter 落在空输入框上是无害的
        ssh.exec("tmux send-keys -t $t Enter")
        // ⚠️ **切没切成必须看一眼。** 换模型失败时 Claude Code 只在屏幕上写一行，不弹框、
        //    没有别的信号；不看就一律报「已切到 X」＝**骗人**（老板就是这么被骗的）。
        //    ⚠️ 只翻**自己这条命令之后**那一截 —— 整屏找的话，上一次失败的旧账会被永远重报。
        //    ⚠️ 先 [flat] 再找：手机上的窄窗格会把这行折断，子串匹配必然落空（同 #113/#133 的病根）。
        val tail = flat(ssh.exec("tmux capture-pane -p -t $t")).substringAfterLast("/model $alias", "")
        return FAILED.find(tail)?.value?.trim()
    }

    /** 思考强度：`/effort <级别>`。一个来回。⚠️ 同样会写成账号默认（回显明说的）。 */
    suspend fun setEffort(ssh: SshSession, target: String, level: String): String? {
        if (level !in listOf("max", "xhigh", "high", "mid", "medium", "low")) return "强度值不对：$level"
        if (!borrowable(ssh.exec("tmux capture-pane -p -t ${app.yxi.ssh.Shell.q(target)}"))) return "它正忙着，或者输入框里有没发完的字 —— 等一下再点"
        val t = app.yxi.ssh.Shell.q(target)
        ssh.exec("tmux send-keys -t $t -l '/effort $level'; sleep 0.3; tmux send-keys -t $t Enter")
        return null
    }

    /** 关掉选单不做任何改动。⚠️ **取消也必须发** —— 面板不关，抓屏会一直看到它。 */
    suspend fun cancel(ssh: SshSession, target: String) {
        ssh.exec("tmux send-keys -t ${app.yxi.ssh.Shell.q(target)} Escape")
    }
}
