package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.yxi.agent.Attachments
import app.yxi.agent.Uploader
import app.yxi.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面版的附件暂存（PRD P0-10，管线在 core 的 [Attachments] / [Uploader]，跟手机端同一条路）：
 * 选文件 / 粘贴图片 → 传到服务器的 `/root/src/tmp/<项目>/` → 发送时正文前面带「[图片1] 路径」的映射。
 *
 * 桌面比手机多的是**粘贴**：截图直接 Ctrl+V 进对话，跟 Codex / Claude Desktop 一个手感。
 */

/** 一条正在暂存的附件。状态是 Compose 状态，chips 直接读它自己会重组。 */
class DraftAttach(val name: String, val isImage: Boolean, val size: Long, val stamp: String, val open: () -> InputStream) {
    var state by mutableStateOf<DraftState>(DraftState.Waiting)
    val cancelled = AtomicBoolean(false)

    /** 传完了显示发送时用的编号名（图片1.png —— `renumber` 在发送时才定编号，所以以 Done 里的为准）；传着时显示原始名。 */
    val display: String
        get() = (state as? DraftState.Done)?.staged?.display ?: name.substringAfterLast('/')
}

sealed interface DraftState {
    data object Waiting : DraftState
    data class Uploading(val done: Long, val total: Long) : DraftState {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).coerceAtMost(99L).toInt()
    }
    data class Done(val staged: Attachments.Staged) : DraftState
    data class Failed(val msg: String) : DraftState
}

object Attach {
    private val seq = AtomicLong(0)

    /** 时间戳 + 序号：同一秒传两个同名文件也不互相覆盖（remotePath 靠它区分）。 */
    fun nextStamp(): String = "%d-%02d".format(System.currentTimeMillis() / 1000, seq.incrementAndGet() % 100)

    // ── 来源：文件选择器 / 剪贴板 ──

    /** AWT 的模态文件对话框：isVisible = true 会自己泵事件，卡在 EDT 上是它的正常姿势。 */
    fun pickFiles(): List<File> {
        val parent = java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull { it.isFocused } ?: java.awt.Frame()
        val fd = java.awt.FileDialog(parent, "选择要发送的文件", java.awt.FileDialog.LOAD)
        fd.setMultipleMode(true)
        runCatching { fd.isVisible = true }
        return fd.files.toList()
    }

    /** 剪贴板里有图就拿出来（没有返回 null —— 文本粘贴仍归输入框）。 */
    fun clipboardImage(): java.awt.image.BufferedImage? = runCatching {
        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        val contents = cb.getContents(null) ?: return null
        if (!cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) return null
        contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.image.BufferedImage
    }.getOrNull()

    /** 便宜的预检：剪贴板里**有没有**图（不解码——完整解码很贵，别放在按键线程上做）。 */
    fun hasClipboardImage(): Boolean = runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)
    }.getOrDefault(false)

    /** 贴图进行中：挡住按键重复把同一张截图贴成好几张。 */
    val pasteBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun pngBytes(img: java.awt.image.BufferedImage): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    // ── 暂存 ──

    /** 从本地文件建一条（上传在 [launchUpload]）。 */
    fun fromFile(f: File): DraftAttach =
        DraftAttach(f.name, isImage(f.name), f.length(), nextStamp()) { FileInputStream(f) }

    /** 从粘贴的图片建一条：字节留在内存里，重试时开新流（Uploader 每次尝试都会重新调 open）。 */
    fun fromPastedImage(bytes: ByteArray): DraftAttach {
        val n = "粘贴图片-" + java.time.LocalTime.now().withNano(0).toString().replace(":", "") + ".png"
        return DraftAttach(n, true, bytes.size.toLong(), nextStamp()) { ByteArrayInputStream(bytes) }
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    /**
     * 跑一条上传（手机端 Uploader 的全部 retry / 续传 / 卡死看门狗都在 core 里，直接用）。
     * 失败不清条目：留在 chips 上给人看原因，✕ 手动删。
     */
    fun launchUpload(c: Conn, sessionName: String, a: DraftAttach, scope: CoroutineScope) {
        scope.launch {
            a.state = DraftState.Uploading(0, a.size)
            val r = Uploader.upload(
                aliveSsh = { waitMs -> waitAlive(c, waitMs) },
                open = a.open, total = a.size,
                sessionName = sessionName, name = a.name, index = 0, isImage = a.isImage, stamp = a.stamp,
                cancelled = { a.cancelled.get() },
                progress = { done, total -> a.state = DraftState.Uploading(done, total) },
            )
            a.state = r.fold(
                { DraftState.Done(it) },
                { e -> DraftState.Failed(if (a.cancelled.get()) "已取消" else Uploader.explain(e)) },
            )
        }
    }

    /** 给 Uploader 的 aliveSsh：最多等 [waitMs] 让连接回来；等不到给 null（那一轮记失败，下一轮再试）。 */
    private suspend fun waitAlive(c: Conn, waitMs: Long): SshSession? {
        val t0 = System.currentTimeMillis()
        while (!c.ssh.isConnected) {
            if (System.currentTimeMillis() - t0 >= waitMs) return null
            delay(500)
        }
        return c.ssh
    }

    /** 发送前从暂存列表里摘出传完的（保持列表顺序），重排编号后拼出正文头。 */
    fun headerOf(staged: List<DraftAttach>): String =
        Attachments.header(Attachments.renumber(staged.mapNotNull { (it.state as? DraftState.Done)?.staged }))

    /** 发送成功后，把已经编进映射的那几条从暂存里撤掉（失败的留着，让人看见原因）。 */
    fun removeDone(staged: MutableList<DraftAttach>) {
        staged.removeAll { it.state is DraftState.Done || (it.state is DraftState.Failed && it.cancelled.get()) }
    }

    /** 暂存区 3 天自动清理（core 的 sweep，命令写死不接外部输入）。接上会话就跑一次。 */
    suspend fun sweep(c: Conn) {
        if (c.ssh.isConnected) runCatching { Attachments.sweep(c.ssh) }
    }
}

/** 对话里历史消息渲染的图片引用缓存（进程级，路径 → 已解码的位图）。 */
object RefImages {
    private const val MAX = 32
    private val map = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()
    fun get(path: String): androidx.compose.ui.graphics.ImageBitmap? = map[path]
    fun put(path: String, img: androidx.compose.ui.graphics.ImageBitmap) {
        if (map.size >= MAX) runCatching { map.keys.toList().let { map.remove(it.first()) } }   // 简单挤掉最早的一个，缓存而已不必讲究
        map[path] = img
    }
}
