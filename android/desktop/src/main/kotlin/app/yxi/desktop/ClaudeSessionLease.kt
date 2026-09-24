package app.yxi.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Advisory ownership between Yxi processes; does not claim to lock external Claude clients. */
internal class ClaudeSessionLease private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) try { lock.release() } finally { channel.close() }
    }
    companion object {
        fun acquire(directory: File, runtimeHome: File, sessionId: String): ClaudeSessionLease {
            require(java.util.UUID.fromString(sessionId).toString() == sessionId)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建会话占用记录目录" }
            val identity = runtimeHome.canonicalPath + "\u0000" + sessionId
            val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            val channel = FileChannel.open(File(directory, "$key.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                checkNotNull(lock) { "此 Claude 会话正在被另一个 Yxi 连接使用" }
                return ClaudeSessionLease(channel, lock)
            } catch (e: Exception) { channel.close(); throw e }
        }
    }
}
