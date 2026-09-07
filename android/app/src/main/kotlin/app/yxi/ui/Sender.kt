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
        /**
         * **等一条活着的连接**（[app.yxi.ui.ChatScreen] 里的 `aliveSsh`）。
         *
         * ⚠️⚠️ **不能传一个抓好的 SshSession 进来。** 原来就是那样：调用点把 Composable 里
         * 那个 `ssh` 传进来，这边 `if (!s.isConnected) return false` 立刻判失败、把话还回输入框。
         * 而**上传附件正是最容易把连接换掉的操作**（几张图 / 一段视频，中途断线重连很常见，
         * 上传那条路已经为此做了等待重连 —— 见 Uploader），发送这条路却没有。
         * 于是：传完附件点发送 → 拿到的是刚死的那条 → 当场判失败 →
         * **话又回到输入框里**。老板报的「上传有附件的时候还在对话框里面没发出去」就是这个。
         */
        aliveSsh: suspend (Long) -> SshSession?,
        hostId: String,
        session: String,
        text: String,
        onFail: (String) -> Unit = {},
    ) {
        if (text.isBlank()) return
        val app = ctx.applicationContext
        scope.launch {
            // ⚠️ exec 是「失败静默返回空」的，所以不能只看有没有抛异常，还要看连接是否真活着
            // ⚠️ 两件事都不能靠猜：
            //  ① **连接**：等一条活着的（最多 20 秒），别拿一个抓好的对象判死刑 —— 见上面 aliveSsh。
            //  ② **提交**：送完回头查输入框空没空（[SessionProbe.send] 自己做），
            //     别「没抛异常就算发出去了」。
            val ok = runCatching {
                val s = aliveSsh(20_000) ?: return@runCatching false
                SessionProbe.send(s, session, text)
            }.getOrDefault(false)
            // ⚠️ 记在**发成功之后**：发失败的话已经还回输入框了，把它记进「发过的话」就是骗人。
            if (ok) SentLog.add(app, hostId, session, text)
            if (!ok) {
                // 把话还给草稿 —— 宁可让它重新出现在输入框，也不能让用户以为发了、其实没发
                val cur = Drafts.get(app, hostId, session)
                Drafts.set(app, hostId, session, if (cur.isBlank()) text else "$text\n$cur")
                onFail(t("没发出去，话给你留在输入框了"))
            }
        }
    }
}
