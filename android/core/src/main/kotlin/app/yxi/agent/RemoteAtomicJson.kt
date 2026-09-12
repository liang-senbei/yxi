package app.yxi.agent

import app.yxi.ssh.SshSession
import app.yxi.ssh.Shell
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Read/compare/replace protocol. Credentials travel through SFTP, never in process arguments. */
object RemoteAtomicJson {
    data class Snapshot(val text: String?, val revision: String)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun read(ssh: SshSession, path: String): Snapshot {
        val sftp = ssh.openSftp()
        try {
            val bytes = try { sftp.read(path, 2 * 1024 * 1024 + 1) }
            catch (e: SftpException) { if (e.id == 2) return Snapshot(null, "missing") else throw e }
            require(bytes.size <= 2 * 1024 * 1024) { "配置过大，未覆盖" }
            return Snapshot(bytes.toString(Charsets.UTF_8), hash(bytes))
        } finally { sftp.close() }
    }

    suspend fun write(ssh: SshSession, path: String, text: String, expected: String): String? {
        val parent = path.substringBeforeLast('/')
        val temp = "$parent/.yxi-upload-${UUID.randomUUID()}"
        try {
            check(ssh.exec("(umask 077; mkdir -p " + Shell.q(parent) + "; set -C; : > " + Shell.q(temp) + ") && printf YXI_READY").trim() == "YXI_READY") { "无法创建受保护的临时配置" }
            val bytes = text.toByteArray()
            require(bytes.size <= 2 * 1024 * 1024) { "配置过大，未覆盖" }
            val sftp = ssh.openSftp()
            try { sftp.write(temp, bytes) } finally { sftp.close() }
            val command = "python3 -c " + Shell.q(SCRIPT) + " " + listOf(path, temp, expected, hash(bytes)).joinToString(" ") { Shell.q(it) }
            val raw = ssh.exec(command)
            val response = runCatching { JSONObject(raw.trim()) }.getOrElse { return "未收到配置提交确认，请刷新核对；不会自动重试" }
            return when (response.optString("status")) {
                "saved" -> null
                "conflict" -> "配置已被其他人修改，本次未覆盖，请刷新后重试"
                else -> "配置未提交：" + response.optString("error", "未知错误")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return "配置未提交：${e.message}" }
        finally { withContext(NonCancellable) { runCatching { ssh.exec("rm -f -- " + Shell.q(temp)) } } }
    }

    // A stable sidecar lock protects cooperating writers across atomic renames.
    private val SCRIPT = """
import os,sys,json,hashlib,fcntl,tempfile
p,t,expected,wanted=sys.argv[1:]
def revision():
    if os.path.islink(p): raise ValueError('Configuration symlinks are not writable here')
    if not os.path.exists(p): return 'missing',None
    with open(p,'rb') as f: data=f.read(2097153)
    if len(data)>2097152: raise ValueError('Configuration too large')
    json.loads(data)
    return hashlib.sha256(data).hexdigest(),data
try:
    fd=os.open(p+'.yxi-lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        current,old=revision()
        if current!=expected:
            print(json.dumps({'status':'conflict'})); sys.exit(0)
        with open(t,'rb') as f: new=f.read(2097153)
        if len(new)>2097152 or hashlib.sha256(new).hexdigest()!=wanted: raise ValueError('Upload verification failed')
        json.loads(new)
        os.chmod(t,0o600)
        if old is not None:
            directory=os.path.join(os.environ['HOME'],'.yxi','backups')
            os.makedirs(directory,mode=0o700,exist_ok=True); os.chmod(directory,0o700)
            backup_fd,backup=tempfile.mkstemp(prefix='route-catalog-',dir=directory)
            with os.fdopen(backup_fd,'wb') as f: f.write(old); f.flush(); os.fsync(f.fileno())
        with open(t,'rb') as f: os.fsync(f.fileno())
        if revision()[0]!=expected:
            print(json.dumps({'status':'conflict'})); sys.exit(0)
        os.replace(t,p)
        print(json.dumps({'status':'saved'}))
except Exception as e: print(json.dumps({'status':'error','error':str(e)}))
""".trimIndent()
}
