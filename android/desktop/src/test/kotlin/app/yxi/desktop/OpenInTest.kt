package app.yxi.desktop

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenInTest {
    @TempDir lateinit var root: File

    private fun exe(dir: File, name: String) = File(dir.apply { mkdirs() }, name).apply { writeText("#!/bin/sh\n"); setExecutable(true) }

    @Test fun `finds known editors on the path in a fixed order, first directory wins, non-executables ignored`() {
        val a = File(root, "a"); val b = File(root, "b")
        exe(b, "zed"); exe(a, "code"); exe(b, "code"); File(a.apply { mkdirs() }, "cursor").writeText("not executable")
        val found = OpenIn.find(listOf(a.path, "", b.path).joinToString(File.pathSeparator), windows = false)
        assertEquals(listOf("Visual Studio Code" to File(a, "code").path, "Zed" to File(b, "zed").path), found.map { it.label to it.command })
    }

    @Test fun `windows looks for cmd and exe launchers`() {
        val bin = File(root, "bin"); exe(bin, "code.cmd"); exe(bin, "idea.exe")
        assertEquals(listOf("Visual Studio Code", "IntelliJ IDEA"), OpenIn.find(bin.path, windows = true).map { it.label })
    }
}
