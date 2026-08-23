package app.yxi.agent

import app.yxi.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 把远端的 Claude Code 转录流回来。
 *
 * ⚠️ **读转录文件，不刮终端屏幕。** 终端里 TUI 会重绘、有 spinner、会折行，
 * 刮屏必然脆；转录是结构化的、权威的（PRD 附录 D.1）。
 *
 * ⚠️ **服务器上不需要装任何东西** —— `ls` 和 `tail` 是系统自带的。
 */
object TranscriptStream {

    /**
     * 找出某个 cwd 对应的最新转录文件。
     * 没有就返回 null（这个会话里可能压根没跑过 Claude Code）。
     */
    suspend fun latestFor(ssh: SshSession, cwd: String): String? {
        val dir = "\$HOME/.claude/projects/" + Transcript.projectDirOf(cwd)
        val out = ssh.exec("ls -t $dir/*.jsonl 2>/dev/null | head -1").trim()
        return out.takeIf { it.isNotEmpty() && it.endsWith(".jsonl") }
    }

    /**
     * `tail` 出最后 [backlog] 行然后持续跟随。每收到一行发一次。
     *
     * 用 `tail -n N -f`：**先给历史再跟随**，这样打开界面就有内容，
     * 不用等 Claude 下一次说话。
     */
    fun stream(ssh: SshSession, file: String, backlog: Int = 800): Flow<String> = flow {
        val shell = ssh.openExecStream(SshSession.follow("tail -n $backlog -f '$file'"))
        // ⚠️ **取消协程不会打断阻塞在 readLine() 上的线程** —— 它不是挂起点。
        // 不主动关通道的话，`finally` 永远轮不到执行，线程和远端进程一起挂着。
        val onCancel = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { runCatching { shell.close() } }
        try {
            val reader = shell.output.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                emit(line)
            }
        } finally {
            onCancel?.dispose()
            shell.close()
        }
    }.flowOn(Dispatchers.IO)
}
