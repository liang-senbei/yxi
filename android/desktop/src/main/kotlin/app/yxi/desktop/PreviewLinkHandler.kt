package app.yxi.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** GFM emits bare www. links without a scheme; preserve other relative file links. */
internal fun normalizedWebLink(value: String): String {
    if (!value.startsWith("www.", ignoreCase = true)) return value
    val candidate = "https://$value"
    val parsed = runCatching { java.net.URI(candidate) }.getOrNull() ?: return value
    return if (parsed.userInfo == null && parsed.host?.split('.')?.let { it.size >= 3 && it.all(String::isNotBlank) } == true) candidate else value
}

/** Ordinary web links stay alongside the conversation; Ctrl opens the OS browser. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun previewWebLinks(state: AppState, conn: Conn, taskKey: String, fallback: UriHandler): UriHandler {
    val scope = rememberCoroutineScope()
    val window = LocalWindowInfo.current
    return object : UriHandler {
        override fun openUri(uri: String) {
            val scheme = runCatching { java.net.URI(uri).scheme?.lowercase() }.getOrNull()
            if (scheme !in listOf("http", "https") || window.keyboardModifiers.isCtrlPressed) {
                fallback.openUri(uri)
                return
            }
            val preview = state.browsers.getOrPut(taskKey) { BrowserPreview(conn.host, taskKey) }
            preview.preparing = true
            state.browserPanelOpen = true
            state.filePanelOpen = false
            scope.launch {
                try { preview.open(conn, uri) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { preview.error = "网页未能打开：${e.message.orEmpty()}" }
                finally { preview.preparing = false }
            }
        }
    }
}
