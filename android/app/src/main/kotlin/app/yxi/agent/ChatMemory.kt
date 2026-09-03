package app.yxi.agent

/**
 * 进过的对话留在内存里：解析好的条目 + 解析器状态 + 转录读到的**字节位置**。
 *
 * ⚠️ **为什么要有它。** 原来每次进对话页都从头来：找转录文件（一趟）、拉最新 60 行（0.5MB，一趟）、
 * 再 `tail -n 400 -f`（4MB）灌历史 —— 手机网络下就是那几秒「正在载入」。用户原话：
 * 「从 Yxi 退到看板再进 Yxi 或 logto，显示读取会话状态中，很影响效率」。
 * 现在：退出时条目和解析器都留着，重进**先把上次的原样摆出来**，再从上次读到的字节位置
 * `tail -c +N -f` **只拉增量** —— 没新内容就是零流量，有新内容也只是那几行。
 *
 * ⚠️ 只留最近 6 个会话（LRU）：一个会话几百条解析好的条目占不了多少，但别无限攒。
 * ⚠️ 转录文件换了（`/clear`、换了 --resume 的 uuid）就整个作废重来 —— 键是会话，文件在 [Entry.file] 里核对。
 */
object ChatMemory {

    class Entry(val file: String, val inc: Transcript.Incremental) {
        var items: List<ChatItem> = emptyList()
        /** 转录已经读到这个字节位置（0 起）；下次从这儿接着 `tail -c` */
        var offset: Long = 0
        var ctx: Transcript.Ctx? = null
    }

    private val map = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 6
    }

    fun key(hostId: String, session: String) = "$hostId|$session"

    @Synchronized fun get(key: String): Entry? = map[key]
    @Synchronized fun put(key: String, e: Entry) { map[key] = e }
    @Synchronized fun drop(key: String) { map.remove(key) }
}
