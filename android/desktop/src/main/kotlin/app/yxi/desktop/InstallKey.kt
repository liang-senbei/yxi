package app.yxi.desktop

import app.yxi.ssh.HostConfig
import app.yxi.ssh.SshSession
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一键装公钥（PRD P0-14，= `ssh-copy-id`）：用密码连一次，把 Yxi 的公钥追加进目标机的
 * `~/.ssh/authorized_keys`（core 的 [SshSession.installPublicKey]，跟手机端同一条命令），之后切密钥免密登录。
 *
 * 密钥是**桌面版自己的**一把（`Store.dir/id_ed25519`），跟用户 shell 里的 `~/.ssh` 分开 ——
 * 机器上没有密钥的新用户装完就能用，有的用户也不必把自己的主密钥交给这个 App。
 */

object DesktopKey {
    private const val COMMENT = "yxi@desktop"
    val privFile: File get() = File(Store.dir, "id_ed25519")
    private val pubFile: File get() = File(Store.dir, "id_ed25519.pub")

    /**
     * 有就直接用；没有现生成一把 ed25519（本机的 JCA 生成不了就退 RSA 4096）。
     * 返回 (私钥文件, 公钥行)。@Synchronized：并发调用会各生成一把、priv/pub 交错写坏。
     */
    @Synchronized
    fun ensure(): Pair<File, String> {
        if (privFile.isFile && pubFile.isFile) {
            fixPerm()
            return privFile to pubFile.readText().trim()
        }
        var rsa = false
        val kp = runCatching { KeyPair.genKeyPair(JSch(), KeyPair.ED25519) }
            .getOrElse { rsa = true; KeyPair.genKeyPair(JSch(), KeyPair.RSA, 4096) }
        kp.writePrivateKey(privFile.outputStream())
        // openSSH 格式的公钥行：`<算法> <base64(blob)> <备注>`。installPublicKey 按整行追加
        val type = if (rsa) "ssh-rsa" else "ssh-ed25519"
        val line = "$type " + Base64.getEncoder().encodeToString(kp.getPublicKeyBlob()) + " " + COMMENT
        pubFile.writeText(line)
        fixPerm()
        return privFile to line
    }

    /** 私钥权限收到只有本人可读（Windows 上是 no-op）。⚠️ 早退路径也要走：1.1.0 之前落盘的可能是 644。 */
    private fun fixPerm() {
        runCatching { privFile.setReadable(false, false); privFile.setReadable(true, true) }
    }
}

/**
 * 装公钥本体：密码连一次 → append 公钥 → **回读验证** → 断开。
 * 成功返回 null（私钥路径在 [DesktopKey.privFile]，调用方把它存进 host.keyPath），失败返回给人看的一句话。
 * [hostKeys] 用侧栏那一份：首次连会弹指纹核对，核对过了才写 —— 公钥绝不能写进一台没核对过指纹的机器。
 */
suspend fun installPublicKey(h: Host, password: String, hostKeys: FileHostKeys): String? {
    // ⚠️ ensure()/toConfig() 都是文件 IO（私钥被外部删了/磁盘满会抛），必须圈进失败路径，
    //    不能让异常从调用方的 scope.launch 裸逃——那会把整个桌面进程带崩（交叉审查抓的 P1）。
    val cfg = try {
        h.toConfig().copy(auth = HostConfig.Auth.Password(password))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        return "读主机配置失败：${e.message}"
    }
    val (_, line) = try {
        withContext(Dispatchers.IO) { DesktopKey.ensure() }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        return "生成/读取 Yxi 密钥失败：${e.message}"
    }
    val s = try {
        SshSession(cfg, hostKeys)
    } catch (e: Exception) {
        return "起不了连接：${e.message}"
    }
    return try {
        s.connect()
        s.installPublicKey(line)
        // ⚠️ core 的 exec 把连接类异常吞成空串、installPublicKey 从不抛——append 没执行它也「成功」。
        //    必须回读验证（line 是 base64+固定注释，无单引号，可安全内插）。交叉审查抓的 P1。
        val check = s.exec("grep -qxF '$line' ~/.ssh/authorized_keys && echo YXI-INSTALLED")
        if ("YXI-INSTALLED" in check) null
        else "公钥没写进去（连接中途断了？）。主机没动，密码登录不受影响，再试一次。"
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val m = e.message.orEmpty()
        when {
            "Auth" in m -> "认证被拒：密码不对。"
            else -> "装不上：${m.ifBlank { e.toString() }}"
        }
    } finally {
        runCatching { s.disconnect() }
    }
}
