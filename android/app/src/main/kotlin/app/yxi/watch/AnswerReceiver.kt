package app.yxi.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * 在通知按钮上选了一项。
 *
 * ⚠️ **为什么是广播不是服务**：targetSdk 34+ 之后，通知按钮上的
 * `PendingIntent.getService` 会因为「后台启动服务」限制被**静默挡掉** ——
 * 点了没反应，而且 logcat 里一条日志都没有。查这个花了我好几轮。
 *
 * ⚠️ 真正的送键在 [EventService.answer] 里，因为那儿才有活着的 SSH 连接。
 * 服务不在（比如被 force-stop 过）就什么都不做 —— **宁可没批，不能乱批**。
 */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = EventService.instance ?: return
        val pending = goAsync()
        svc.scope.launch {
            try {
                when (intent.action) {
                    EventService.ACT_ANSWER -> svc.answer(intent)   // 点了某个数字选项
                    EventService.ACT_REPLY -> svc.reply(intent)     // 打字/语音回了一句
                    EventService.ACT_MUTE -> svc.mute(intent)       // 点了静音
                }
            } finally { pending.finish() }
        }
    }
}
