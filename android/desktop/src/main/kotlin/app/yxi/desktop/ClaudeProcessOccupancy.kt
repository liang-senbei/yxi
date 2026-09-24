package app.yxi.desktop

import java.io.File

/** Only positive process-argument evidence is conclusive; an empty result is not proof of external idleness. */
internal object ClaudeProcessOccupancy {
    fun claimsSession(command: String, arguments: List<String>, runtime: LocalRuntimeInstallation, sessionId: String): Boolean {
        val executable = command.replace('\\', '/').substringAfterLast('/').lowercase()
        val selectedExecutable = runtime.command.firstOrNull()?.let { runCatching { File(it).canonicalPath == File(command).canonicalPath }.getOrDefault(false) } == true
        val native = executable in setOf("claude", "claude.exe") || (selectedExecutable && executable !in setOf("node", "node.exe"))
        val nodeCli = executable in setOf("node", "node.exe") && arguments.firstOrNull()?.replace('\\', '/')?.let {
            it.endsWith("/@anthropic-ai/claude-code/cli.js")
        } == true
        if (!native && !nodeCli) return false
        val args = if (nodeCli) arguments.drop(1) else arguments
        for ((index, argument) in args.withIndex()) {
            if (argument == "--") break
            if (argument in setOf("--resume", "-r", "--session-id") && args.getOrNull(index + 1) == sessionId) return true
            if (argument in setOf("--resume=$sessionId", "--session-id=$sessionId")) return true
        }
        return false
    }
    fun requireNoKnownOwner(runtime: LocalRuntimeInstallation, sessionId: String) {
        ProcessHandle.allProcesses().use { processes ->
            val owner = processes.filter { process ->
                if (!process.isAlive || process.pid() == ProcessHandle.current().pid()) false else {
                    val info = process.info()
                    claimsSession(info.command().orElse(""), info.arguments().orElse(emptyArray()).toList(), runtime, sessionId)
                }
            }.findFirst()
            check(owner.isEmpty) { "原会话仍由本机 Claude 进程 ${owner.get().pid()} 使用，请先结束该会话" }
        }
    }
}
