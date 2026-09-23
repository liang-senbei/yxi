package app.yxi.desktop

import org.json.JSONObject

/** Process-only official subscription selection. Never writes credentials or the native config. */
internal object LocalCodexProfiles {
    const val chatgptBase = "https://chatgpt.com/backend-api/"
    private val excludedEnvironment = setOf("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL",
        "OPENAI_FEDERATION_RULE_ID", "OPENAI_IDENTITY_TOKEN_FILE")

    internal fun officialArguments() = listOf("-c", "model_provider=\"openai\"", "-c", "openai_base_url=\"\"",
        "-c", "chatgpt_base_url=\"$chatgptBase\"", "app-server")

    internal fun officialEnvironment(inherited: Map<String, String>) = inherited.filterKeys { it.uppercase() !in excludedEnvironment }

    internal fun verifyOfficial(config: JSONObject, account: JSONObject) {
        check(config.optString("model_provider") == "openai") { "运行器未确认官方提供方，未启用发送" }
        check(config.has("openai_base_url") && config.optString("openai_base_url") == "") { "无法确认已移除外部 API 端点，未启用发送" }
        check(config.optString("chatgpt_base_url").trimEnd('/') == chatgptBase.trimEnd('/')) { "运行器登录端点不是官方地址，未启用发送" }
        check(account.optBoolean("requiresOpenaiAuth", false) && account.optJSONObject("account")?.optString("type") == "chatgpt") {
            "官方订阅需要已有 ChatGPT 登录；原登录保持不变，请先在 Codex 完成登录"
        }
    }

    internal fun verifyMutation(method: String, params: JSONObject, thread: JSONObject? = null) {
        val overrides = params.optJSONObject("config")
        check(overrides == null || overrides.keys().asSequence().all { it == "model_reasoning_effort" }) {
            "官方订阅不接受未经核对的会话配置覆盖"
        }
        val requested = params.optString("modelProvider")
        check(requested.isEmpty() || requested == "openai") { "官方订阅不能向第三方提供方发送" }
        if (method in setOf("thread/resume", "thread/fork")) check(requested == "openai") {
            "继续历史必须显式核对目标提供方，不能隐式沿用旧第三方配置"
        }
        if (method in setOf("turn/start", "turn/steer")) check(thread?.optString("modelProvider") == "openai") {
            "会话的实际提供方尚未确认为官方，未启用发送"
        }
    }

    suspend fun connectOfficial(runtime: LocalRuntimeInstallation): CodexAppServer = connect(runtime, emptyList())

    suspend fun connectOfficialWithSharedMcp(runtime: LocalRuntimeInstallation, records: List<SharedMcpRecord>): CodexAppServer {
        require(records.all { !it.retired && it.definition.hostKey == "@local" && "codex" in it.desiredRunners })
        connectOfficial(runtime).use { probe ->
            val config = probe.request("config/read", JSONObject().put("includeLayers", false)).getJSONObject("result").getJSONObject("config")
            val existing = config.optJSONObject("mcp_servers")
            check(records.none { it.definition.name == "codex_apps" || existing?.has(it.definition.name) == true }) { "原生 Codex 已有同名或内置 MCP，未覆盖" }
        }
        return connect(runtime, records)
    }
    internal fun verifySharedMcp(config: JSONObject, records: List<SharedMcpRecord>) {
        records.forEach { record ->
            val definition = record.definition
            val entry = config.optJSONObject("mcp_servers")?.optJSONObject(definition.name) ?: error("运行器未确认共享 MCP：${definition.name}")
            check(entry.optBoolean("enabled", true)) { "共享 MCP 被原生规则禁用：${definition.name}" }
            if (definition.command.isNotEmpty()) {
                check(entry.optString("command") == definition.command.first() && entry.isNull("url") &&
                    (entry.optJSONArray("args") ?: org.json.JSONArray()).similar(org.json.JSONArray(definition.command.drop(1)))) { "共享 MCP 启动配置不一致：${definition.name}" }
            } else check(entry.optString("url") == definition.url && entry.isNull("command")) { "共享 MCP 地址不一致：${definition.name}" }
            for (key in listOf("env", "env_vars", "http_headers", "env_http_headers", "bearer_token_env_var", "bearer_token")) {
                val value = entry.opt(key)
                check(value == null || value === JSONObject.NULL || value == "" || (value is JSONObject && value.length() == 0) ||
                    (value is org.json.JSONArray && value.length() == 0)) { "共享 MCP 混入了未声明的认证或环境覆盖：${definition.name}" }
            }
        }
    }
    private suspend fun connect(runtime: LocalRuntimeInstallation, records: List<SharedMcpRecord>): CodexAppServer {
        val arguments = officialArguments().dropLast(1) + SharedMcpSettings.codexArguments(records, "@local") + "app-server"
        val transport = LocalCodexTransport.start(runtime, arguments, officialEnvironment(System.getenv()))
        val client = CodexAppServer(transport, profileLabel = "官方订阅 · ChatGPT")
        try {
            client.initializeLocal()
            suspend fun verify() {
                val config = client.request("config/read", JSONObject().put("includeLayers", false)).getJSONObject("result").getJSONObject("config")
                val account = client.request("account/read", JSONObject().put("refreshToken", false)).getJSONObject("result")
                verifyOfficial(config, account)
                verifySharedMcp(config, records)
            }
            verify()
            client.beforeLocalMutation = { method, params ->
                verify()
                val thread = if (method in setOf("turn/start", "turn/steer")) client.request("thread/read",
                    JSONObject().put("threadId", params.getString("threadId")).put("includeTurns", false))
                    .getJSONObject("result").getJSONObject("thread") else null
                verifyMutation(method, params, thread)
            }
            return client
        } catch (e: Exception) { client.close(); throw e }
    }
}
