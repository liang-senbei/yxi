package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenInTest {
    @TempDir lateinit var root: File

    private fun exe(dir: File, name: String) = File(dir.apply { mkdirs() }, name).apply { writeText("#!/bin/sh\n"); setExecutable(true) }

    @Test fun `finds known editors on the path in a fixed order, first directory wins, non-executables ignored`() {
        val a = File(root, "a"); val b = File(root, "b")
        exe(b, "zed"); exe(a, "code"); exe(b, "code")
        // Windows 没有可执行位（canExecute 对任何存在的文件都是 true），「没有可执行位的不算」只在别的系统上测（1.4.17 Windows CI 上发现）
        if (File.separatorChar != '\\') File(a.apply { mkdirs() }, "cursor").writeText("not executable")
        val found = OpenIn.find(listOf(a.path, "", b.path).joinToString(File.pathSeparator), windows = false)
        assertEquals(listOf("Visual Studio Code" to File(a, "code").path, "Zed" to File(b, "zed").path), found.map { it.label to it.command })
    }

    @Test fun `windows looks for cmd and exe launchers`() {
        val bin = File(root, "bin"); exe(bin, "code.cmd"); exe(bin, "idea.exe")
        assertEquals(listOf("Visual Studio Code", "IntelliJ IDEA"), OpenIn.find(bin.path, windows = true).map { it.label })
    }

    // 发版前检查 10-15：.cmd / .bat 经 cmd.exe 跑，目录名里有 cmd 符号的不交给它；Program Files (x86) 这类括号路径照常
    @Test fun `batch launchers refuse directories cmd would interpret`() {
        assertTrue(OpenIn.batchUnsafe("C:\\Users\\me\\AppData\\Local\\Programs\\Microsoft VS Code\\bin\\code.cmd", "C:\\work\\a&b"))
        assertTrue(OpenIn.batchUnsafe("C:\\tools\\cursor.CMD", "C:\\work\\100%done"))
        assertFalse(OpenIn.batchUnsafe("C:\\tools\\code.cmd", "C:\\Program Files (x86)\\demo app"))
        assertFalse(OpenIn.batchUnsafe("C:\\tools\\zed.exe", "C:\\work\\a&b"))
        assertFalse(OpenIn.batchUnsafe("/usr/local/bin/code", "/srv/a&b"))
        assertFailsWith<IllegalArgumentException> { OpenIn.open(EditorApp("Visual Studio Code", "C:\\tools\\code.cmd"), "C:\\work\\a&b") }
    }
}
