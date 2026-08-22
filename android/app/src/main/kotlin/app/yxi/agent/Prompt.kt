package app.yxi.agent

/**
 * 从 `tmux capture-pane` 的屏幕文本里认出**正在等你回答的那个选择器**。
 *
 * ⚠️ **为什么不从转录里读**：实测过了 —— Claude Code 的 `tool_use` 块
 * **要等工具跑完才写进 JSONL**。也就是说「问题挂在那儿等你」的那段时间里，
 * 转录里什么都没有（用户消息在、assistant 那条不在）。
 * 所以待答提示只能从屏幕来。历史照旧读转录，两者分工不同：
 * **转录是权威的历史，屏幕是唯一的「此刻」。**
 *
 * ⚠️ **顺带的好处**：屏幕上写着几号，我们就送几号。
 * 要是改成「从 JSON 读选项、按下标送键」，一旦顺序对不上就会**点 A 选中 B** ——
 * 那种错误不会报错，只会默默选错。**屏幕上的数字就是契约。**
 *
 * 按键协议全部实测过（送给 `tmux send-keys`）：
 *   · 单选：送数字 → **直接选中并确认**，不用再送 Enter
 *   · 多选：送数字 → 切换勾选；`Right` → 跳到 Submit 页；再送 `1` → 提交
 *   · 多个问题：`Left` / `Right` 在问题标签之间切换
 *   · 通用：`Up`/`Down`/`j`/`k` 移动，`Esc` 取消
 */
data class Pending(
    /** 问题正文。取不到就是空串（照样能选，只是没抬头）。 */
    val title: String,
    val options: List<Option>,
    /** 多选：数字是切换勾选，要 `Right` + `1` 才算提交。 */
    val multiSelect: Boolean,
) {
    data class Option(
        /** 屏幕上那个数字，**送键就送它**。 */
        val number: Int,
        val label: String,
        val description: String = "",
        /** 多选时当前是否已勾上。 */
        val checked: Boolean = false,
    )
}

object Prompt {

    private val OPTION = Regex("""^\s*[❯>]?\s*(\d+)\.\s+(.*\S)\s*$""")
    private val CHECKBOX = Regex("""^\[([ xX✔✓])]\s*(.*)$""")

    /** 底部这行是选择器的标志。没有它就说明当前没在等人选。 */
    private fun isFooter(l: String) =
        "to navigate" in l && ("Enter to" in l || "to select" in l)

    /**
     * @param screen `tmux capture-pane -p` 的原样输出
     * @return 没有在等人选就返回 null
     */
    fun parse(screen: String): Pending? {
        val lines = screen.lines()
        val footer = lines.indexOfLast(::isFooter)
        if (footer < 0) return null

        // ⚠️ **从脚注往上收，编号必须连续递减到 1。**
        // 不能只按「脚注上面 N 行里的编号行」算 —— 计划正文本身就常常是
        // `1. 烧水 / 2. 下面 / 3. 出锅` 这种编号列表，**就贴在选择器上面**。
        // 把它当成选项，用户点第 3 项时送出去的 `3` 会落到真选项的第 3 个上，
        // **点 A 选中 B，而且不报错**。连续性这条规则才挡得住。
        val numbered = HashMap<Int, Int>()      // 选项号 → 行号
        var expected = -1
        for (i in footer - 1 downTo 0) {
            val m = OPTION.matchEntire(lines[i])
            if (m == null) {
                if (expected == 0) break        // 已经收到 1 号，上面的不要了
                continue
            }
            val n = m.groupValues[1].toInt()
            when {
                expected == -1 -> { numbered[n] = i; expected = n - 1 }
                n == expected -> { numbered[n] = i; expected = n - 1 }
                else -> break                   // 编号断了 —— 上面那些不是这一组的
            }
            if (expected == 0) break
        }
        if (numbered.isEmpty() || !numbered.containsKey(1)) return null

        // 正向再走一遍，把选项之间的说明行挂到上一个选项上
        val firstLine = numbered.getValue(1)
        val opts = ArrayList<Pending.Option>()
        var multi = false
        // 不含脚注那行本身 —— 含了它会被当成最后一项的「说明」（真机上看见过）
        for (i in firstLine until footer) {
            val m = OPTION.matchEntire(lines[i])
            if (m != null && numbered[m.groupValues[1].toInt()] == i) {
                var label = m.groupValues[2]
                var checked = false
                CHECKBOX.matchEntire(label)?.let { c ->
                    multi = true
                    checked = c.groupValues[1].isNotBlank()
                    label = c.groupValues[2].trim()
                }
                opts += Pending.Option(m.groupValues[1].toInt(), label, checked = checked)
            } else if (opts.isNotEmpty() && !isNoise(lines[i])) {
                val last = opts.removeAt(opts.lastIndex)
                opts += if (last.description.isEmpty()) last.copy(description = lines[i].trim()) else last
            }
        }

        // 标题 = 1 号选项上面最后一行「像话」的文本
        val title = (firstLine - 1 downTo 0)
            .map { lines[it] }
            .firstOrNull { !isNoise(it) && OPTION.matchEntire(it) == null }
            ?.trim().orEmpty()

        return Pending(title, opts, multi)
    }

    /** 分隔线、标签栏（`←  ☒ 配菜  ✔ Submit  →`）、提示脚注这些不是内容。 */
    private fun isNoise(l: String): Boolean {
        val t = l.trim()
        return t.isEmpty() ||
            t.all { it == '─' || it == '-' || it == '━' } ||
            t.startsWith("←") || t.endsWith("→") ||
            t.startsWith("shift+tab") || t.startsWith("ctrl+") ||
            t.startsWith("❯ ")   // 用户自己刚敲的那行命令
    }
}
