package app.yxi.ui

import android.content.Context
import app.yxi.agent.SessionProbe
import app.yxi.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 发消息 —— **挂在 app 级 scope 上，活得比界面久**（跟 [UpdateDownloader] 同一个道理）。
 *
 * ⚠️ 病根（用户报的）：发送按钮原来跑在对话界面的 `rememberCoroutineScope` 上，
 * **点完箭头立刻切走，协程就被取消** —— 而草稿在点的那一刻已经清掉并落盘了，
 * 于是那句话**凭空消失、也没发出去**。用户原话：
 * 「要在对话里面等几秒再返回才算发给 agent 了」。
 *
 * 现在：送键跑在这个永不取消的 scope 上，[SessionProbe.send] 自己还是 NonCancellable
 * （保证「打字」和「回车」不会被劈开）。**发失败就把话还回草稿**，绝不让它凭空没了。
 */
object Sender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** @param onFail 发不出去时回调（界面可以提示一下）。草稿已经替你还回去了。 */
    fun send(
        ctx: Context,
        ssh: SshSession?,
        hostId: String,
        session: String,
        text: String,
        onFail: (String) -> Unit = {},
    ) {
        if (text.isBlank()) return
        val app = ctx.applicationContext
        scope.launch {
            // ⚠️ exec 是「失败静默返回空」的，所以不能只看有没有抛异常，还要看连接是否真活着
            val ok = runCatching {
                val s = ssh ?: return@runCatching false
                if (!s.isConnected) return@runCatching false
                SessionProbe.send(s, session, text)
                s.isConnected
            }.getOrDefault(false)
            if (!ok) {
                // 把话还给草稿 —— 宁可让它重新出现在输入框，也不能让用户以为发了、其实没发
                val cur = Drafts.get(app, hostId, session)
                Drafts.set(app, hostId, session, if (cur.isBlank()) text else "$text\n$cur")
                onFail(t("没发出去，话给你留在输入框了"))
            }
        }
    }
}
