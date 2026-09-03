package app.yxi.agent

import app.yxi.ui.t

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

    /**
     * @param localUri 手机上那份的 content Uri（字符串形式）。**只为预览用。**
     * 预览读本地不读远端：文件刚从这台手机传上去的，再从服务器拉回来纯属白跑一趟，
     * 而且慢。⚠️ 这个授权只在当前 Activity 活着的时候有效，所以 [Staged] 也只该活这么久
     * （它本来就是 `remember` 的，发出去就清）。
     */
    data class Staged(
        val label: String,
        val remotePath: String,
        val isImage: Boolean,
        val localUri: String? = null,
    )

    /**
     * 传一个文件上去。[name] 是原始文件名，只用来取扩展名和给人看。
     * 文件名前面加时间戳，避免同名互相覆盖。
     */
    suspend fun upload(
        sftp: Sftp, session: String, name: String, bytes: ByteArray,
        index: Int, isImage: Boolean, stamp: String,
        /** 进度 `(已传, 总数)`，返回 false 中止（见 [Sftp.write]） */
        progress: ((Long, Long) -> Boolean)? = null,
    ): Staged {
        val dir = dirFor(session)
        sftp.mkdirs(dir)
        val path = remotePath(session, name, stamp)
        sftp.write(path, bytes, progress)
        return Staged(if (isImage) t("图片%d").format(index) else t("附件%d").format(index), path, isImage)
    }

    /** 上传落在服务器上的路径。单独拿出来是为了**取消时能删掉传了一半的那个**。 */
    fun remotePath(session: String, name: String, stamp: String): String {
        val safe = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").takeLast(60).ifBlank { "file" }
        return dirFor(session) + "/" + stamp + "-" + safe
    }

    /**
     * 重新编号。
     *
     * ⚠️ **必须在加进列表之后统一编，不能在上传前各算各的。**
     * 原来是 `staged.count { … } + 1` —— 而 `staged` 只在**上传成功后**才更新，
     * 于是第一张还在传的时候点第二张，两张算出来的都是 1，
     * 双双叫「图片1」。而 [header] 是靠标签把路径喂给 Claude 的，
     * 两个同名标签 = 它拿到两条自相矛盾的映射。
     *
     * 顺带把「传完的顺序不等于点的顺序」也抹平了：编号只跟列表里的位置走。
     */
    fun renumber(list: List<Staged>): List<Staged> {
        var img = 0
        var file = 0
        return list.map {
            if (it.isImage) it.copy(label = t("图片%d").format(++img))
            else it.copy(label = t("附件%d").format(++file))
        }
    }

    /**
     * 用户消息里被 [header] 贴上去的那一条附件引用。
     *
     * @param label 界面上那个名字（`图片1` / `附件2`）
     * @param path 远端绝对路径
     * @param name 文件名（拉不到图时显示它，比整条路径有用）
     */
    data class Ref(val label: String, val path: String, val isImage: Boolean) {
        val name: String get() = path.substringAfterLast('/')
    }

    /**
     * 把 [header] 贴上去的那几行从正文里摘出来。
     *
     * ⚠️ **整段里任何一行都认，不只认开头。** 原来只认开头连续的几行，理由是怕吃掉正文里
     * 长得像的句子 —— 但真实情况是：用户趁 Claude 忙的时候连发两条，第二条带图，
     * Claude Code 把排队的两条**合成一条**送进去，附件头就落在了两段正文中间，
     * 于是图片渲染不出来、路径原样躺在气泡里（用户截图报的）。
     * 正则本身已经很严（`[标签] /绝对路径`，整行只有这个），正文里撞上的概率可以忽略。
     *
     * ⚠️ 图片还是附件**按扩展名判**，不按标签判：标签是本地化的（`图片1`/`Image 1`），
     * 换个语言看历史消息就全认不出来了。
     *
     * @return (附件们, 剩下的正文)
     */
    fun parseRefs(text: String): Pair<List<Ref>, String> {
        val re = Regex("""^\[([^\]]+)]\s+(/\S+)\s*$""")
        val refs = ArrayList<Ref>()
        val body = ArrayList<String>()
        for (line in text.lines()) {
            val m = re.find(line)
            if (m == null) { body += line; continue }
            val path = m.groupValues[2]
            val ext = path.substringAfterLast('.', "").lowercase()
            refs += Ref(m.groupValues[1], path, ext in IMAGE_EXT)
        }
        if (refs.isEmpty()) return emptyList<Ref>() to text
        return refs to body.joinToString("\n").trim()
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

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
