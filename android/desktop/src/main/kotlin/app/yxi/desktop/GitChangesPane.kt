package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.yxi.ssh.Shell
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun GitChangesPane(conn: Conn, directory: String, openFile: (String) -> Unit) {
    var snapshot by remember(conn, directory) { mutableStateOf<JSONObject?>(null) }
    var file by remember(conn, directory) { mutableStateOf("") }
    var staged by remember(conn, directory) { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(conn, directory, file, staged, revision) {
        busy = true; error = ""; snapshot = null
        try {
            val script = """
import json, pathlib, subprocess, sys
cwd, selected, staged = sys.argv[1:]
def git(*args):
    return subprocess.check_output(['git', '--no-pager', '-C', cwd, *args])
root = git('rev-parse', '--show-toplevel').decode().strip()
cwd = root
records = git('status', '--porcelain=v1', '-z', '--untracked-files=normal').split(b'\0')
files, index = [], 0
while index < len(records):
    record = records[index]; index += 1
    if not record: continue
    code, path = record[:2].decode(), record[3:].decode('utf-8', errors='replace')
    files.append({'code': code, 'path': path})
    if 'R' in code or 'C' in code: index += 1
text, clipped = '', False
entry = next((f for f in files if f['path'] == selected), None)
if entry:
    if entry['code'] == '??':
        text = '未跟踪文件或目录，请通过文件预览打开。'
    else:
        args = ['git', '--no-pager', '-C', cwd, 'diff', '--no-ext-diff', '--no-textconv', '--no-color']
        if staged == 'true': args.append('--cached')
        args += ['--', selected]
        process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            data = process.stdout.read(262145)
            clipped = len(data) > 262144
            if clipped: process.terminate()
            _, stderr = process.communicate(timeout=15)
            if not clipped and process.returncode: raise ValueError(stderr.decode(errors='replace')[:500])
            text = data[:262144].decode('utf-8', errors='replace')
        finally:
            if process.poll() is None: process.kill(); process.wait()
print('__YXI_GIT__:' + json.dumps({'root': root, 'files': files, 'diff': text, 'clipped': clipped}, ensure_ascii=False))
""".trimIndent()
            val raw = conn.ssh.exec("python3 -c " + Shell.q(script) + " " + Shell.q(directory) + " " + Shell.q(file) + " " + Shell.q(staged.toString()))
            val result = raw.lineSequence().lastOrNull { it.startsWith("__YXI_GIT__:") } ?: error("无法读取 Git 改动，请确认目录属于 Git 仓库")
            snapshot = JSONObject(result.removePrefix("__YXI_GIT__:"))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty(); snapshot = null }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("Git 改动", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton({ revision++ }, enabled = !busy) { Text("刷新") }
        }
        WorkbenchTabs(listOf("未暂存", "已暂存"), if (staged) "已暂存" else "未暂存", { staged = it == "已暂存" })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        snapshot?.let { data ->
            Text(data.getString("root"), style = MaterialTheme.typography.labelSmall, color = Tokens.current.textMuted)
            val files = data.getJSONArray("files")
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                if (files.length() == 0) Text("当前没有工作区改动", style = MaterialTheme.typography.bodySmall)
                for (index in 0 until files.length()) {
                    val entry = files.getJSONObject(index)
                    TextButton({ file = entry.getString("path") }) { Text("${entry.getString("code")}  ${entry.getString("path")}") }
                }
            }
            if (file.isNotBlank()) {
                TextButton({ openFile(data.getString("root").trimEnd('/') + "/" + file) }) { Text("打开文件 · $file") }
                if (data.optBoolean("clipped")) Text("差异较长，显示前 256 KiB", style = MaterialTheme.typography.labelSmall)
                val diff = data.getString("diff")
                val colors = Tokens.current
                val rendered = remember(diff, colors) {
                    buildAnnotatedString {
                        diff.lineSequence().forEach { line ->
                            val color = when {
                                line.startsWith("@@") -> colors.accent
                                line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff --git") || line.startsWith("index ") -> colors.textMuted
                                line.startsWith("+") -> colors.success
                                line.startsWith("-") -> colors.danger
                                else -> colors.textPrimary
                            }
                            withStyle(SpanStyle(color = color)) { append(line); append('\n') }
                        }
                    }
                }
                val added = diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
                val removed = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
                if (diff.isNotBlank()) Text("当前显示差异：+$added / −$removed" + if (data.optBoolean("clipped")) "（截取部分）" else "", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                SelectionContainer {
                    if (diff.isBlank()) Text("当前范围无差异", style = CodeStyle)
                    else Text(rendered, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), style = CodeStyle)
                }
            } else Text("选择文件查看差异", color = Tokens.current.textMuted)
        }
    }
}
