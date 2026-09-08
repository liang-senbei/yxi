package app.yxi.ssh

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * 主机指纹校验的缝：core 里的 [SshSession] 只认这个接口；Android 的 KnownHosts（存在 HostStore 里）实现它，桌面版另存一份文件。
 * SSH 抵御中间人的唯一防线（PRD §8 第 2 条），所以不能因为搬模块就省掉。
 */
interface HostKeys : HostKeyRepository {
    fun userInfo(): UserInfo
    /** 指纹变了（可能是中间人）——上层看到它就停止重试 */
    val changedDetected: Boolean
}
