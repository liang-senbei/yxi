package app.yxi.ui

import android.content.Context

/**
 * 没发出去的草稿，**按主机 + 会话分开存**。
 *
 * ⚠️ **为什么落盘而不是往上提一层。** 用户的原话是「切到终端再回来字就没了」——
 * 病根跟 TROUBLESHOOTING #96 是同一个：状态存在了比它自己命短的地方
 * （`ChatScreen` 里的 `remember`，切模式就销毁）。
 *
 * 提到 [Workspace] 能治「切模式」，但治不了「退回会话列表再进来」，
 * 更治不了「App 被系统杀掉」。而**草稿是用户亲手打的字，丢了就是丢了** ——
 * 这种东西的存放位置应该由「最坏情况」决定，不是由「这次报的那个场景」决定。
 * 落盘一行代码的事，顺手把三种情况一起解决。
 *
 * ⚠️ 只存**文本**。附件已经传到服务器上了，但它的本地预览授权
 * （[app.yxi.agent.Attachments.Staged.localUri]）活不过进程，存了也是半残 ——
 * 附件改成挂在 [Workspace] 上，够用且不骗人。
 */
internal object Drafts {
    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi", Context.MODE_PRIVATE)
    private fun key(hostId: String, session: String) = "draft:$hostId:$session"

    fun get(ctx: Context, hostId: String, session: String): String =
        p(ctx).getString(key(hostId, session), "") ?: ""

    /** ⚠️ 空草稿要**删键**不要存空串 —— 否则一台机器几十个会话，键会越攒越多。 */
    fun set(ctx: Context, hostId: String, session: String, text: String) {
        val e = p(ctx).edit()
        if (text.isBlank()) e.remove(key(hostId, session)) else e.putString(key(hostId, session), text)
        e.apply()
    }
}
