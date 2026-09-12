package app.yxi.desktop

import java.net.URI

data class PreviewAddress(val uri: URI, val remotePort: Int?) {
    val url get() = uri.toASCIIString()
    val remoteHost get() = uri.host.removePrefix("[").removeSuffix("]")
    fun forwarded(localPort: Int): String = "${uri.scheme}://127.0.0.1:$localPort" + suffix(uri)
    fun logical(actual: String, localPort: Int?): String {
        val current = runCatching { URI(actual) }.getOrNull() ?: return actual
        return if (remotePort != null && localPort != null && current.host == "127.0.0.1" && current.port == localPort)
            "${current.scheme}://${if (remoteHost.contains(':')) "[$remoteHost]" else remoteHost}:$remotePort" + suffix(current) else actual
    }
    companion object {
        fun parse(input: String): PreviewAddress {
            val value = input.trim()
            val prepared = when {
                value.toIntOrNull() != null -> "http://localhost:$value/"
                !value.contains("://") && (value.startsWith("localhost:") || value.startsWith("127.") || value.startsWith("[::1]:")) -> "http://$value"
                else -> value
            }
            val uri = URI(prepared)
            require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()) { "请输入 HTTP/HTTPS 地址，或远端开发端口" }
            require(uri.userInfo == null) { "地址不能包含用户名或密码" }
            require(uri.port == -1 || uri.port in 1..65535) { "端口必须为1–65535" }
            return PreviewAddress(uri, if (loopback(uri.host)) if (uri.port == -1) { if (uri.scheme.equals("https", true)) 443 else 80 } else uri.port else null)
        }
        private fun suffix(uri: URI) = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        fun loopback(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            if (h in listOf("localhost", "::1", "[::1]")) return true
            val parts = h.split('.')
            return parts.size == 4 && parts[0] == "127" && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        fun allowed(url: String, fixture: Boolean = false, resource: Boolean = false): Boolean {
            if (url == "about:blank") return true
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (resource && uri.scheme in listOf("data", "blob")) return true
            return uri.scheme?.lowercase() in (if (resource) listOf("http", "https", "ws", "wss") else listOf("http", "https")) &&
                uri.userInfo == null && uri.host != null && (!fixture || loopback(uri.host))
        }
    }
}
