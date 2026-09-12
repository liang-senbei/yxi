package app.yxi.desktop

import app.yxi.agent.Dirs
import app.yxi.ssh.Shell
import java.util.UUID

data class DesktopLaunchPlan(val directory: String, val agent: String, val requestId: String) {
    init {
        require(agent in listOf("claude", "codex")) { "不支持的运行器" }
        require(directory.startsWith('/') && directory.none { it < ' ' || it == '\u007f' }) { "请输入服务器上的绝对路径，不含控制字符" }
        require(Regex("[a-f0-9]{32}").matches(requestId)) { "启动请求标识无效" }
    }
    val sessionName: String get() {
        val slug = directory.trimEnd('/').substringAfterLast('/').map { if (it.isLetterOrDigit() || it in "_-") it else '_' }.joinToString("").take(40).ifBlank { "workspace" }
        return (if (agent == "codex") "cx-" else "cc-") + slug + "-" + requestId
    }
    fun command(): String {
        val tag = Dirs.TAG
        return """
d=${Shell.q(directory)}; n=${Shell.q(sessionName)}; agent=${Shell.q(agent)}
command -v tmux >/dev/null 2>&1 || { echo '$tag:missing-tmux'; exit 0; }
bin=${'$'}(command -v "${'$'}agent")
[ -f "${'$'}bin" ] && [ -x "${'$'}bin" ] || { echo '$tag:missing-runtime'; exit 0; }
mkdir -p -- "${'$'}d" 2>/dev/null || { echo '$tag:nodir'; exit 0; }
want=${'$'}(cd -- "${'$'}d" 2>/dev/null && pwd -P) || { echo '$tag:nodir'; exit 0; }
if tmux has-session -t "=${'$'}n" 2>/dev/null; then
  got=${'$'}(tmux display-message -p -t "=${'$'}n:" '#{pane_current_path}' 2>/dev/null)
  [ "${'$'}got" = "${'$'}want" ] || { echo '$tag:conflict'; exit 0; }
  echo '$tag:exists'; exit 0
fi
tmux new-session -d -s "${'$'}n" -c "${'$'}want" /bin/sh -c 'exec "${'$'}1"' yxi-launch "${'$'}bin" 2>/dev/null || { echo '$tag:failed'; exit 0; }
sleep 0.2
tmux has-session -t "=${'$'}n" 2>/dev/null || { echo '$tag:exited'; exit 0; }
got=${'$'}(tmux display-message -p -t "=${'$'}n:" '#{pane_current_path}' 2>/dev/null)
[ "${'$'}got" = "${'$'}want" ] || { echo '$tag:changed-directory'; exit 0; }
echo '$tag:ok'
""".trimIndent()
    }
    companion object {
        fun newRequestId() = UUID.randomUUID().toString().replace("-", "")
    }
}
