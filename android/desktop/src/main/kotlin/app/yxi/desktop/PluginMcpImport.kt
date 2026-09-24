package app.yxi.desktop

import org.json.JSONObject
import java.io.File

/** Read-only local manifest import; never resolves native credentials or installs resources. */
internal object PluginMcpImport {
    data class Preview(val definition: SharedMcpDefinition, val manifest: File, val digest: String)
    fun read(plugin: NativePlugin): List<Preview> {
        val source = JSONObject(plugin.source)
        require(source.optString("type") == "local") { "此条目未提供本地插件资源；云端连接器或专属插件不能直接登记为通用 MCP" }
        val root = File(source.optString("path"))
        require(root.isAbsolute && root.isDirectory) { "目录未提供可核对的本地绝对路径，请在共享配置中手动登记" }
        val manifest = File(root, ".mcp.json")
        require(manifest.isFile && manifest.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "未找到插件内的 .mcp.json，专属技能或钩子尚不支持转换" }
        val bytes = manifest.inputStream().use { it.readNBytes(262145) }
        require(bytes.size <= 262144) { "MCP 定义超过读取上限" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.keySet() == setOf("mcpServers")) { "MCP 根定义包含尚未适配的字段，请手动核对" }
        val servers = json.optJSONObject("mcpServers") ?: error("未找到通用 mcpServers 定义")
        require(servers.length() in 1..64) { "MCP 服务数量不支持" }
        val digest = contentHash(bytes)
        return servers.keys().asSequence().sorted().map { name ->
            val row = servers.getJSONObject(name)
            require(row.keySet().all { it in setOf("type", "command", "args", "url", "env", "headers") }) { "服务包含尚未适配的字段，请手动核对配置" }
            require(!row.has("command") || row.opt("command") is String) { "command 必须是字符串" }
            require(!row.has("url") || row.opt("url") is String) { "url 必须是字符串" }
            require(!row.has("type") || row.opt("type") is String) { "type 必须是字符串" }
            val command = row.opt("command") as? String
            val args = row.optJSONArray("args")
            require(!row.has("args") || args != null) { "命令参数必须是数组" }
            require(command != null || args == null) { "远程 MCP 不能包含命令参数" }
            val rawArgv = if (command != null) listOf(command) + (0 until (args?.length() ?: 0)).map { args!!.getString(it) } else emptyList()
            val npmLauncher = command?.replace('\\', '/')?.substringAfterLast('/')?.lowercase() in setOf("npx", "npx.cmd", "npm", "npm.cmd")
            val argv = rawArgv.mapIndexed { index, value -> argument(value, root.canonicalFile, index == 0, npmLauncher && index > 0) }
            val env = row.optJSONObject("env") ?: JSONObject()
            require(!row.has("env") || row.opt("env") is JSONObject) { "环境变量格式无效" }
            val environment = env.keys().asSequence().map { key ->
                require(reference(env.getString(key)) == key) { "环境变量仅支持同名引用，不能复制字面量或重命名凭据" }; key
            }.toSet()
            val headers = row.optJSONObject("headers") ?: JSONObject()
            require(!row.has("headers") || row.opt("headers") is JSONObject) { "请求头格式无效" }
            val headerVariables = headers.keys().asSequence().associateWith { reference(headers.getString(it)) ?: error("请求头仅支持完整环境变量引用，不复制认证值") }
            val type = row.optString("type")
            require(type.isEmpty() || type == "stdio" && command != null || type in setOf("http", "streamable-http") && command == null) { "此 MCP 传输方式尚未适配" }
            val definition = SharedMcpDefinition("@local", plugin.id + ":" + name, "local:" + root.canonicalPath,
                plugin.version.ifBlank { "sha256:$digest" }, name, argv, row.opt("url") as? String, environment, headerVariables)
            definition.validate()
            Preview(definition, manifest.canonicalFile, digest)
        }.toList()
    }
    private fun argument(value: String, root: File, executable: Boolean, npmArgument: Boolean): String {
        val key = value.substringBefore('=').lowercase()
        require(value.substringBefore('=') != "-H" && key !in setOf("--token", "--access-token", "--auth-token", "--password", "--secret", "--api-key", "--api_key", "--authorization", "--header")) {
            "命令包含认证或请求头参数，请改用插件专用环境变量引用"
        }
        val prefix = if (value.startsWith("--") && '=' in value) value.substringBefore('=') + "=" else ""
        val path = value.removePrefix(prefix)
        val marker = "${'$'}{CLAUDE_PLUGIN_ROOT}"
        if (path.startsWith(marker)) {
            val suffix = path.removePrefix(marker)
            require(suffix.isEmpty() || suffix.startsWith('/') || suffix.startsWith('\\')) { "插件根模板后的路径格式无效" }
            require('$' !in suffix) { "插件路径包含不支持的变量" }
            val resolved = File(root.path + suffix).canonicalFile
            require(resolved.toPath().startsWith(root.toPath()) && resolved.exists()) { "插件资源路径不存在或超出插件根目录" }
            return prefix + resolved.path
        }
        require('$' !in value) { "命令含尚未支持的变量，请手动核对" }
        // Scoped npm package names contain a slash but are not relative filesystem paths.
        val scopedPackage = npmArgument && Regex("@[a-z0-9][a-z0-9_.-]*/[a-z0-9][a-z0-9_.-]*(?:@[A-Za-z0-9_.+^~*-]+)?").matches(path)
        val isPath = !scopedPackage && (path.startsWith('.') || path.startsWith('~') || '/' in path || '\\' in path ||
            (!executable && Regex("(?i).+\\.(js|mjs|cjs|py|sh|ps1|json|yaml|yml|toml)").matches(path)))
        require(!isPath || File(path).isAbsolute) { "命令包含无法确认工作目录的相对资源路径，请使用插件根模板或绝对路径" }
        return value
    }
    private fun reference(value: String): String? = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").matchEntire(value)?.groupValues?.get(1)
    fun confirm(plugin: NativePlugin, preview: Preview, registry: SharedMcpRegistry, runners: Set<String>) {
        require(runners.isNotEmpty()) { "请选择至少一个运行器" }
        val fresh = read(plugin).singleOrNull { it.definition.key == preview.definition.key }
        check(fresh == preview) { "插件文件已变化，请重新预览" }
        registry.save(preview.definition, runners, null)
    }
}
