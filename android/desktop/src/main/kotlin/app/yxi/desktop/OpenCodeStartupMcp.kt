package app.yxi.desktop

import org.json.JSONObject

/** Native config loading expands variable references in the target process. No values are copied here. */
internal object OpenCodeStartupMcp {
    fun configuration(definitions: List<SharedMcpDefinition>): String {
        require(definitions.map { it.name }.distinct().size == definitions.size)
        val servers = JSONObject()
        definitions.forEach { definition ->
            servers.put(definition.name, SharedMcpSettings.forRunner(definition, "opencode").getJSONObject("mcp").getJSONObject(definition.name))
        }
        return JSONObject().put("mcp", servers).toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= 65536) { "共享 MCP 启动配置过大" }
        }
    }

    fun environment(inherited: Map<String, String>, definitions: List<SharedMcpDefinition>): Map<String, String> {
        if (definitions.isEmpty()) return inherited
        // Replacing an existing inline configuration would lose unrelated native provider settings.
        require(inherited["OPENCODE_CONFIG_CONTENT"].isNullOrBlank()) { "运行器已有进程配置，无法自动合并共享 MCP；请先核对 OPENCODE_CONFIG_CONTENT" }
        val names = definitions.flatMap { it.environmentNames + it.headerVariables.values }.toSet()
        require(names.all { !inherited[it].isNullOrBlank() }) { "目标机器的运行器启动环境缺少共享 MCP 所需变量" }
        return inherited + ("OPENCODE_CONFIG_CONTENT" to configuration(definitions))
    }
}
