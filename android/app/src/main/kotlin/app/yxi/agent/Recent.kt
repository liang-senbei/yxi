package app.yxi.agent

/**
 * 最近一次抓到的会话表，**按主机存在内存里**。
 *
 * 存在的理由只有一个：**工作区左上角那个下拉原来是「点了才去抓」**——
 * 打开菜单要等一整趟 SSH 往返（手机网络下动辄一两秒），用户的原话是
 * 「点击后显示有点太慢了，是不是我点击的时候才加载」。**就是。**
 *
 * 现在：会话页每次刷新顺手存一份，下拉**立刻**拿这份画出来，
 * 同时在后台再抓一次覆盖它。慢的那一趟仍然发生，但不再挡在用户和菜单之间。
 *
 * ⚠️ **只在内存里，不落盘**：会话状态几秒就变，存到磁盘只会让「刚打开 app
 * 看到一屏几小时前的假状态」——那比空着更误导。进程没了就没了，正好。
 */
object Recent {
    private val byHost = HashMap<String, List<Session>>()
    /** 那份快照是什么时候抓的（epoch 秒）。落盘那份靠它标「几分钟前」。 */
    private val atHost = HashMap<String, Long>()

    fun put(ctx: android.content.Context?, hostId: String, sessions: List<Session>) {
        byHost[hostId] = sessions
        val now = System.currentTimeMillis() / 1000
        atHost[hostId] = now
        ctx?.let { save(it, hostId, sessions, now) }
    }

    /** 没抓到过就返回空 —— 调用方该显示「读取中」，不是显示「没有会话」。 */
    fun get(hostId: String): List<Session> = byHost[hostId].orEmpty()

    /** 这份快照抓于何时。0 = 没有。 */
    fun at(hostId: String): Long = atHost[hostId] ?: 0L

    /**
     * 冷启动时从磁盘取回上一次的看板。
     *
     * ⚠️ **只在内存里那版是不够的。** 安卓会在后台把进程杀掉，
     * 用户再点开就是**一块空看板 + 「连接断了，正在重连…」** ——
     * 会话一个都不显示，看着像全没了（用户报的就是这个）。
     * 而这时候手机往往正在换网（他那台在三个出口 IP 之间跳），
     * 重连要好几秒，那几秒的空白最吓人。
     *
     * ⚠️ **但绝不能把它当成实时状态画。** 原来这里的注释说得对：
     * 「看到一屏几小时前的假状态比空着更误导」——所以落盘那份**必须带时间戳**，
     * 界面压暗 + 明说「断线中 · N 分钟前」。**旧但标明白**好过**空白**，
     * 这跟 #151/#154 是同一条教训。
     *
     * ⚠️ 超过 24 小时的直接不给 —— 那时候「上次是什么样」已经没有参考价值了。
     */
    fun load(ctx: android.content.Context, hostId: String): List<Session> {
        if (byHost.containsKey(hostId)) return byHost[hostId].orEmpty()
        val p = ctx.getSharedPreferences("yxi", android.content.Context.MODE_PRIVATE)
        val raw = p.getString("board:$hostId", null) ?: return emptyList()
        val out = runCatching {
            val o = org.json.JSONObject(raw)
            val ts = o.optLong("ts")
            if (ts <= 0 || System.currentTimeMillis() / 1000 - ts > 86_400) return emptyList()
            val arr = o.optJSONArray("s") ?: return emptyList()
            atHost[hostId] = ts
            (0 until arr.length()).mapNotNull { i ->
                val j = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = j.optString("n").ifEmpty { return@mapNotNull null }
                Session(
                    name = name,
                    windows = j.optInt("w", 1),
                    attached = j.optBoolean("a"),
                    cwd = j.optString("c"),
                    lastActivity = j.optLong("l"),
                    state = SessionState.of(j.optString("st").ifEmpty { null }),
                    detail = j.optString("d"),
                    stateTs = j.optDouble("t", 0.0),
                )
            }
        }.getOrDefault(emptyList())
        if (out.isNotEmpty()) byHost[hostId] = out
        return out
    }

    private fun save(ctx: android.content.Context, hostId: String, sessions: List<Session>, now: Long) {
        runCatching {
            val arr = org.json.JSONArray()
            sessions.forEach { s ->
                arr.put(
                    org.json.JSONObject()
                        .put("n", s.name).put("w", s.windows).put("a", s.attached)
                        .put("c", s.cwd).put("l", s.lastActivity)
                        // ⚠️ 存**代号**不存 label —— label 会跟着语言变，
                        // 存中文的话换成英文界面读回来就全落进 Idle
                        .put("st", stateCode(s.state)).put("d", s.detail).put("t", s.stateTs)
                )
            }
            ctx.getSharedPreferences("yxi", android.content.Context.MODE_PRIVATE).edit()
                .putString("board:$hostId", org.json.JSONObject().put("ts", now).put("s", arr).toString())
                .apply()
        }
    }

    /** 跟 [SessionState.of] 认的那几个字符串对上，round-trip 才不丢状态。 */
    private fun stateCode(s: SessionState) = when (s) {
        SessionState.NeedsYou -> "input"
        SessionState.Working -> "work"
        SessionState.Done -> "done"
        SessionState.Idle -> ""
    }
}
