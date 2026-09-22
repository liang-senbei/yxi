package app.yxi.desktop

import app.yxi.agent.*
import app.yxi.ssh.Shell
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** Applying a route must never call Lines.apply: that changes other agents in the same scope. */
internal object ConversationRouteApply {
    data class Receipt(val processIdentity: String, val sessionId: String, val settingsPath: String)
    internal val script get() = ConversationRouteApply::class.java
        .getResource("/app/yxi/desktop/apply-conversation-route.py")!!.readText()

    internal fun settings(line: Lines.Line): JSONObject {
        require(line.agent == Lines.CLAUDE) { "此适配器仅支持 Claude Code" }
        require(Lines.rejectedKeys(line.extra).isEmpty()) { "配置含不支持的字段，请先在配置页修正" }
        return line.settingsJson()
    }

    suspend fun apply(conn: Conn, session: Session, line: Lines.Line): Receipt = conn.instructionDeliveryMutex.withLock {
        check(session.agent == Lines.CLAUDE && session.runtimeId.isNotBlank() && conn.ssh.isConnected)
        check(!RewindDelivery.gate.blocked(taskNavigationKey(conn.host, session))) { "请先完成回退核验" }
        val screen = conn.ssh.exec("tmux capture-pane -p -t ${Shell.q("=" + session.name + ":")}")
        check(Model.borrowable(screen) && Prompt.parse(screen) == null) { "请等待任务结束，并处理终端中未发送的内容" }
        val mode = PermissionMode.fromScreen(screen) ?: error("无法确认当前权限模式，未重启会话")
        val capture = (Rewind.parseCapture(conn.ssh.exec(Rewind.captureCommand(session.name))) as? Rewind.Got)?.capture
            ?: error("无法确认当前 Claude 进程，未重启会话")
        try {
            val home = conn.ssh.exec("printf %s \"\$HOME\"").trim()
            check(home.startsWith('/') && home.none { it < ' ' }) { "无法确认服务器家目录" }
            val token = UUID.randomUUID().toString()
            val path = "$home/.yxi/agent-settings/$token.json"
            val input = JSONObject().put("settings", settings(line)).put("token", token).toString()
            RemoteAtomicJson.write(conn.ssh, path, input, "missing")?.let { error(it) }
            val args = listOf("apply", session.name, session.runtimeId, capture.paneId, capture.pid, capture.exe,
                RemoteAtomicJson.hash(screen.trimEnd('\n').toByteArray()), path,
                RemoteAtomicJson.hash(input.toByteArray()), mode.nativeId)
            fun command(values: List<String>) = "python3 -c ${Shell.q(script)} " + values.joinToString(" ", transform = Shell::q) + " 2>/dev/null"
            val response = runCatching { JSONObject(conn.ssh.exec(command(args)).trim()) }.getOrNull()
                ?: error("未收到应用确认，请查看终端并核对；不会自动再次重启")
            check(response.optString("status") == "restarted") { "配置未应用：${response.optString("error", "无法核对会话状态")}" }
            val sid = response.getString("sessionId")
            val digest = response.getString("settingsDigest")
            repeat(80) {
                val result = runCatching { JSONObject(conn.ssh.exec(command(listOf("verify", session.name,
                    session.runtimeId, capture.paneId, capture.exe, sid, path, digest, token))).trim()) }.getOrNull()
                if (result?.optString("status") == "verified") {
                    return@withLock Receipt(result.getString("processIdentity"), sid, path)
                }
                if (result?.optString("status") == "changed") error("会话或配置已变化，未记录为生效；请查看终端")
                delay(250)
            }
            error("运行器已重启，但新进程尚未确认。请查看终端处理提示后核对，未记录为生效")
        } finally { runCatching { conn.ssh.exec(Rewind.cleanupCommand(capture)) } }
    }
}
