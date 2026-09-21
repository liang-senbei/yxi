package app.yxi.agent

import app.yxi.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart

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
     * 找出这个会话的最新转录文件。没有就返回 null。
     *
     * ⚠️⚠️ **按 sessionId 找，别只按目录找。**
     *
     * Claude Code 的转录目录是拿**启动时**那个目录的名字拼的，而
     * `pane_current_path` 是**此刻**的目录 —— 会话里 `cd` 一下，两者就永久对不上。
     * 用户报的就是这个：`cc-hexingyang` 在 `/root/src/workspace/hexingyang` 启动、
     * 后来 `cd` 进了 `unitree_rl_mjlab-main`，于是对话页一直显示
     * 「这个会话里没找到 Claude Code 的转录」，而转录一直好好地在原来那个目录里写着。
     *
     * `~/.claude/sessions/` 下那些 json 里有 `tmux`（会话名）和 `sessionId`，
     * 而转录文件名就是 `<sessionId>.jsonl` —— 这条链是精确的，不受 cd 影响。
     *
     * ⚠️ **找不到 sessionId 才退回按目录找**（老路径）。不往父目录爬：
     * `/a/b/c` 爬到 `/a/b` 很可能撞上**另一个会话**的转录，
     * 显示错人的对话比显示「没找到」糟得多。
     *
     * @param session tmux 会话名（如 `cc-mail`）。给空就只走按目录那条老路。
     */
    suspend fun latestFor(ssh: SshSession, cwd: String, session: String = ""): String? {
        val q = session.replace("'", "'\\''")
        val dir = Transcript.projectDirOf(cwd)
        val out = ssh.exec(
            "p=\"\$HOME/.claude/projects\"; n='$q'; " +
                "if [ -n \"\$n\" ]; then " +
                // ⚠️⚠️ **同一个 tmux 名字可能有多份 session json**（本机 37 个会话里 25 个是这样：
                //    每次重开 / --resume 都会新写一份）。原来是 `grep -l … | head -1` ——
                //    取的是**输出顺序的第一个，不是最新的那个**，而输出顺序根本不可靠：
                //    这台机器上 /usr/bin/grep 是 **ugrep（多线程）**，实测同一条命令连跑三次，
                //    第一次和后两次的顺序就不一样。哪天某个会话 /clear 过、两份 json 指向不同
                //    sessionId，界面就会在两份转录之间来回跳（顶栏的模型和上下文也跟着变）。
                //    → 先 grep 出全部，再 `ls -t` 取**最新**。
                //    （文件名是 `<pid>.json` / uuid，没有空格，所以这里可以不加引号做词分割。）
                "m=\$(grep -l \"\\\"tmux\\\":\\\"\$n:\" \"\$HOME\"/.claude/sessions/*.json 2>/dev/null); " +
                "[ -n \"\$m\" ] && m=\$(ls -t \$m 2>/dev/null | head -1); " +
                "if [ -n \"\$m\" ]; then " +
                "s=\$(sed -n 's/.*\"sessionId\":\"\\([^\"]*\\)\".*/\\1/p' \"\$m\" 2>/dev/null | head -1); " +
                "if [ -n \"\$s\" ]; then r=\$(ls -t \"\$p\"/*/\"\$s\".jsonl 2>/dev/null | head -1); " +
                "[ -n \"\$r\" ] && { printf '%s\\n' \"\$r\"; exit 0; }; fi; fi; fi; " +
                "ls -t \"\$p/$dir\"/*.jsonl 2>/dev/null | head -1"
        ).trim()
        return out.lineSequence().map { it.trim() }.firstOrNull { it.endsWith(".jsonl") }
    }

    /**
     * **先只取最近这几条**，一个来回就回来，用来立刻画出「最新的那一屏」。
     *
     * ⚠️ **这是「进对话要等好久」的正解。** `tail -n N` 是**从这 N 行里最老的
     * 那条开始吐**，最新的一条**最后才到** —— 所以完整那次要等整包传完才看得见
     * 最新内容。实测同一个会话：`tail -n 800` 是 **4.17 MB**，
     * 而 `tail -n 60` 只有 **0.58 MB**（大头全堆在 200 条以外的老行里，
     * 工具输出动辄几十 KB）。手机上那是 7 倍的等待。
     *
     * 用户自己想到的：「我很久没点进去就先加载最新的会不会好一点」—— 对。
     */
    suspend fun head(ssh: SshSession, file: String, lines: Int = 60): List<String> =
        ssh.exec("tail -n $lines '$file'").lineSequence().filter { it.isNotBlank() }.toList()

    /**
     * `tail` 出最后 [backlog] 行然后持续跟随。每收到一行发一次。
     *
     * 用 `tail -n N -f`：**先给历史再跟随**，这样打开界面就有内容，
     * 不用等 Claude 下一次说话。
     */
    /**
     * 最后 [backlog] 行从哪个字节开始：返回 (文件大小, 起点)。一趟 exec 两个数一起拿。
     * ⚠️ 起点是 0 起的字节位置；`tail -c +N` 是 1 起的，[streamFrom] 里会 +1。
     */
    suspend fun tailStart(ssh: SshSession, file: String, backlog: Int): Pair<Long, Long>? {
        // Read a fixed file-size snapshot; stat followed by tail races with concurrent appends.
        val script = """
import os, sys
with open(sys.argv[1], 'rb') as f:
    size = os.fstat(f.fileno()).st_size
    pos, count = size, 0
    need = int(sys.argv[2])
    start = 0
    while pos > 0:
        n = min(pos, 65536)
        pos -= n
        f.seek(pos)
        block = f.read(n)
        for i in range(len(block)-1, -1, -1):
            if block[i] == 10 and pos+i != size-1:
                count += 1
                if count == need:
                    start = pos+i+1
                    break
        if count == need: break
    print(size, start)
""".trimIndent()
        val out = ssh.exec("python3 -c ${app.yxi.ssh.Shell.q(script)} ${app.yxi.ssh.Shell.q(file)} ${backlog.coerceAtLeast(1)}").trim()
        val parts = out.split(Regex("\\s+"))
        val size = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val start = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return (size to start).takeIf { size >= 0 && start in 0..size }
    }

    /**
     * 从字节位置 [offset]（0 起）开始持续跟随 —— **重进对话只拉增量**（[ChatMemory]）。
     * 文件要是被重写得比 offset 还短，`tail -c` 什么都不吐、等它长回来；那种情况上层按「转录文件换了」处理。
     */
    fun streamFrom(ssh: SshSession, file: String, offset: Long, windowSeconds: Int = 0): Flow<String> =
        // ⚠️⚠️ **起点先夹到文件大小以内。** `tail -c +N -f` 的 N 一旦超过文件大小，GNU tail 在文件下次变长时
        //    判成「file truncated」，从第 0 字节把整个文件重放（#273，几百 MB 的转录 = 几周前的对话涌上屏）。
        //    上层的 offset 是自己数出来的，任何一处多数了一个字节（心跳空行、编码差异）都会踩进去；
        //    这里在服务器上花一次 stat 兜住，比指望所有调用方数得分毫不差可靠。
        follow(
            ssh,
            "f='$file'; o=$offset; s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0); " +
                "[ \"\$o\" -gt \"\$s\" ] && o=\$s; " +
                (if (windowSeconds > 0) "timeout ${windowSeconds.coerceIn(5, 60)}s " else "") +
                "tail -s 0.2 -c +\$((o+1)) -f \"\$f\"",
        )

    fun stream(ssh: SshSession, file: String, backlog: Int = 800): Flow<String> =
        follow(ssh, "tail -n $backlog -f '$file'")

    private fun follow(ssh: SshSession, cmd: String): Flow<String> = flow { coroutineScope {
        val shell = ssh.openExecStream(SshSession.follow(cmd))
        // ⚠️ **取消协程不会打断阻塞在 readLine() 上的线程** —— 它不是挂起点。
        // 不主动关通道的话，`finally` 永远轮不到执行，线程和远端进程一起挂着。
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { shell.close() } }
        }
        try {
            val reader = shell.output.bufferedReader()
            val line = StringBuilder()
            val chunk = CharArray(8192)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                for (i in 0 until count) {
                    if (chunk[i] == '\n') { emit(line.toString()); line.setLength(0) }
                    else line.append(chunk[i])
                }
            }
        } finally {
            closer.cancel()
            shell.close()
        }
    } }.flowOn(Dispatchers.IO)
}
