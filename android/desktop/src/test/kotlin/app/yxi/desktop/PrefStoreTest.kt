package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** M1：偏好读写、未知值回退、写盘失败不切换并说出原因（PRD §4.1 / §4.3 ③）。只碰临时目录，不碰真实 prefs.json。 */
class PrefStoreTest {
    private fun fixture(block: (dir: File, warnings: MutableList<String>) -> Unit) {
        val dir = Files.createTempDirectory("yxi-prefs-test").toFile()
        try { block(dir, mutableListOf()) } finally { dir.deleteRecursively() }
    }

    @Test fun `saved prefs survive a new store instance`() = fixture { dir, warnings ->
        val file = File(dir, "prefs.json")
        val store = PrefStore(file) { warnings += it }
        assertEquals("classic", store.get(UiStyle.PREF, "classic"))
        assertNull(store.set(UiStyle.PREF, "code"))
        assertNull(store.set("theme", "dark"))
        assertEquals("code", store.get(UiStyle.PREF, "classic"))
        val reopened = PrefStore(file) { warnings += it }
        assertEquals(UiStyle.Code, UiStyle.from(reopened.get(UiStyle.PREF, "classic")))
        assertEquals("dark", reopened.get("theme", "system"))
        assertEquals(emptyList(), warnings)
    }

    /** 老版本 / 手改写进去的怪值：原样读出来，由 UiStyle.from 回退经典，不报错也不改写文件。 */
    @Test fun `unknown stored style reads back as classic`() = fixture { dir, warnings ->
        val file = File(dir, "prefs.json")
        file.writeText("""{"uiStyle":"neon","theme":"light"}""")
        val store = PrefStore(file) { warnings += it }
        assertEquals("neon", store.get(UiStyle.PREF, "classic"))
        assertEquals(UiStyle.Classic, UiStyle.from(store.get(UiStyle.PREF, "classic")))
        assertEquals("light", store.get("theme", "system"))
        assertEquals("""{"uiStyle":"neon","theme":"light"}""", file.readText())
        assertEquals(emptyList(), warnings)
    }

    /** 写不进去：返回「界面风格没存上：原因」，内存里还是旧值（界面不切换）。 */
    @Test fun `write failure keeps the old value and explains why`() = fixture { dir, _ ->
        val blocker = File(dir, "not-a-dir").apply { writeText("x") }   // 父路径是个文件，建不了目录
        val store = PrefStore(File(blocker, "prefs.json"))
        val error = store.set(UiStyle.PREF, "code", "界面风格没存上")
        assertNotNull(error)
        assertTrue(error.startsWith("界面风格没存上："), error)
        assertTrue(error.length > "界面风格没存上：".length, "原因不能是空的：$error")
        assertEquals("classic", store.get(UiStyle.PREF, "classic"))
        assertTrue(store.set("theme", "dark")!!.startsWith("偏好未保存："))
        assertEquals("system", store.get("theme", "system"))
    }

    /** prefs.json 坏了且没有备份：读的时候报出来；之后写也拒绝，不拿新内容盖掉坏文件。 */
    @Test fun `unreadable prefs are reported and never overwritten`() = fixture { dir, warnings ->
        val file = File(dir, "prefs.json").apply { writeText("broken") }
        val store = PrefStore(file) { warnings += it }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().startsWith("无法读取桌面偏好："), warnings.single())
        assertEquals("classic", store.get(UiStyle.PREF, "classic"))
        assertNotNull(store.set(UiStyle.PREF, "code", "界面风格没存上"))
        assertEquals("classic", store.get(UiStyle.PREF, "classic"))
        assertEquals("broken", file.readText())
    }
}
