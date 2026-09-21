package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

internal object ReleaseNotes {
    data class Section(val title: String, val items: List<String>)
    data class Entry(val version: String, val date: String, val title: String, val sections: List<Section>)
    fun parse(raw: String): List<Entry> {
        val releases = JSONObject(raw).getJSONArray("releases")
        require(releases.length() <= 100)
        return (0 until releases.length()).map { i ->
            val r = releases.getJSONObject(i)
            val sections = r.getJSONArray("sections")
            Entry(r.getString("version"), r.getString("date"), r.getString("title"),
                (0 until sections.length()).map { n ->
                    val s = sections.getJSONObject(n); val items = s.getJSONArray("items")
                    Section(s.getString("title"), (0 until items.length()).map(items::getString))
                })
        }
    }
    fun bundled() = parse(ReleaseNotes::class.java.getResource("/app/yxi/desktop/release-notes.json")!!.readText())
    fun latest(): List<Entry> {
        val connection = URI(Updater.FEED + "release-notes.json").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000; connection.readTimeout = 8000
        return try {
            check(connection.responseCode == 200)
            val bytes = connection.inputStream.use { it.readNBytes(256 * 1024 + 1) }
            require(bytes.size <= 256 * 1024)
            parse(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

@Composable
internal fun ReleaseNotesDialog(version: String? = null, close: () -> Unit) {
    var entries by remember { mutableStateOf(ReleaseNotes.bundled()) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { entries = withContext(Dispatchers.IO) { ReleaseNotes.latest() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { offline = true }
        finally { loading = false }
    }
    WorkbenchDialog(onDismissRequest = close, title = { Text(if (version == null) "版本更新日志" else "v$version 更新日志") }, text = {
        Column(Modifier.widthIn(max = 620.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (offline) Text("暂时无法获取在线日志，以下为随应用提供的版本说明。", color = Tokens.current.textMuted, style = MaterialTheme.typography.bodySmall)
            val visible = entries.filter { version == null || it.version == version }
            if (!loading && visible.isEmpty()) Text("此版本的更新说明暂未提供。")
            visible.forEach { entry ->
                Text("v${entry.version} · ${entry.title}", style = MaterialTheme.typography.titleLarge)
                Text(entry.date, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted)
                HorizontalDivider()
                entry.sections.forEach { section ->
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    section.items.forEach { text ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("•", color = Tokens.current.textMuted)
                            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}
