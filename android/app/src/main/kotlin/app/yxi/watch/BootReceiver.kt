package app.yxi.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.yxi.ssh.HostStore

/** 开机后自动恢复盯梢 —— 否则手机重启一次就得手动打开 App 才会再响。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val any = runCatching { HostStore(ctx).hosts.value.any { it.watch } }.getOrDefault(false)
        if (any) EventService.sync(ctx, true)
    }
}
