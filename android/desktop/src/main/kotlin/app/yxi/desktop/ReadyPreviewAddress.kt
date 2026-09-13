package app.yxi.desktop

/** Only auto-open the verified loopback listener. Preserve encoded paths and
 * queries; public/reverse-proxy addresses remain explicit preview actions. */
internal fun readyPreviewAddress(port: Int, saved: String?, host: String): String? {
    if (host !in listOf("127.0.0.1", "::1")) return null
    val uri = saved?.let { runCatching { java.net.URI(it) }.getOrNull() ?: return null }
    val savedHost = uri?.host?.removePrefix("[")?.removeSuffix("]")
    if (uri != null && (uri.scheme != "http" || (savedHost != "localhost" && savedHost != host) || uri.port != port)) return null
    return "http://${if (host == "::1") "[::1]" else host}:$port" + (uri?.rawPath?.ifEmpty { "/" } ?: "/") +
        (uri?.rawQuery?.let { "?$it" } ?: "") + (uri?.rawFragment?.let { "#$it" } ?: "")
}
