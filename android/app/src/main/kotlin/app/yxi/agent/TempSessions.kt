package app.yxi.agent

import android.content.Context
import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession

/**
 * **临时会话**（用户 2026-09-05：「侧边栏点一下发起新的临时会话，模型可自定义、默认 sonnet、
 * 思考中等，这个会话不保存、重启 Yxi 就消失」）。
 *
 * 做法：在服务器 `/tmp/yxi-tmp/<id>` 里起一个 `cc-tmp-<id>` 的 tmux 会话，
 * `claude --model X --effort Y` —— **`--model` / `--effort` 是会话级参数，不碰账号默认**
 * （跟 `/model` 斜杠命令不同，见 TROUBLESHOOTING #265）。
 *
 * ⚠️ **「不保存」靠两条**：
 *  1. 名字记在**本机** prefs 里（按主机分开）。App 冷启动后第一次连上那台机器就把上次留下的
 *     全部收掉（[sweep]）：kill 会话、删 `/tmp/yxi-tmp/<id>`、删它的转录目录。
 *  2. 不走 `cloud-enter`，不在 `~/.cloud-sessions` 登记 —— 服务器重启时 watchdog 不会把它拉回来。
 *     有 `cloud-forget` 的机器上收掉时顺手也调一下，双保险。
 * ⚠️ 转录是 Claude Code 自己写的（`~/.claude/projects/-tmp-yxi-tmp-<id>/`），我们删的是**整个目录**，
 *    「不保存」才是真的不保存。
 */
object TempSessions {
    private const val KEY = "tmp-sessions:"
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)

    fun newId(): String = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    fun dirOf(id: String) = "/tmp/yxi-tmp/$id"
    fun nameOf(id: String) = "cc-tmp-$id"

    /**
     * 刚开出来的那一个。新会话本来就是空的，进去不必再 `/clear`
     * （而且那会儿 claude 还在启动，送早了会打在 shell 上）。只跳过一次。
     */
    @Volatile private var fresh: String? = null

    /** 进入这个临时会话时该不该先 `/clear`：刚开的不用，之前留着的要。 */
    fun needsClear(name: String): Boolean = if (fresh == name) { fresh = null; false } else true

    fun remember(ctx: Context, hostId: String, name: String) {
        fresh = name
        val cur = p(ctx).getStringSet(KEY + hostId, emptySet()).orEmpty().toMutableSet()
        cur += name
        p(ctx).edit().putStringSet(KEY + hostId, cur).apply()
    }

    private val swept = HashSet<String>()

    /**
     * App 这次运行里**第一次**连上这台机器时调一次：把上次运行留下的临时会话全收掉。
     * ⚠️ 只收 `cc-tmp-` 开头的名字 —— prefs 被改坏也不会误杀别的会话。
     */
    suspend fun sweep(ctx: Context, ssh: SshSession, hostId: String) {
        if (!swept.add(hostId)) return
        val names = p(ctx).getStringSet(KEY + hostId, emptySet()).orEmpty()
            .filter { it.startsWith("cc-tmp-") && Shell.safeName(it) }
        if (names.isEmpty()) return
        runCatching { ssh.exec(killCommand(names)) }
        p(ctx).edit().remove(KEY + hostId).apply()
    }

    /** 收掉这几个：先移出 watchdog 名单（有的话），再 kill，再删目录和转录。 */
    fun killCommand(names: Collection<String>): String = names.joinToString("; ") { n ->
        val id = n.removePrefix("cc-tmp-")
        val q = Shell.q(n)
        "if command -v cloud-forget >/dev/null 2>&1; then cloud-forget $q >/dev/null 2>&1; fi; " +
            "tmux kill-session -t $q 2>/dev/null; " +
            "rm -rf ${Shell.q(dirOf(id))} \"\$HOME/.claude/projects/-tmp-yxi-tmp-${id.filter { it.isLetterOrDigit() || it == '-' }}\" 2>/dev/null"
    } + "; true"
}
