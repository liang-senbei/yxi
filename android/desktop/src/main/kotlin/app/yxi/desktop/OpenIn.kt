package app.yxi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 本机装着的一个编辑器（M4m）：[command] 是可执行文件的完整路径，打开时后面接目录。 */
internal data class EditorApp(val label: String, val command: String)

/**
 * 「在编辑器中打开」（M4m，规格表 §3.9 标题菜单的「打开方式 >」、PRD §6「在 IDE 中继续」）：只给本机会话，打开的是会话记录里的目录
 * （你建会话时选的，不是模型给的路径，所以不违反「模型给的路径不调 Desktop.open」）。编辑器在登录 shell 的 PATH 里找
 * （code / cursor / idea / zed / subl，Windows 上带 .cmd / .exe）；另加「在访达 / 资源管理器中显示」。
 * [detect]、[launch] 可替换：截图夹具不许起进程，编辑器列表也不能跟着这台机器装了什么变。
 */
internal object OpenIn {
    private val known = listOf("code" to "Visual Studio Code", "cursor" to "Cursor", "idea" to "IntelliJ IDEA", "zed" to "Zed", "subl" to "Sublime Text")
    internal var detect: () -> List<EditorApp> = { find(LoginShellPath.await()) }
    internal var launch: (List<String>) -> Unit = { command ->
        ProcessBuilder(command).apply { LoginShellPath.applyTo(environment()) }.redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(File(if (File.separatorChar == '\\') "NUL" else "/dev/null"))).start()
    }

    /** 在 [path]（PATH 那种用分隔符连起来的目录表）里找认得的编辑器，按 [known] 的顺序、每种一个。 */
    internal fun find(path: String, windows: Boolean = System.getProperty("os.name").startsWith("Windows")): List<EditorApp> {
        val dirs = path.split(File.pathSeparatorChar).filter { it.isNotBlank() }
        return known.mapNotNull { (name, label) ->
            dirs.asSequence().flatMap { dir -> (if (windows) listOf("$name.cmd", "$name.exe") else listOf(name)).asSequence().map { File(dir, it) } }
                .firstOrNull { it.isFile && it.canExecute() }?.let { EditorApp(label, it.path) }
        }
    }

    fun open(editor: EditorApp, directory: String) = launch(listOf(editor.command, directory))

    /** 在系统的文件管理器里显示这个目录（目录是你选的，同上）。 */
    fun reveal(directory: String) {
        require(File(directory).isDirectory) { "目录不存在：$directory" }
        java.awt.Desktop.getDesktop().open(File(directory))
    }

    val revealLabel: String get() = if (System.getProperty("os.name").startsWith("Mac")) "在访达中显示" else "在资源管理器中显示"
}

/** 本机 Code 页会话头的「打开方式」按钮（M4m）：点开列出找到的编辑器（第一次点时才去找）和「在访达 / 资源管理器中显示」，同 Code 的其他浮层。 */
@Composable
internal fun CodeOpenInButton(directory: String) {
    var open by remember { mutableStateOf(false) }
    var editors by remember { mutableStateOf<List<EditorApp>?>(null) }
    var note by remember(directory) { mutableStateOf("") }
    NativeOverlay(open)
    LaunchedEffect(open) { if (open && editors == null) editors = withContext(Dispatchers.IO) { runCatching { OpenIn.detect() }.getOrDefault(emptyList()) } }
    Box {
        CodeIconButton(Icons.Outlined.Code, "打开方式", held = open) { open = true }
        if (open) CodePopup({ open = false }, alignEnd = true, gap = 6.4.dp, width = 240.dp) {
            CodeMenuCaption("打开方式")
            val found = editors
            if (found == null) CodeMenuNote("正在查找本机的编辑器…")
            else if (found.isEmpty()) CodeMenuNote("没找到 code / cursor / idea / zed / subl 命令")
            found.orEmpty().forEach { editor ->
                CodeMenuItem(editor.label, { open = false; runCatching { OpenIn.open(editor, directory) }.onFailure { note = "打不开 ${editor.label}：${it.message.orEmpty()}" } })
            }
            CodeMenuDivider()
            CodeMenuItem(OpenIn.revealLabel, { open = false; runCatching { OpenIn.reveal(directory) }.onFailure { note = it.message.orEmpty() } })
            if (note.isNotBlank()) CodeMenuNote(note)
        }
    }
}
