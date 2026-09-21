package app.yxi.agent

import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * **OpenCode 配置适配（最小独立模块）** —— 读取那台机器的用户级全局配置
 * （`${XDG_CONFIG_HOME:-$HOME/.config}/opencode/opencode.json[c]`，官方 Global.Path.config = xdg_config/opencode）
 * 里的 provider/model 列表，并把「选定 provider/model」写进顶层 `model` 字段。
 * **不改 Lines / UI 主文件**，接线由 root 负责。
 *
 * 官方口径（2026-09 源码核对 sst/opencode dev 线）：
 *  - 配置是 JSON **或 JSONC**：官方 parse 用 jsonc-parser，`allowTrailingComma: true`，注释合法；
 *    schema 校验 `onExcessProperty: "ignore"`，未知字段官方自己也不删。
 *  - `model` = `providerID/modelID`，官方 [ModelV2.parse] 按**首个 `/`** 切分、modelID 可再含 `/`。
 *  - `provider` 是 Record：id → { name?, npm?, options?: { apiKey?, baseURL?, … }, models?: Record }。
 *
 * JSONC 红线：
 *  - 读取：内存里剥注释/尾逗号再解析，**只为展示；剥完的文本绝不写回**。
 *  - 写入是**文本手术**，从不重建整文件：只把顶层 `model` 的值 span 原位替换（键缺失则按文件自身的
 *    换行/缩进风格在根 `{` 后插入）；注释、未知字段、键序、缩进一字不动；顶层 `model` 出现多次 → 拒绝；
 *    现有内容不是合法 JSONC → 拒绝盲改。
 *  - scope 边界（本迭代）：只写顶层 `model` 选择。provider 的 apiKey/baseURL 等**不支持写**，
 *    也不写 small_model/权限/MCP 等任何其它键；不自动切换生产配置（调用方 UI 显式确认后才落盘）。
 *
 * 安全红线（协议同 [GeminiRouteConfig] / [RemoteAtomicJson]）：
 *  - read/compare/replace：expected-content hash 防并发覆盖；文件 0600、临时文件 + `os.replace` 原子替换；
 *    **备份进 `~/.yxi/backups/`**，前缀 `opencode-route-`，绝不放原文件旁。
 *  - `opencode.json` 与 `opencode.jsonc` 并存时**拒绝写入**（官方两份都会合并，写哪份都算替用户做主）；
 *    读取按官方查找顺序以 `.jsonc` 为准并如实上报 [Status.bothExist]。
 *  - 回读（[status]）apiKey 只出脱敏 fingerprint，原始文件经 SFTP 读入应用内存；状态对象不包含密钥原文。
 *  - 测试走临时目录 fake：不写用户真实配置、不发任何模型请求。
 */
object OpenCodeRouteConfig {
    const val FILE_JSONC = "opencode.jsonc"
    const val FILE_JSON = "opencode.json"

    /**
     * 顶层 model 选择：`provider/model`（官方按首个 `/` 切分，modelID 允许再含 `/`，如 openrouter 的三段 id）。
     * 两段都禁空白、引号、反斜杠、控制符；providerID 1..100，modelID 1..200。
     */
    val MODEL = Regex("^[A-Za-z0-9._~:+-]{1,100}/[A-Za-z0-9._~:/+-]{1,200}$")

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val BACKUP_DIR = "~/.yxi/backups"

    /** 一条待落的配置：显式写入选定的 provider/model。 */
    data class Patch(val model: String)

    data class Provider(
        val id: String,
        val name: String?,
        val npm: String?,
        /** provider.options.baseURL（官方字段就是 camelCase）。 */
        val baseURL: String?,
        /** options.apiKey 的脱敏指纹；原始文件经 SFTP 读入应用内存；状态对象不包含密钥原文。 */
        val apiKeyFingerprint: String?,
        /** models 映射的键，字典序（展示稳定；org.json 的键序无保证）。 */
        val modelIds: List<String>,
    )

    data class Status(
        /** 配置目录（调用方传入，如 `$HOME/.config/opencode`）。 */
        val dir: String,
        /** 本模块读取的配置文件名；null = 两份都不存在。 */
        val fileName: String?,
        /** 顶层 `model`（用户当前的 provider/model 选择）。 */
        val selectedModel: String?,
        val providers: List<Provider>,
        /** 顶层除 model/provider 外的键（字典序；证明未知字段被读到且本模块不会碰它们）。 */
        val otherTopLevelKeys: List<String>,
        /** 配置解析失败原因（JSONC 语法 / 根非对象 / model 或 provider 类型不对）；非 null 时拒绝写入。 */
        val parseError: String?,
        /** opencode.json 与 opencode.jsonc 并存：官方两份都会合并，本模块拒绝写入。 */
        val bothExist: Boolean = false,
        val revision: String = "",
    )

    // ---------- 纯函数层（本地可单测，不碰 SSH） ----------

    fun validateModel(model: String) {
        require(MODEL.matches(model)) {
            "model 需为 provider/model（官方按首个 / 切分，modelID 可再含 /；禁空白、引号、控制符）：${model.take(80)}"
        }
    }

    /** 脱敏 fingerprint：SHA-256 前 10 位 + 长度。稳定、可对账、不含原文（口径同 [GeminiRouteConfig]）。 */
    fun fingerprint(secret: String?): String? {
        if (secret.isNullOrBlank()) return null
        return "sha256:" + RemoteAtomicJson.hash(secret.toByteArray(Charsets.UTF_8)).take(10) + "#" + secret.length
    }

    internal data class Parsed(
        val model: String?,
        val providers: List<Provider>,
        val otherTopLevelKeys: List<String>,
    )

    /**
     * 读取路径：剥注释/尾逗号（仅内存，结果不外传）→ 严格解析 → 抽 provider/model。
     * 解析不了抛 [IllegalArgumentException]（status 把它落进 [Status.parseError]，apply 拒绝盲改）。
     */
    internal fun parseConfig(text: String): Parsed {
        val root = runCatching { JSONObject(stripJsonc(text)) }
            .getOrElse { throw IllegalArgumentException("配置不是合法 JSONC，请检查语法后重试") }
        val model = when (val v = root.opt("model")) {
            null, JSONObject.NULL -> null
            is String -> v.ifBlank { null }
            else -> throw IllegalArgumentException("顶层 model 不是字符串，未改动")
        }
        when (root.opt("provider")) {
            null, JSONObject.NULL, is JSONObject -> {}
            else -> throw IllegalArgumentException("顶层 provider 不是对象，未改动")
        }
        val providers = (root.optJSONObject("provider"))?.let { p ->
            p.keys().asSequence().sorted().map { id ->
                val v = p.optJSONObject(id)
                if (v == null) {
                    Provider(id, null, null, null, null, emptyList())
                } else {
                    val opts = v.optJSONObject("options")
                    Provider(
                        id = id,
                        name = v.optString("name").ifBlank { null },
                        npm = v.optString("npm").ifBlank { null },
                        baseURL = opts?.optString("baseURL")?.ifBlank { null },
                        apiKeyFingerprint = fingerprint(opts?.optString("apiKey")),
                        modelIds = v.optJSONObject("models")?.keys()?.asSequence()?.sorted()?.toList().orEmpty(),
                    )
                }
            }.toList()
        }.orEmpty()
        val others = root.keys().asSequence().filter { it != "model" && it != "provider" }.sorted().toList()
        return Parsed(model, providers, others)
    }

    /**
     * 只把顶层 `model` 的值原位替换（键缺失则在根 `{` 后按文件自身风格插入）；其余字节不动。
     * text 为空 = 新建最小配置（官方文档示例风格，2 空格缩进）。
     * 先 [scanTopLevel]（结构定位 + 重复键给准确中文报错），再 [parseConfig]
     * （org.json 严格解析 + 类型检查）——两道闸都过才动手。
     */
    fun patchModel(text: String?, model: String): String {
        validateModel(model)
        if (text.isNullOrBlank()) return "{\n  \"model\": \"$model\"\n}\n"
        val sp = scanTopLevel(text)
        parseConfig(text) // 拒绝在解析不了的配置上盲改
        val span = sp.modelSpan
        if (span != null) return text.replaceRange(span.first, span.second, "\"$model\"")
        return insertNewKey(text, sp.rootOpen, model)
    }

    /** 键缺失：按文件自身的换行/缩进风格在根 `{` 后插入 `"model": "x"`（已有其它键时补逗号）。 */
    private fun insertNewKey(text: String, rootOpen: Int, model: String): String {
        val firstStruct = skipWsComments(text, rootOpen + 1)
        require(firstStruct < text.length) { "对象未闭合" }
        val pretty = text.indexOf('\n', rootOpen + 1) in (rootOpen + 1) until firstStruct
        val pair = "\"model\": \"$model\""
        return if (text[firstStruct] == '}') {
            if (pretty) text.replaceRange(rootOpen + 1, rootOpen + 1, "\n  $pair")
            else text.replaceRange(rootOpen + 1, rootOpen + 1, pair)
        } else if (pretty) {
            val lineStart = text.lastIndexOf('\n', firstStruct - 1) + 1
            val indent = text.substring(lineStart, firstStruct)
            text.replaceRange(rootOpen + 1, rootOpen + 1, "\n$indent$pair,")
        } else {
            text.replaceRange(rootOpen + 1, rootOpen + 1, "$pair,")
        }
    }

    internal data class Spans(val rootOpen: Int, val modelSpan: Pair<Int, Int>?)

    /**
     * 定位根 `{` 与顶层 `model` 的值 span（起 = 值首字符，止 = 值末字符之后；尾随空白/注释不在 span 内）。
     * 只认直接挂在根对象下的键；嵌套对象里的同名 `model` 一律不碰。重复键拒绝（JSON 语义取后者，手术取「不动」）。
     */
    internal fun scanTopLevel(text: String): Spans {
        val rootOpen = skipWsComments(text, 0)
        require(rootOpen < text.length) { "配置为空" }
        require(text[rootOpen] == '{') { "配置根必须是对象" }
        var i = rootOpen + 1
        var span: Pair<Int, Int>? = null
        var count = 0
        while (true) {
            i = skipWsComments(text, i)
            require(i < text.length) { "对象未闭合" }
            if (text[i] == '}') break
            if (text[i] == ',') { i++; continue } // 尾逗号/多余逗号容忍（官方 allowTrailingComma 同款）
            require(text[i] == '"') { "对象键必须是字符串" }
            val keyStart = i
            i = skipString(text, i)
            val key = unescapeKey(text.substring(keyStart + 1, i - 1))
            i = skipWsComments(text, i)
            require(i < text.length && text[i] == ':') { "键后缺少冒号" }
            val vStart = skipWsComments(text, i + 1)
            val vEnd = skipValue(text, vStart)
            i = vEnd
            if (key == "model") { count++; span = vStart to vEnd }
        }
        require(count <= 1) { "顶层 model 出现 $count 次，请先手动整理为一份再使用本功能" }
        return Spans(rootOpen, span)
    }

    /** 跳过空白与 JSONC 注释；块注释未闭合抛错（jsonc-parser 同样视为语法错误）。 */
    private fun skipWsComments(s: String, from: Int): Int {
        var k = from
        while (k < s.length) {
            when {
                s[k].isWhitespace() -> k++
                s[k] == '/' && k + 1 < s.length && s[k + 1] == '/' -> { k += 2; while (k < s.length && s[k] != '\n') k++ }
                s[k] == '/' && k + 1 < s.length && s[k + 1] == '*' -> {
                    val e = s.indexOf("*/", k + 2)
                    require(e >= 0) { "块注释未闭合" }
                    k = e + 2
                }
                else -> return k
            }
        }
        return k
    }

    /** s[k] 应为 `"`；返回闭引号之后的位置。 */
    private fun skipString(s: String, k: Int): Int {
        require(s[k] == '"') { "应为字符串" }
        var i = k + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            i++
            if (c == '"') return i
        }
        throw IllegalArgumentException("字符串未闭合")
    }

    /** 跳过一个 JSON 值（字符串/对象/数组/字面量），返回其末字符之后的位置。 */
    private fun skipValue(s: String, from: Int): Int {
        var i = from
        require(i < s.length) { "值缺失" }
        when (s[i]) {
            '"' -> return skipString(s, i)
            '{', '[' -> {
                val open = s[i]
                val close = if (open == '{') '}' else ']'
                var depth = 0
                while (i < s.length) {
                    when {
                        s[i] == '"' -> { i = skipString(s, i); continue }
                        s[i] == open -> depth++
                        s[i] == close -> { depth--; if (depth == 0) return i + 1 }
                    }
                    i++
                }
                throw IllegalArgumentException("括号未闭合")
            }
            else -> {
                // true/false/null/数字：读到结构性字符为止（这些字面量不含 , } ] / 或空白）
                while (i < s.length && s[i] !in ",}] \t\r\n/") i++
                require(i > from) { "值缺失" }
                return i
            }
        }
    }

    /** 顶层键名比较：含转义序列时按 JSON 语义解转义后再比（`"model"` 也认得出是 model）。 */
    private fun unescapeKey(raw: String): String {
        if ('\\' !in raw) return raw
        return runCatching { org.json.JSONTokener("\"$raw\"").nextValue() as? String }.getOrNull() ?: raw
    }

    /**
     * 剥注释/尾逗号（只用于读取路径的内存解析与本地自检；**结果绝不写回**）。
     * 字符串内的行注释符、块注释符不当注释；尾逗号按官方 `allowTrailingComma` 语义丢弃。
     */
    internal fun stripJsonc(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inStr = false
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                out.append(c)
                if (c == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' ->
                    { while (i < text.length && text[i] != '\n') i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val e = text.indexOf("*/", i + 2)
                    require(e >= 0) { "块注释未闭合" }
                    out.append(' ')
                    i = e + 2
                }
                c == ',' -> {
                    // 尾逗号：向后跳过空白/注释，若下一个结构字符是 } 或 ] 则丢弃该逗号
                    var j = i + 1
                    while (j < text.length) {
                        when {
                            text[j].isWhitespace() -> j++
                            text[j] == '/' && j + 1 < text.length && text[j + 1] == '/' -> {
                                val e = text.indexOf('\n', j); j = if (e >= 0) e + 1 else text.length
                            }
                            text[j] == '/' && j + 1 < text.length && text[j + 1] == '*' -> {
                                val e = text.indexOf("*/", j + 2)
                                require(e >= 0) { "块注释未闭合" }
                                j = e + 2
                            }
                            else -> break
                        }
                    }
                    if (j < text.length && (text[j] == '}' || text[j] == ']')) i++ else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    // ---------- 远端层（协议同 GeminiRouteConfig / RemoteAtomicJson：SFTP 传内容，argv 只传 hash） ----------

    internal data class Resolved(val fileName: String, val snapshot: RemoteAtomicJson.Snapshot, val bothExist: Boolean)

    /** 按官方查找顺序解析实际生效的配置文件：`.jsonc` 优先，其次 `.json`；都缺 = 新建 `.json`（文档 canonical 名）。 */
    private suspend fun resolve(ssh: SshSession, dir: String): Resolved {
        val jsonc = readOne(ssh, "$dir/$FILE_JSONC")
        val json = readOne(ssh, "$dir/$FILE_JSON")
        val useJsonc = jsonc.text != null
        return Resolved(
            fileName = if (useJsonc) FILE_JSONC else FILE_JSON,
            snapshot = if (useJsonc) jsonc else json,
            bothExist = useJsonc && json.text != null,
        )
    }

    private suspend fun readOne(ssh: SshSession, path: String): RemoteAtomicJson.Snapshot =
        try { RemoteAtomicJson.read(ssh, path) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error("无法读取 OpenCode 配置（$path）") }

    suspend fun status(ssh: SshSession, dir: String): Status {
        val r = resolve(ssh, dir)
        val text = r.snapshot.text
        var model: String? = null
        var providers = emptyList<Provider>()
        var others = emptyList<String>()
        var parseError: String? = null
        if (text != null) {
            try {
                val p = parseConfig(text)
                model = p.model
                providers = p.providers
                others = p.otherTopLevelKeys
            } catch (e: IllegalArgumentException) {
                parseError = e.message
            }
        }
        return Status(
            dir = dir,
            fileName = if (text == null) null else r.fileName,
            selectedModel = model,
            providers = providers,
            otherTopLevelKeys = others,
            parseError = parseError,
            bothExist = r.bothExist,
            revision = r.snapshot.revision,
        )
    }

    /**
     * 显式写入选定 provider/model（顶层 `model`）。read → 本地手术 → compare-and-swap 单文件落盘。
     * @return null = 成功；否则中文错误说明（绝不静默、绝不重建整文件、绝不自动重试）。
     */
    suspend fun apply(ssh: SshSession, dir: String, patch: Patch, expected: Status? = null): String? {
        validateModel(patch.model)
        val r = resolve(ssh, dir)
        if (r.bothExist) {
            return "$dir 下 $FILE_JSON 与 $FILE_JSONC 并存：官方会把两份都合并，写哪份都可能被另一份盖回。" +
                "本次未写入，请先手动整理为一份再使用本功能"
        }
        if (expected != null && (expected.dir != dir || expected.revision != r.snapshot.revision ||
                expected.fileName != r.fileName.takeIf { r.snapshot.text != null } || expected.bothExist != r.bothExist)) {
            return "配置在编辑期间已被修改，本次未覆盖，请刷新后重新编辑"
        }
        val newText = try { patchModel(r.snapshot.text, patch.model) } catch (e: IllegalArgumentException) { return e.message }
        parseConfig(newText) // 自检：手术结果必须仍是合法 JSONC；不是则视为缺陷，宁可不上传
        return commit(ssh, "$dir/${r.fileName}", newText, r.snapshot.revision)
    }

    /** 服务器侧提交：SFTP 传临时文件 → python3 锁内 hash 比对 + JSONC 校验 → 0600 + 备份 + 原子替换。null = 成功。 */
    private suspend fun commit(ssh: SshSession, path: String, text: String, expected: String): String? {
        val parent = path.substringBeforeLast('/')
        val temp = "$parent/.yxi-upload-${UUID.randomUUID()}"
        try {
            check(ssh.exec("(umask 077; mkdir -p " + Shell.q(parent) + "; set -C; : > " + Shell.q(temp) + ") && printf YXI_READY").trim() == "YXI_READY") {
                "无法创建受保护的临时配置"
            }
            val bytes = text.toByteArray()
            require(bytes.size <= MAX_BYTES) { "配置过大，未覆盖" }
            val sftp = ssh.openSftp()
            try { sftp.write(temp, bytes) } finally { sftp.close() }
            val command = "python3 -c " + Shell.q(SCRIPT) + " " +
                listOf(path, temp, expected, RemoteAtomicJson.hash(bytes), BACKUP_DIR).joinToString(" ") { Shell.q(it) }
            val raw = ssh.exec(command)
            val response = runCatching { JSONObject(raw.trim()) }.getOrElse { return "未收到配置提交确认，请刷新核对；不会自动重试" }
            return when (response.optString("status")) {
                "saved" -> null
                "conflict" -> "配置已被其他人修改，本次未覆盖，请刷新后重试"
                else -> "配置未提交：" + response.optString("error", "未知错误")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "配置未提交：${e.message}"
        } finally {
            withContext(NonCancellable) { runCatching { ssh.exec("rm -f -- " + Shell.q(temp)) } }
        }
    }

    /**
     * 提交脚本（远端 python3）。参数：path temp expected wanted backups。
     * 与 [RemoteAtomicJson] 同一协议；两点不同：备份目录走参数（本地测试可用临时目录）、
     * 校验按 **JSONC**（剥注释/尾逗号再 json.loads，同官方 jsonc-parser + allowTrailingComma 口径）。
     * 备份前缀 `opencode-route-`。
     */
    internal val SCRIPT = """
import os,sys,json,hashlib,fcntl,tempfile
p,t,expected,wanted,backups=sys.argv[1:]
def strip_jsonc(s):
    out=[];i=0;n=len(s);instr=False
    while i<n:
        c=s[i]
        if instr:
            out.append(c)
            if c=='\\' and i+1<n: out.append(s[i+1]);i+=2;continue
            if c=='"': instr=False
            i+=1;continue
        if c=='"': instr=True;out.append(c);i+=1;continue
        if c=='/' and i+1<n and s[i+1]=='/':
            i+=2
            while i<n and s[i]!='\n': i+=1
            continue
        if c=='/' and i+1<n and s[i+1]=='*':
            j=s.find('*/',i+2)
            if j<0: raise ValueError('unclosed block comment')
            out.append(' ');i=j+2;continue
        if c==',':
            j=i+1
            while j<n:
                if s[j] in ' \t\r\n': j+=1
                elif s[j]=='/' and j+1<n and s[j+1]=='/':
                    k=s.find('\n',j);j=k+1 if k>=0 else n
                elif s[j]=='/' and j+1<n and s[j+1]=='*':
                    k=s.find('*/',j+2)
                    if k<0: raise ValueError('unclosed block comment')
                    j=k+2
                else: break
            if j<n and s[j] in '}]': i+=1;continue
        out.append(c);i+=1
    return ''.join(out)
def valid(b):
    json.loads(strip_jsonc(b.decode('utf-8')))
def revision():
    if os.path.islink(p): raise ValueError('Configuration symlinks are not writable here')
    if not os.path.exists(p): return 'missing',None
    with open(p,'rb') as f: data=f.read(2097153)
    if len(data)>2097152: raise ValueError('Configuration too large')
    valid(data)
    return hashlib.sha256(data).hexdigest(),data
try:
    fd=os.open(p+'.yxi-lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        current,old=revision()
        if current!=expected:
            print(json.dumps({'status':'conflict'}));sys.exit(0)
        with open(t,'rb') as f: new=f.read(2097153)
        if len(new)>2097152 or hashlib.sha256(new).hexdigest()!=wanted: raise ValueError('Upload verification failed')
        valid(new)
        os.chmod(t,0o600)
        if old is not None:
            d=os.path.expanduser(backups)
            os.makedirs(d,mode=0o700,exist_ok=True);os.chmod(d,0o700)
            bfd,bname=tempfile.mkstemp(prefix='opencode-route-',dir=d)
            with os.fdopen(bfd,'wb') as f: f.write(old);f.flush();os.fsync(f.fileno())
            os.chmod(bname,0o600)
        with open(t,'rb') as f: os.fsync(f.fileno())
        if revision()[0]!=expected:
            print(json.dumps({'status':'conflict'}));sys.exit(0)
        os.replace(t,p)
        print(json.dumps({'status':'saved'}))
except Exception as e:
    print(json.dumps({'status':'error','error':str(e)}))
""".trimIndent()
}
