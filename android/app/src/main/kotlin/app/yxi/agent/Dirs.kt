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
}
