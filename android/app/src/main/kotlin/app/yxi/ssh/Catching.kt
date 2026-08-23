package app.yxi.ssh

import kotlinx.coroutines.CancellationException

/**
 * `runCatching`，但**取消原样抛回去**。
 *
 * ⚠️ `kotlin.runCatching` 捕获的是 `Throwable`，**包括协程的取消信号**。
 * 在挂起函数里用它包一段网络操作、失败时写进界面状态，就会出现这种事：
 * 用户切个页面 → effect 被取消 → 取消异常被当成失败 →
 * 界面上留下一句「连不上：JobCancellationException」，**而且永远不消失**
 * （状态是记住的，没人再去清）。
 *
 * 这个坑在本项目里前后踩了**五次**，每次表现都不一样：
 * 「终端起不来」「连不上」「去不了这个目录」。见 TROUBLESHOOTING #78。
 *
 * 规矩：**凡是「失败要显示给用户」的地方，一律用这个，不用 runCatching。**
 */
inline fun <T> catching(block: () -> T): Result<T> {
    val r = runCatching(block)
    (r.exceptionOrNull() as? CancellationException)?.let { throw it }
    return r
}
