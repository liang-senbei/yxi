package app.yxi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.yxi.ssh.SshSession
import kotlinx.coroutines.delay

/**
 * **「给我一条活着的连接」** —— 全项目唯一的那一份（TROUBLESHOOTING #282）。
 *
 * ⚠️⚠️ **别把 `SshSession` 抓在手里跨越等待。** 手机上断线重连是常态：
 * `rememberHostSession` 重连时会先把 `session` 置 null、再赋一个**新对象**，
 * 你手里那个当场变成死的。今天一天栽了三次 —— 临时会话、上传、发送。
 * 症状都是「**一次性判死、把用户的东西弄丢**」：话弹回输入框、传了一半算失败、
 * 授权明明成功却报失败。
 *
 * ⚠️ **规矩只对「一次性动作」是硬的**（发消息、传文件、切模型、等授权）。
 * 轮询/展示类（取不到就藏起来、下一轮自己好）不用改 —— 见 #282 里那条边界，
 * 别拿着规矩把所有 `val s = ssh` 都改一遍。
 *
 * ⚠️ **用轮询，不要 `snapshotFlow{}.first{}`**：`HostSession` 是每次重组新建的 data class，
 * 结构相等时不会发新值，`first{}` 会**永远挂着**（临时会话那轮的原话，E2E 抓到过）。
 *
 * 用法：
 * ```
 * val alive = rememberAliveSsh(ssh)
 * …
 * val s = alive(20_000) ?: return   // 等最多 20 秒；拿不到就如实失败
 * ```
 */
@Composable
fun rememberAliveSsh(ssh: SshSession?): suspend (Long) -> SshSession? {
    val latest = rememberUpdatedState(ssh)
    return remember {
        { maxWaitMs: Long ->
            val t0 = System.currentTimeMillis()
            var got: SshSession? = null
            while (true) {
                val s = latest.value
                if (s != null && s.isAlive) { got = s; break }
                if (System.currentTimeMillis() - t0 >= maxWaitMs) break
                delay(500)
            }
            got
        }
    }
}
