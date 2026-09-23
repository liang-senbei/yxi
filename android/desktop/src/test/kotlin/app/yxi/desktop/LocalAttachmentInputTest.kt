package app.yxi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalAttachmentInputTest {
    @Test fun `native images use an existing absolute host path`() {
        val file = Files.createTempFile("yxi-image-", ".png").toFile()
        try {
            val input = CodexAppServer.userInput("", listOf(InstructionAttachment("图片.png", file.absolutePath)), local = true)
            assertEquals("localImage", input.getJSONObject(0).getString("type"))
            assertEquals(file.absolutePath, input.getJSONObject(0).getString("path"))
        } finally { file.delete() }
    }

    @Test fun `local file references identify the local machine and reject missing files`() {
        val file = Files.createTempFile("yxi-document-", ".txt").toFile()
        try {
            val input = CodexAppServer.userInput("读取文件", listOf(InstructionAttachment("说明.txt", file.absolutePath)), local = true)
            assertTrue(input.getJSONObject(1).getString("text").contains("本机文件"))
            assertFailsWith<IllegalArgumentException> {
                CodexAppServer.userInput("", listOf(InstructionAttachment("x", file.parent)), local = true)
            }
        } finally { file.delete() }
        assertFailsWith<IllegalArgumentException> {
            CodexAppServer.userInput("", listOf(InstructionAttachment("x", file.absolutePath)), local = true)
        }
        assertFailsWith<IllegalArgumentException> {
            CodexAppServer.userInput("", listOf(InstructionAttachment("x", "relative.png")), local = true)
        }
    }

    @Test fun `remote files do not require a corresponding local file`() {
        val input = CodexAppServer.userInput("", listOf(InstructionAttachment("说明.txt", "/remote/upload/document.txt")))
        assertTrue(input.getJSONObject(0).getString("text").contains("服务器文件"))
    }
}
