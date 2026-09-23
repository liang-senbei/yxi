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

    suspend fun connectOfficial(runtime: LocalRuntimeInstallation): CodexAppServer {
        val transport = LocalCodexTransport.start(runtime, officialArguments(), officialEnvironment(System.getenv()))
        val client = CodexAppServer(transport, profileLabel = "官方订阅 · ChatGPT")
        try {
            client.initializeLocal()
            suspend fun verify() {
                val config = client.request("config/read", JSONObject().put("includeLayers", false)).getJSONObject("result").getJSONObject("config")
                val account = client.request("account/read", JSONObject().put("refreshToken", false)).getJSONObject("result")
                verifyOfficial(config, account)
            }
            verify()
            client.beforeLocalMutation = { verify() }
            return client
        } catch (e: Exception) { client.close(); throw e }
    }
}
