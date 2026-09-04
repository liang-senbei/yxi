package app.yxi.agent

import app.yxi.ssh.SshSession
import java.io.File

/**
 * 语音识别 —— **跑在你自己那台服务器上**，不经过任何云 API。
 *
 * ⚠️ **为什么不调云厂商的 ASR。** Yxi 没有后端，密钥只能塞进 APK 里 ——
 * 那等于把它公开（跟「不查 GitHub Release」是同一个理由：仓库私有、API 要 token，
 * 而把 token 塞进 APK 就是公开它）。而且这条路在防火墙后面照样能用。
 *
 * ⚠️ **微信那套拿不到**：腾讯自研、不开源、也没给第三方的独立 SDK。
 * 能买到的是腾讯云 ASR，另一个产品，要钱，而且还是上面那个密钥问题。
 *
 * 服务端是 `server/yxi-asr`（SenseVoice-Small，Apache-2.0，走 sherpa-onnx，
 * 不要 torch）。实测这台 16 核、被宿主机抢走三到五成 CPU 的机器上：
 * 5.6 秒中文 → 1.6 秒解码，整条命令来回 3~4 秒，自带标点。
 *
 * ⚠️ **装不装是可选的。** 没装就退回系统的 `RecognizerIntent`（[available] 会说）。
 * 一个连自己服务器的工具，不该强迫用户先在服务器上装 360MB 模型才能用麦克风。
 */
object Voice {

    /**
     * ⚠️ **非交互的 `ssh exec` 拿不到登录 shell 的 PATH。**
     * `yxi-asr` 装在 `~/.local/bin`（pip install --user 的默认位置），
     * 而 `ssh host "command -v yxi-asr"` 的 PATH 里**没有**这一条 ——
     * 于是 App 一直判定「这台服务器没有语音识别」，默默退回系统识别；
     * 而用户的手机 GMS 是关的，系统识别根本不存在 = 麦克风点了没反应。
     * 服务器上明明装好了，界面上却像没装 —— 这种「装了等于没装」最难查。
     */
    private const val PATH_FIX = "export PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:\$PATH\";"

    /**
     * 录好的音频先落这儿。
     *
     * ⚠️ 用 [Attachments.ROOT] 下的子目录，**不写 `$HOME`** —— SFTP 没有 shell，
     * `$HOME` 不会展开，会真去建一个名叫 `$HOME` 的目录。
     * 跟附件同一个根，因为那块地方本来就是给「手机传上来的临时东西」用的、已知可写。
     */
    private val DIR = Attachments.ROOT + "/.voice"

    /**
     * 这台机器上有没有 `yxi-asr`。
     *
     * ⚠️ **一次连接只问一次**（调用方缓存）。它是一条 exec，本身不贵，
     * 但每次点麦克风都问一遍就是每次多一个来回的延迟。
     */
    suspend fun available(ssh: SshSession): Boolean =
        // ⚠️ 用 `--warm` 不用 `--check`：**顺手把守护预热起来**。
        // 守护闲置 10 分钟会自己退，冷启动要 6~10 秒装载模型 —— 那笔时间
        // 摊在「用户按住说完话之后」是最难受的位置（他已经说完了，在等）。
        // `--warm` 只把守护 fork 出去就立刻返回（实测 0.15 秒），
        // 等用户真说完话，模型早装载好了：实测 9.0 秒 → 3.9 秒。
        app.yxi.ssh.catching { ssh.exec("$PATH_FIX command -v yxi-asr >/dev/null && yxi-asr --warm 2>/dev/null") }
            .getOrDefault("").trim() == "ok"

    /**
     * 把录好的音频传上去、识别、**删掉**，返回识别出来的文字。
     *
     * ⚠️ **传完就删。** 语音是用户说的话，留在服务器上没有任何用处，
     * 而且它跟附件不一样 —— 附件那边是有意留 3 天给 agent 读的。
     *
     * @return 识别出的文字；失败返回 null，[why] 里是原因。
     */
    suspend fun transcribe(ssh: SshSession, local: File, why: (String) -> Unit): String? {
        val remote = "$DIR/${System.currentTimeMillis()}-v.m4a"
        val sftp = app.yxi.ssh.catching { ssh.openSftp() }.getOrNull()
        if (sftp == null) { why(app.yxi.ui.t("开不了 SFTP 通道")); return null }
        try {
            app.yxi.ssh.catching { sftp.mkdirs(DIR) }
            val put = app.yxi.ssh.catching { sftp.write(remote, local.readBytes()) }
            if (put.isFailure) {
                why(app.yxi.ui.t("传不上去：%s").format(app.yxi.ssh.Sftp.explain(put.exceptionOrNull()!!)))
                return null
            }
        } finally { runCatching { sftp.close() } }

        // ⚠️ **识别和删除写在一条命令里。** 分两条的话，中间断线就会在服务器上
        // 留下一个用户说过的话的录音 —— 那是不该留的东西。
        val q = remote.replace("'", "'\\''")
        val out = app.yxi.ssh.catching {
            ssh.exec("$PATH_FIX yxi-asr '$q' 2>&1; rm -f '$q'")
        }.getOrDefault("").trim()

        if (out.isEmpty()) { why(app.yxi.ui.t("没识别出东西")); return null }
        // yxi-asr 失败时把原因打在 stderr（上面 2>&1 合并了），特征是不像人话的那几句
        if (out.startsWith("找不到") || out.contains("没装") || out.contains("守护起不来")) {
            why(out.lineSequence().first().take(60)); return null
        }
        return out.lineSequence().filter { it.isNotBlank() }.joinToString(" ").trim()
    }
}
