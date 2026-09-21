package app.yxi.desktop

import app.yxi.agent.Lines
import org.json.JSONObject
import java.net.URI

internal fun editedRoute(original: Lines.Line, name: String, url: String, secret: String, model: String, authToken: String = original.token): Lines.Line {
    require(name.isNotBlank()) { "请填写线路名称" }
    val endpoint = URI(url.trim())
    require(endpoint.scheme in listOf("https", "http") && !endpoint.host.isNullOrBlank()) { "请输入完整的 HTTP 或 HTTPS 端点地址" }
    require(endpoint.userInfo == null && endpoint.fragment == null && endpoint.query == null) { "端点地址不能包含账号、查询参数或片段" }
    require(secret.isNotBlank() || (!original.isCodex && authToken.isNotBlank())) { "请填写 API 密钥或认证令牌" }
    require(model.none { it < ' ' }) { "模型 ID 不能包含控制字符" }
    val extra = JSONObject(original.extra.toString())
    if (model.isBlank()) extra.remove("model") else extra.put("model", model.trim())
    if (!original.isCodex) {
        val env = extra.optJSONObject("env") ?: JSONObject()
        if (model.isBlank()) env.remove("ANTHROPIC_MODEL") else env.put("ANTHROPIC_MODEL", model.trim())
        if (env.length() == 0) extra.remove("env") else extra.put("env", env)
    }
    return original.copy(name = name.trim(), baseUrl = url.trim().trimEnd('/'), apiKey = secret.trim(), token = if (original.isCodex) "" else authToken.trim(), extra = extra)
}

internal fun routeModel(line: Lines.Line) = line.extra.optString("model").ifBlank { line.extraEnv().optString("ANTHROPIC_MODEL") }
internal fun routeCatalogEqual(a: List<Lines.Line>, b: List<Lines.Line>): Boolean =
    a.size == b.size && a.zip(b).all { (x, y) ->
        x.id == y.id && x.name == y.name && x.baseUrl == y.baseUrl && x.apiKey == y.apiKey && x.token == y.token &&
            x.agent == y.agent && x.note == y.note && x.website == y.website && x.extra.similar(y.extra)
    }
