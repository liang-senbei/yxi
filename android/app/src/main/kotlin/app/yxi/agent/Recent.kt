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

    fun put(hostId: String, sessions: List<Session>) {
        byHost[hostId] = sessions
    }

    /** 没抓到过就返回空 —— 调用方该显示「读取中」，不是显示「没有会话」。 */
    fun get(hostId: String): List<Session> = byHost[hostId].orEmpty()
}
