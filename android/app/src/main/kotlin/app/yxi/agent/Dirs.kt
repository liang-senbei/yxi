package app.yxi.agent

/**
 * 「新开一个会话，开在哪个目录」的候选列表。
 *
 * ⚠️ **原来列的是「现有会话的 cwd」—— 正好反了。** 那些目录**已经开着会话**，
 * 再点一次只会跳回同一个会话，等于这个入口什么也没做。
 * 用户的原话：「已经开着的对话就不支持再开」。
 *
 * 现在列的是**那些工作区目录里还没开会话的**：
 * 从现有会话的 cwd 取父目录（`/root/src/workspace/Yxi` → `/root/src/workspace`），
 * 列出它们的兄弟目录，再把已经有会话的减掉。
 */
object Dirs {

    /**
     * 列目录的命令。
     *
     * ⚠️ **只列一层**（`-maxdepth 1`）：工作区下面动辄几万个文件，
     * 递归列会把 SSH 通道塞满，而我们要的只是「有哪几个项目」。
     * ⚠️ 每个父目录单独一条 `find`，某一个不存在不影响别的（`2>/dev/null`）。
     */
    fun listCommand(parents: Collection<String>): String? {
        val ps = parents.filter { it.startsWith("/") }.distinct().take(6)
        if (ps.isEmpty()) return null
        return ps.joinToString("; ") { p ->
            "find '${p.replace("'", "'\\''")}' -maxdepth 1 -mindepth 1 -type d 2>/dev/null"
        }
    }

    /** 从现有会话的 cwd 反推该去哪几个父目录里找。 */
    fun parentsOf(cwds: Collection<String>): List<String> =
        cwds.mapNotNull { cwd ->
            val t = cwd.trimEnd('/')
            val i = t.lastIndexOf('/')
            if (i <= 0) null else t.substring(0, i)
        }.distinct()

    /**
     * @param out [listCommand] 的原样输出
     * @param taken 已经开着会话的目录 —— **这些要剔掉**
     * @return 还没开会话的候选目录，按名字排
     */
    fun candidates(out: String, taken: Collection<String>): List<String> {
        val busy = taken.map { it.trimEnd('/') }.toSet()
        return out.lineSequence()
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("/") }
            // ⚠️ 隐藏目录不列（`.git` / `.cache` 那些不是项目）
            .filterNot { it.substringAfterLast('/').startsWith(".") }
            .filterNot { it in busy }
            .distinct()
            .sortedBy { it.substringAfterLast('/').lowercase() }
            .toList()
    }

    /** shell 单引号里安全地嵌一个值。⚠️ 是 `'\''` 四个字符 —— 少一个反斜杠就成了 `'''`，是错的。 */
    private fun q(v: String) = v.replace("'", "'\\''")

    const val TAG = "__YXI_NEW__"

    /**
     * 「在这个目录开一个新会话」的命令。
     *
     * ⚠️ **`tmux new-session -c <不存在的目录>` 会返回 0，然后跑到 `$HOME` 去。**
     * 不报错、不非零退出 —— 调用方 `runCatching` 看到的是成功，
     * 于是 App 高高兴兴跳进一个根本不在你指定位置的会话。
     * 用户报的就是这个：想在 `/root/src/workspace/logto` 开，最后开在了 `/root`。
     *
     * 所以这里做两件事：
     *  1. **先 `mkdir -p`** —— 目录不存在就建出来（用户要的「没有就直接创建」）
     *  2. **建完回头核对 `pane_current_path`** —— 不信 tmux 的退出码，只信它真正落在哪。
     *     对不上就把会话杀掉再报错，不留一个「名字对、位置错」的会话在那儿骗人。
     *
     * 两边都过一遍 `cd && pwd -P`，免得 `/root/src` 这种软链把比较搞砸。
     */
    fun createCommand(
        dir: String, session: String,
        /** 跑哪个 agent：claude / codex；null = 按会话名前缀（`cx-` = codex）*/
        agent: String? = null,
        /**
         * 「拉起」歇掉的会话时传 true：接上这个目录里**最近那份转录**（`claude --resume <uuid>`），
         * 不是开一个全新对话。⚠️ #218：只跑了 bootstrap.sh 的机器上 `claude` 是裸二进制，没有 cloud-enter
         * 那套登记表帮它接 —— App 不传 `--resume` 的话「拉起」永远是新对话，对话页却还显示旧转录。
         * 转录目录名 = 路径里非字母数字全换成 `-`（跟 [Transcript.projectDirOf] 一致）。
         * Codex 没有这条（`codex resume --last` 在没历史时会报错），照旧裸起。
         */
        resume: Boolean = false,
    ): String {
        val d = q(dir.trimEnd('/').ifBlank { "/" })
        val n = q(session)
        // ⚠️ 只认这两个，别的一律退回 claude —— 这行最终是 send-keys 进 shell 的
        val a = if (agent == "codex" || (agent == null && session.startsWith("cx-"))) "codex" else "claude"
        val launch = if (resume && a == "claude") """
            enc=${'$'}(printf %s "${'$'}want" | sed 's/[^A-Za-z0-9]/-/g')
            u=${'$'}(ls -t "${'$'}HOME/.claude/projects/${'$'}enc"/????????-????-????-????-????????????.jsonl 2>/dev/null | head -1)
            u=${'$'}{u##*/}; u=${'$'}{u%.jsonl}
            if [ -n "${'$'}u" ]; then tmux send-keys -t "${'$'}n" "claude --resume ${'$'}u" Enter; else tmux send-keys -t "${'$'}n" 'claude' Enter; fi
        """.trimIndent() else """tmux send-keys -t "${'$'}n" '$a' Enter"""
        return """
            d='$d'; n='$n'
            mkdir -p "${'$'}d" 2>/dev/null
            [ -d "${'$'}d" ] || { echo '$TAG:nodir'; exit 0; }
            if tmux has-session -t "${'$'}n" 2>/dev/null; then echo '$TAG:exists'; exit 0; fi
            tmux new-session -d -s "${'$'}n" -c "${'$'}d" 2>/dev/null || { echo '$TAG:failed'; exit 0; }
            want=${'$'}(cd "${'$'}d" 2>/dev/null && pwd -P)
            got=${'$'}(tmux display-message -p -t "${'$'}n" '#{pane_current_path}' 2>/dev/null)
            got=${'$'}(cd "${'$'}got" 2>/dev/null && pwd -P)
            if [ "${'$'}want" != "${'$'}got" ]; then
              tmux kill-session -t "${'$'}n" 2>/dev/null
              echo "$TAG:wrongdir:${'$'}got"; exit 0
            fi
            $launch
            echo '$TAG:ok'
        """.trimIndent()
    }

    /**
     * [createCommand] 的结果。只有 [Made.Ok] / [Made.Exists] 才可以跳进那个会话。
     *
     * ⚠️ 失败只带**代号**，不带话术 —— [Dirs] 是纯逻辑（能单测、拿不到 Context），
     * 在这儿写中文的话，英文界面会原样吐中文，而 `dev/i18n-check.sh` 看不见它。
     * 话术在 UI 层用 `t()` 拼。
     */
    sealed interface Made {
        object Ok : Made
        object Exists : Made
        /** @param code nodir / failed / wrongdir / noresult / unknown */
        data class Failed(val code: String, val detail: String = "") : Made
    }

    /**
     * 读 [createCommand] 的输出。
     *
     * ⚠️ **认不出来一律当失败**（fail-closed）。跳进一个没建成的会话，
     * 用户看到的是一片空白加「连不上」，比直接说「没开成」难查得多。
     */
    fun madeFrom(out: String): Made {
        val line = out.lineSequence().lastOrNull { it.trim().startsWith(TAG) }?.trim()
            ?: return Made.Failed("noresult")
        val body = line.removePrefix("$TAG:")
        return when {
            body == "ok" -> Made.Ok
            body == "exists" -> Made.Exists
            body == "nodir" -> Made.Failed("nodir")
            body == "failed" -> Made.Failed("failed")
            body.startsWith("wrongdir") -> Made.Failed("wrongdir", body.substringAfter("wrongdir:"))
            else -> Made.Failed("unknown", body)
        }
    }
}
