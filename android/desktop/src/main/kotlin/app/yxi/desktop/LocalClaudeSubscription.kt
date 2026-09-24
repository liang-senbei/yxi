package app.yxi.desktop

import org.json.JSONObject
import java.io.File

/** Keeps the checked native process alive; a future task must use this same connection. */
internal class LocalClaudeSubscription(private val connect: suspend (LocalRuntimeInstallation, File) -> ClaudeControlClient = { runtime, directory ->
    ClaudeControlClient(LocalClaudeControlTransport.start(runtime, directory))
}) {
    class Prepared(val client: ClaudeControlClient, val initialization: JSONObject, val settings: JSONObject) : AutoCloseable {
        override fun close() = client.close()
    }
    suspend fun prepare(runtime: LocalRuntimeInstallation, directory: File): Prepared {
        require(runtime.engine == "claude" && runtime.ready && directory.isAbsolute && directory.isDirectory)
        val client = connect(runtime, directory.canonicalFile)
        try {
            val initialization = client.initialize()
            ClaudeSubscriptionSettings.requireControlIdentity(initialization)
            val settings = client.settings()
            ClaudeSubscriptionSettings.requireOfficialRoute(settings)
            return Prepared(client, initialization, settings)
        } catch (e: Exception) { client.close(); throw e }
    }
}
