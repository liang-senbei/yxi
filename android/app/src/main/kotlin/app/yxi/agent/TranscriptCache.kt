package app.yxi.agent

import android.content.Context
import java.io.File

/**
 * 上次看到的那几条对话，存在磁盘上。
 *
 * ⚠️ **这是「从后台切回来一片空白」的正解。**
 * 病根有两条，叠在一起：
 *  1. 荣耀这类国产 ROM 后台管控很凶，App 在后台待一会儿**进程就被杀了**。
 *     回前台是整个重建，Compose 里 `remember` 的消息列表全空。
 *  2. 重新拉转录要一个 SSH 往返 + 0.58 MB（`TranscriptStream.head`）。
 *     而那段时间 `items` 是空的、`status` 又刚被清成 null ——
 *     LazyColumn 上面什么都没有 = **纯白屏，零解释**。用户原话：什么都不显示。
 *
 * 所以进对话时**先把上次那几条从磁盘画出来**，再去拉新的替换。
 * 存的是**原始 JSONL 行**不是解析后的对象 —— 复用现成的解析路径，
 * 不用给 ChatItem 写一套序列化（那玩意儿每加一种卡片就得改一次）。
 */
object TranscriptCache {

    private fun dir(ctx: Context) = File(ctx.cacheDir, "transcript").apply { mkdirs() }

    /** 文件名不能直接用会话名/路径 —— 里面有斜杠。取个稳定的哈希。 */
    private fun key(sessionName: String, cwd: String) =
        Integer.toHexString("$sessionName $cwd".hashCode()) + ".jsonl"

    fun load(ctx: Context, sessionName: String, cwd: String): List<String> =
        runCatching {
            File(dir(ctx), key(sessionName, cwd)).readLines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    /**
     * ⚠️ **空的不写**。连接刚断、拉回来一手空的时候要是覆盖上去，
     * 就把唯一那份能立刻画出来的东西给擦了 —— 下次回前台照样白屏。
     * ⚠️ 写不进去（缓存目录被系统清了、没空间）**不算错**，静默放过：
     * 缓存只是加速，不该让主流程失败。
     */
    fun save(ctx: Context, sessionName: String, cwd: String, lines: List<String>) {
        if (lines.isEmpty()) return
        runCatching {
            File(dir(ctx), key(sessionName, cwd)).writeText(lines.joinToString("\n"))
        }
    }
}
