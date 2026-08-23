package app.yxi.agent

import app.yxi.ssh.Sftp
import app.yxi.ssh.SshSession

/**
 * 附件暂存区。
 *
 * 上传到服务器的 `/root/src/tmp/<项目>/`，**按项目分目录**，
 * 消息里按「图片1 / 附件1」编号，发送时在正文前面带一段路径映射 ——
 * 这样 Claude 看到的是「图片1 在这个路径」，它自己去读，我们不用把内容塞进对话。
 *
 * ⚠️ **`/root/src` 不是 git 仓库**，往里写不会污染任何项目（PRD 附录 F）。
 * ⚠️ **保留 3 天**，之后自动清理 —— 手机随手拍的图不该在服务器上长住。
 */
object Attachments {

    const val ROOT = "/root/src/tmp"

    /** 项目目录名 = tmux 会话名去掉 `cc-` 前缀。跟看板上显示的名字一致，找起来不用猜。 */
    fun dirFor(session: String): String = ROOT + "/" + session.removePrefix("cc-").ifBlank { "misc" }

    data class Staged(val label: String, val remotePath: String, val isImage: Boolean)

    /**
     * 传一个文件上去。[name] 是原始文件名，只用来取扩展名和给人看。
     * 文件名前面加时间戳，避免同名互相覆盖。
     */
    suspend fun upload(
        sftp: Sftp, session: String, name: String, bytes: ByteArray,
        index: Int, isImage: Boolean, stamp: String,
    ): Staged {
        val dir = dirFor(session)
        sftp.mkdirs(dir)
        val safe = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").takeLast(60).ifBlank { "file" }
        val path = "$dir/$stamp-$safe"
        sftp.write(path, bytes)
        return Staged(if (isImage) "图片$index" else "附件$index", path, isImage)
    }

    /** 发送时贴在正文前面的路径映射。没有附件就返回空串。 */
    fun header(staged: List<Staged>): String =
        if (staged.isEmpty()) "" else
            staged.joinToString("\n") { "[${it.label}] ${it.remotePath}" } + "\n"

    /**
     * 清掉 3 天前的暂存文件。
     *
     * ⚠️ 这条命令会**删文件**，所以每一处都是刻意的：
     *   · 路径**写死**在常量里，不接受任何外部输入 —— 不给「参数被污染」留口子
     *   · `-xdev` 不跨文件系统
     *   · `-type f` 只删普通文件，**不跟符号链接**（`-L` 绝不加）
     *   · 删之前先把清单**追加进日志**，删错了至少查得到
     *   · `-mindepth 2` 保证只删项目目录**里面**的东西，不动 `/root/src/tmp` 本身
     */
    suspend fun sweep(ssh: SshSession) {
        val log = "$ROOT/.swept.log"
        ssh.exec(
            "test -d '$ROOT' || exit 0; " +
                "find '$ROOT' -xdev -mindepth 2 -type f -mtime +3 " +
                "-printf '%TY-%Tm-%Td %p\\n' >> '$log' 2>/dev/null; " +
                "find '$ROOT' -xdev -mindepth 2 -type f -mtime +3 -delete 2>/dev/null; " +
                "find '$ROOT' -xdev -mindepth 1 -type d -empty -delete 2>/dev/null; true"
        )
    }
}
