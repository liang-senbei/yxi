package app.yxi.desktop

import org.json.JSONObject
import java.io.File

/** Keeps the checked native process alive; a future task must use this same connection. */
internal class LocalClaudeSubscription(private val connect: suspend (LocalRuntimeInstallation, File) -> ClaudeControlClient = { runtime, directory ->
    LocalClaudeControlTransport.start(runtime, directory).let { ClaudeControlClient(it, it.requestedSessionId) }
}) {
    class Prepared(val client: ClaudeControlClient, val initialization: JSONObject, val settings: JSONObject) : AutoCloseable {
        override fun close() = client.close()
    }
    suspend fun prepare(runtime: LocalRuntimeInstallation, directory: File): Prepared {
        require(runtime.engine == "claude" && runtime.ready && directory.isAbsolute && directory.isDirectory)
        return verify(connect(runtime, directory.canonicalFile))
    }
    suspend fun resume(runtime: LocalRuntimeInstallation, record: LocalCodexTaskRecord,
        reconnect: suspend (LocalRuntimeInstallation, File, String) -> ClaudeControlClient = { selected, directory, id ->
            LocalClaudeControlTransport.start(selected, directory, resumeSessionId = id).let { ClaudeControlClient(it, it.requestedSessionId) }
        }): Prepared {
        require(record.engine == "claude" && record.provider == "official:claude" && record.hostKey.isBlank()) { "此记录不是本机官方 Claude 会话" }
        require(record.user == System.getProperty("user.name") && record.platform == System.getProperty("os.name")) { "会话所属用户或系统不匹配" }
        require(runtime.engine == "claude" && runtime.ready && File(runtime.home).canonicalPath == File(record.runtimeHome).canonicalPath) { "请选择原会话使用的 Claude 安装配置" }
        require(java.util.UUID.fromString(record.threadId).toString() == record.threadId) { "会话 ID 无效" }
        val directory = File(record.directory)
        require(directory.isAbsolute && directory.isDirectory) { "原工作目录不存在，请先恢复目录" }
        val client = reconnect(runtime, directory.canonicalFile, record.threadId)
        try {
            check(client.requestedSessionId == record.threadId) { "恢复连接身份与原会话不一致" }
            return verify(client)
        } catch (e: Exception) { client.close(); throw e }
    }
    private suspend fun verify(client: ClaudeControlClient): Prepared {
        try {
            val initialization = client.initialize()
            ClaudeSubscriptionSettings.requireControlIdentity(initialization)
            val settings = client.settings()
            ClaudeSubscriptionSettings.requireOfficialRoute(settings)
            return Prepared(client, initialization, settings)
        } catch (e: Exception) { client.close(); throw e }
    }
}
