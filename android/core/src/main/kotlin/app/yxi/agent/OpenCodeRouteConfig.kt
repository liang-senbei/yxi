package app.yxi.agent

import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
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
 *  - 写入是**文本手术**，从不重建整文件：只把目标值 span 原位替换（键缺失则按文件自身的
 *    换行/缩进风格做风格化插入）；注释、未知字段、键序、缩进一字不动；目标键出现多次 → 拒绝；
 *    现有内容不是合法 JSONC → 拒绝盲改。
 *  - scope 边界（本迭代）：顶层 `model` 选择 + provider 基础字段（[ProviderPatch]：name/npm/
 *    options.baseURL/options.apiKey/models 键登记的新增与编辑）。`""` = 显式写空串；
 *    ensureModelIds 只补缺登记空条目、绝不改写已有条目内容。**不**删除 provider/字段、**不**改已有
 *    model 条目的内容、不写 small_model/权限/MCP 等其它键；不自动切换生产配置（调用方 UI 显式确认后才落盘）。
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

    /** provider id：与 [MODEL] 的 provider 段同款（官方 providerID，单段，无 `/`）。 */
    val PROVIDER_ID = Regex("^[A-Za-z0-9._~:+-]{1,100}$")

    /** model id（provider.models 的键）：与 [MODEL] 的 modelID 段同款，可含 `/`。 */
    val MODEL_ID = Regex("^[A-Za-z0-9._~:/+-]{1,200}$")

    /** npm 包名：`@scope/name` 或 `name`（段内可 `._-`）；空串 = 显式清空，单独放行。 */
    val NPM_PACKAGE = Regex("^@?[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)?$")

    /**
     * 新增/编辑一个 provider 的基础字段（顶层 `provider.<id>`）。null = 不动；`""` = 显式写空串（清空语义）。
     * [ensureModelIds] 只**补缺**：缺的登记为空条目 `"id": {}`；已有条目（无论什么内容）绝不改写。
     * 不支持：删除 provider/字段、改名（id 即身份）、动其它 provider 或未知字段。
     */
    data class ProviderPatch(
        val id: String,
        val name: String? = null,
        val npm: String? = null,
        val baseURL: String? = null,
        val apiKey: String? = null,
        val ensureModelIds: List<String> = emptyList(),
    )

    fun validateProviderPatch(patch: ProviderPatch) {
        require(PROVIDER_ID.matches(patch.id)) {
            "provider id 只允许字母/数字/._~:+-（1-100 位）：${patch.id.take(80)}"
        }
        require(
            patch.name != null || patch.npm != null || patch.baseURL != null ||
                patch.apiKey != null || patch.ensureModelIds.isNotEmpty(),
        ) { "provider ${patch.id} 没有任何要写的字段" }
        patch.name?.let {
            require(it.length <= 200 && it.none { c -> c < ' ' }) { "name 含控制符或超 200 字，未写入" }
        }
        patch.npm?.let {
            require(it.length <= 200) { "npm 超 200 字，未写入" }
            if (it.isNotEmpty()) require(NPM_PACKAGE.matches(it)) { "npm 需为包名（如 @scope/pkg 或 pkg）：${it.take(80)}" }
        }
        patch.baseURL?.let {
            require(it.length <= 2048 && it.none { c -> c < ' ' }) { "baseURL 含控制符或超长，未写入" }
            if (it.isNotEmpty()) {
                val u = runCatching { URI(it) }.getOrElse { throw IllegalArgumentException("baseURL 不是合法 URL") }
                require(u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrBlank()) {
                    "baseURL 需为 http(s) URL：${it.take(80)}"
                }
                require(u.userInfo == null) { "baseURL 不应携带用户信息（user:pass@），拒绝写入" }
            }
        }
        patch.apiKey?.let {
            require(it.length <= 4096 && it.none { c -> c < ' ' }) { "apiKey 含控制符或超 4096 字，未写入" }
        }
        require(patch.ensureModelIds.size <= 100) { "一次最多登记 100 个 model id" }
        patch.ensureModelIds.forEach {
            require(MODEL_ID.matches(it)) { "model id 只允许字母/数字/._~:/+-（1-200 位）：${it.take(80)}" }
        }
        require(patch.ensureModelIds.toSet().size == patch.ensureModelIds.size) { "model id 有重复" }
    }

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
        // 严格语法闸：org.json 偏宽（裸 token、单引号、`12 3` 这类 token 粘连都吃），
        // 官方 jsonc-parser 会拒；写闸/读闸都按官方口径，语法错误统一给脱敏文案。
        runCatching { validateStrictJsonc(text) }
            .getOrElse { throw IllegalArgumentException("配置不是合法 JSONC，请检查语法后重试") }
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
    private fun insertNewKey(text: String, rootOpen: Int, model: String): String =
        insertIntoObject(text, rootOpen, "\"model\": \"$model\"")

    internal data class Spans(val rootOpen: Int, val modelSpan: Pair<Int, Int>?)

    /**
     * 定位根 `{` 与顶层 `model` 的值 span（起 = 值首字符，止 = 值末字符之后；尾随空白/注释不在 span 内）。
     * 只认直接挂在根对象下的键；嵌套对象里的同名 `model` 一律不碰。重复键拒绝（JSON 语义取后者，手术取「不动」）。
     */
    internal fun scanTopLevel(text: String): Spans {
        val rootOpen = skipWsComments(text, 0)
        require(rootOpen < text.length) { "配置为空" }
        require(text[rootOpen] == '{') { "配置根必须是对象" }
        val models = objectEntries(text, rootOpen).filter { it.key == "model" }
        require(models.size <= 1) { "顶层 model 出现 ${models.size} 次，请先手动整理为一份再使用本功能" }
        return Spans(rootOpen, models.firstOrNull()?.valueSpan)
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
                        // 注释里的括号不计深度（否则 `/* } */` 会截断值 span、手术定错位）
                        s[i] == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                            val e = s.indexOf('\n', i + 2); i = if (e >= 0) e else s.length; continue
                        }
                        s[i] == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                            val e = s.indexOf("*/", i + 2)
                            require(e >= 0) { "块注释未闭合" }
                            i = e + 2; continue
                        }
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
     * 严格 JSONC 语法校验（与官方 jsonc-parser 语义对齐）。org.json 的解析器偏宽
     * （裸 token、单引号字符串、注释剥离后 `12 3` 这类粘连都吞），直接拿它当写闸会把
     * opencode 加载不了的配置放过去；这里按 JSON 语法自己走一遍，块注释当空格（token 不粘连）。
     */
    internal fun validateStrictJsonc(text: String) {
        val rootOpen = skipWsComments(text, 0)
        require(rootOpen < text.length) { "配置为空" }
        require(text[rootOpen] == '{') { "配置根必须是对象" }
        val end = validateObject(text, rootOpen)
        require(skipWsComments(text, end) >= text.length) { "对象结束后有多余内容" }
    }

    /** 校验 [openPos] 处的对象，返回其 `}` 之后的位置。尾逗号容忍（官方 allowTrailingComma）。 */
    private fun validateObject(text: String, openPos: Int): Int {
        var i = openPos + 1
        while (true) {
            i = skipWsComments(text, i)
            require(i < text.length) { "对象未闭合" }
            if (text[i] == '}') return i + 1
            if (text[i] == ',') { i++; continue }
            require(text[i] == '"') { "对象键必须是字符串" }
            i = skipString(text, i)
            i = skipWsComments(text, i)
            require(i < text.length && text[i] == ':') { "键后缺少冒号" }
            i = validateValue(text, i + 1)
        }
    }

    /** 校验 [openPos] 处的数组，返回其 `]` 之后的位置。 */
    private fun validateArray(text: String, openPos: Int): Int {
        var i = openPos + 1
        while (true) {
            i = skipWsComments(text, i)
            require(i < text.length) { "数组未闭合" }
            if (text[i] == ']') return i + 1
            if (text[i] == ',') { i++; continue }
            i = validateValue(text, i)
        }
    }

    /** 校验一个严格 JSON 值，返回其末尾位置。 */
    private fun validateValue(text: String, from: Int): Int {
        val i = skipWsComments(text, from)
        require(i < text.length) { "值缺失" }
        return when (text[i]) {
            '"' -> skipString(text, i)
            '{' -> validateObject(text, i)
            '[' -> validateArray(text, i)
            't' -> { require(text.startsWith("true", i)) { "非法字面量" }; i + 4 }
            'f' -> { require(text.startsWith("false", i)) { "非法字面量" }; i + 5 }
            'n' -> { require(text.startsWith("null", i)) { "非法字面量" }; i + 4 }
            else -> {
                require(text[i] == '-' || text[i].isDigit()) { "非法值" }
                validateNumber(text, i)
            }
        }
    }

    /** 严格数字：`-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`，后紧跟字母/下划线/点也拒（粘连）。 */
    private fun validateNumber(text: String, from: Int): Int {
        var i = from
        if (text[i] == '-') i++
        require(i < text.length && text[i].isDigit()) { "数字非法" }
        if (text[i] == '0') i++
        else while (i < text.length && text[i].isDigit()) i++
        if (i < text.length && text[i] == '.') {
            i++
            require(i < text.length && text[i].isDigit()) { "小数非法" }
            while (i < text.length && text[i].isDigit()) i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            require(i < text.length && text[i].isDigit()) { "指数非法" }
            while (i < text.length && text[i].isDigit()) i++
        }
        require(i >= text.length || !text[i].isLetterOrDigit() && text[i] != '_' && text[i] != '.') { "数字后紧跟非法字符" }
        return i
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

    // ---------- provider 新增/编辑（文本手术，纪律同 patchModel：未提及的字节一律不动） ----------

    /** 一个对象成员：解转义后的键 + 值 span（起 = 值首字符，止 = 值末字符之后）。 */
    internal data class ObjEntry(val key: String, val valueSpan: Pair<Int, Int>)

    /**
     * 枚举 [openPos] 对象的直接成员。容忍尾逗号/多余逗号（官方 allowTrailingComma 同款）；
     * 重复键不在枚举层拒——由调用方按语义给准确报错。
     */
    internal fun objectEntries(text: String, openPos: Int): List<ObjEntry> {
        var i = openPos + 1
        val out = mutableListOf<ObjEntry>()
        while (true) {
            i = skipWsComments(text, i)
            require(i < text.length) { "对象未闭合" }
            if (text[i] == '}') break
            if (text[i] == ',') { i++; continue }
            require(text[i] == '"') { "对象键必须是字符串" }
            val keyStart = i
            i = skipString(text, i)
            val key = unescapeKey(text.substring(keyStart + 1, i - 1))
            i = skipWsComments(text, i)
            require(i < text.length && text[i] == ':') { "键后缺少冒号" }
            val vStart = skipWsComments(text, i + 1)
            val vEnd = skipValue(text, vStart)
            i = vEnd
            out += ObjEntry(key, vStart to vEnd)
        }
        return out
    }

    internal data class Located(val parentOpen: Int, val entry: ObjEntry?)

    /**
     * 按键路径定位：返回**叶子父亲**的 `{` 位置与叶子条目；entry=null = 路径前缀都在、叶子缺（可插入）。
     * 路径上重复键、中间节点不是对象 → 拒绝（手术只认唯一、确定的挂点）。
     */
    internal fun locatePath(text: String, path: List<String>): Located {
        var open = skipWsComments(text, 0)
        require(open < text.length && text[open] == '{') { "配置根必须是对象" }
        path.forEachIndexed { idx, key ->
            val hits = objectEntries(text, open).filter { it.key == key }
            require(hits.size <= 1) {
                "路径 ${path.take(idx + 1).joinToString(" → ")} 出现 ${hits.size} 次，请先手动整理为一份再使用本功能"
            }
            val e = hits.firstOrNull() ?: return Located(open, null)
            if (idx == path.size - 1) return Located(open, e)
            open = skipWsComments(text, e.valueSpan.first)
            require(open < text.length && text[open] == '{') {
                "${path.take(idx + 1).joinToString(" → ")} 不是对象（类型冲突）"
            }
        }
        error("unreachable")
    }

    /** [e] 的值必须是对象，返回其 `{` 位置；不是则类型冲突拒绝。 */
    internal fun requireObjOpen(text: String, e: ObjEntry, what: String): Int {
        val open = skipWsComments(text, e.valueSpan.first)
        require(open < text.length && text[open] == '{') { "$what 不是对象（类型冲突），未改动" }
        return open
    }

    /**
     * 在 [openPos] 对象的 `{` 后插入一个成员（[pair] 已是 `"key": value` 字面文本，调用方负责引号）；
     * 已有成员时补逗号。风格判定：`{` 与首个结构字符之间有换行 = 多行文件（抄既有缩进），否则内联。
     */
    internal fun insertIntoObject(text: String, openPos: Int, pair: String): String {
        val firstStruct = skipWsComments(text, openPos + 1)
        require(firstStruct < text.length) { "对象未闭合" }
        val pretty = text.indexOf('\n', openPos + 1) in (openPos + 1) until firstStruct
        return if (text[firstStruct] == '}') {
            if (pretty) text.replaceRange(openPos + 1, openPos + 1, "\n  $pair")
            else text.replaceRange(openPos + 1, openPos + 1, pair)
        } else if (pretty) {
            val lineStart = text.lastIndexOf('\n', firstStruct - 1) + 1
            val indent = text.substring(lineStart, firstStruct)
            text.replaceRange(openPos + 1, openPos + 1, "\n$indent$pair,")
        } else {
            text.replaceRange(openPos + 1, openPos + 1, "$pair,")
        }
    }

    /**
     * 把 `path` 指向的字符串值原位替换为 [value]；叶子缺失 = 在父亲对象里按风格插入；
     * 叶子不是字符串（对象/数组/数字/布尔）= 类型冲突拒绝。除该值外一字节不动。
     */
    internal fun setStringAt(text: String, path: List<String>, value: String): String {
        val loc = locatePath(text, path)
        val e = loc.entry ?: return insertIntoObject(text, loc.parentOpen, "${jsonQuote(path.last())}: ${jsonQuote(value)}")
        require(text[e.valueSpan.first] == '"') { "${path.joinToString(" → ")} 现有值不是字符串（类型冲突），未改动" }
        return text.replaceRange(e.valueSpan.first, e.valueSpan.second, jsonQuote(value))
    }

    /** 手写 JSON 字符串字面量（org.json 构建键序无保证，新子树要求字节确定）。 */
    internal fun jsonQuote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** 新子树专用：从有序成员构建紧凑单行对象。既有内容永远原样保留，不走这里。 */
    internal fun buildObject(members: List<Pair<String, String>>): String =
        members.joinToString(", ", "{ ", " }") { (k, v) -> "${jsonQuote(k)}: $v" }

    /** 新建 provider 对象：只含请求字段，固定顺序 name → npm → options → models（决定性输出）。 */
    private fun buildProviderObject(patch: ProviderPatch): String {
        val members = mutableListOf<Pair<String, String>>()
        patch.name?.let { members += "name" to jsonQuote(it) }
        patch.npm?.let { members += "npm" to jsonQuote(it) }
        if (patch.baseURL != null || patch.apiKey != null) {
            members += "options" to buildObject(
                buildList {
                    if (patch.baseURL != null) add("baseURL" to jsonQuote(patch.baseURL))
                    if (patch.apiKey != null) add("apiKey" to jsonQuote(patch.apiKey))
                },
            )
        }
        if (patch.ensureModelIds.isNotEmpty()) {
            members += "models" to buildObject(patch.ensureModelIds.map { it to "{}" })
        }
        return buildObject(members)
    }

    /**
     * 新增/编辑顶层 `provider.<patch.id>` 的基础字段。手术纪律同 [patchModel]：未提及的字段、
     * 其它 provider、注释、未知字段、键序一字不动；新对象字段顺序固定。text 为空/空白 = 新建最小配置。
     * 动手前后各过一道 [parseConfig]（拒绝在解析不了的配置上盲改；结果自检必须仍是合法 JSONC）。
     */
    fun patchProvider(text: String?, patch: ProviderPatch): String {
        validateProviderPatch(patch)
        if (text.isNullOrBlank()) {
            return "{\n  \"provider\": {\n    ${jsonQuote(patch.id)}: ${buildProviderObject(patch)}\n  }\n}\n"
        }
        scanTopLevel(text)
        parseConfig(text)
        var t: String = text
        val pLoc = locatePath(t, listOf("provider"))
        if (pLoc.entry == null) {
            // provider 容器整个不存在：一次性建 provider → { id → 全部请求字段 }
            t = insertIntoObject(t, pLoc.parentOpen, "\"provider\": " + buildObject(listOf(patch.id to buildProviderObject(patch))))
        } else {
            requireObjOpen(t, pLoc.entry, "顶层 provider")
            val idLoc = locatePath(t, listOf("provider", patch.id))
            if (idLoc.entry == null) {
                t = insertIntoObject(t, idLoc.parentOpen, "${jsonQuote(patch.id)}: " + buildProviderObject(patch))
            } else {
                requireObjOpen(t, idLoc.entry, "provider ${patch.id}")
                var u: String = t
                if (patch.name != null) u = setStringAt(u, listOf("provider", patch.id, "name"), patch.name)
                if (patch.npm != null) u = setStringAt(u, listOf("provider", patch.id, "npm"), patch.npm)
                if (patch.baseURL != null || patch.apiKey != null) {
                    val oLoc = locatePath(u, listOf("provider", patch.id, "options"))
                    if (oLoc.entry == null) {
                        val members = buildList {
                            if (patch.baseURL != null) add("baseURL" to jsonQuote(patch.baseURL))
                            if (patch.apiKey != null) add("apiKey" to jsonQuote(patch.apiKey))
                        }
                        u = insertIntoObject(u, oLoc.parentOpen, "\"options\": " + buildObject(members))
                    } else {
                        requireObjOpen(u, oLoc.entry, "provider ${patch.id} 的 options")
                        // setStringAt 各自重新定位：前一步会移动既有位置，span 不可复用
                        if (patch.baseURL != null) u = setStringAt(u, listOf("provider", patch.id, "options", "baseURL"), patch.baseURL)
                        if (patch.apiKey != null) u = setStringAt(u, listOf("provider", patch.id, "options", "apiKey"), patch.apiKey)
                    }
                }
                if (patch.ensureModelIds.isNotEmpty()) {
                    val mLoc = locatePath(u, listOf("provider", patch.id, "models"))
                    if (mLoc.entry == null) {
                        u = insertIntoObject(u, mLoc.parentOpen, "\"models\": " + buildObject(patch.ensureModelIds.map { it to "{}" }))
                    } else {
                        // 只补缺：每插一个都重新定位 + 重查（插左会移动既有位置）
                        for (mid in patch.ensureModelIds) {
                            val cur = locatePath(u, listOf("provider", patch.id, "models"))
                            val open = requireObjOpen(u, cur.entry!!, "provider ${patch.id} 的 models")
                            if (objectEntries(u, open).any { it.key == mid }) continue
                            u = insertIntoObject(u, open, "${jsonQuote(mid)}: {}")
                        }
                    }
                }
                t = u
            }
        }
        parseConfig(t) // 自检：手术结果必须仍是合法 JSONC；不是则视为缺陷，宁可拒绝也不落盘
        return t
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
    /** `$dir` 下两份配置并存的拒绝文案（官方两份都合并，写哪份都可能被另一份盖回）。 */
    private fun bothExistRefusal(dir: String): String =
        "$dir 下 $FILE_JSON 与 $FILE_JSONC 并存：官方会把两份都合并，写哪份都可能被另一份盖回。" +
            "本次未写入，请先手动整理为一份再使用本功能"

    suspend fun apply(ssh: SshSession, dir: String, patch: Patch, expected: Status? = null): String? {
        validateModel(patch.model)
        val r = resolve(ssh, dir)
        if (r.bothExist) return bothExistRefusal(dir)
        if (expected != null && expectationStale(expected, dir, r)) {
            return "配置在编辑期间已被修改，本次未覆盖，请刷新后重新编辑"
        }
        val newText = try { patchModel(r.snapshot.text, patch.model) } catch (e: IllegalArgumentException) { return e.message }
        parseConfig(newText) // 自检：手术结果必须仍是合法 JSONC；不是则视为缺陷，宁可不上传
        return commit(ssh, "$dir/${r.fileName}", newText, r.snapshot.revision)
    }

    /**
     * 新增/编辑 provider 基础字段（[ProviderPatch]）。防覆盖协议同 [apply]：两份并存拒绝、
     * expected 全量身份比对、hash CAS 单文件落盘。@return null = 成功；否则中文错误说明
     * （绝不静默、绝不重建整文件、绝不自动重试）。
     */
    suspend fun applyProvider(ssh: SshSession, dir: String, patch: ProviderPatch, expected: Status? = null): String? {
        validateProviderPatch(patch)
        val r = resolve(ssh, dir)
        if (r.bothExist) return bothExistRefusal(dir)
        if (expected != null && expectationStale(expected, dir, r)) {
            return "配置在编辑期间已被修改，本次未覆盖，请刷新后重新编辑"
        }
        val newText = try { patchProvider(r.snapshot.text, patch) } catch (e: IllegalArgumentException) { return e.message }
        parseConfig(newText)
        return commit(ssh, "$dir/${r.fileName}", newText, r.snapshot.revision)
    }

    /**
     * expected 快照与当前实况的**全量身份比对**：目录、revision、生效文件名（存在性口径）、
     * 双文件并存态，任一变化即视为「已被修改」——防期间文件被换名/删除/第二份出现后盖错对象。
     */
    internal fun expectationStale(expected: Status, dir: String, r: Resolved): Boolean =
        expected.dir != dir || expected.revision != r.snapshot.revision ||
            expected.fileName != r.fileName.takeIf { r.snapshot.text != null } ||
            expected.bothExist != r.bothExist

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
