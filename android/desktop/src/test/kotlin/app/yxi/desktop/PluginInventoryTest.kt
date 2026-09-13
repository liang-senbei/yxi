package app.yxi.desktop

import kotlin.test.*

class PluginInventoryTest {
    @Test fun `missing output is not an empty installation list`() {
        assertFails { PluginInventory.parse("") }
        assertFails { PluginInventory.parse("permission denied") }
        assertTrue(PluginInventory.parse("__YXI_PLUGINS__:{\"runner\":\"claude\",\"plugins\":[],\"warnings\":[]}").plugins.isEmpty())
    }
    @Test fun `false and unknown enable states remain distinct`() {
        val data = PluginInventory.parse("""__YXI_PLUGINS__:{"runner":"claude","warnings":["partial"],"plugins":[{"id":"p@m","version":"1","scope":"project","project":"/work","path":"/installed","present":true,"userEnabled":false},{"id":"q@m","version":"","scope":"","project":"","path":"","present":false,"userEnabled":null}]}""")
        assertEquals(false, data.plugins[0].userEnabled)
        assertNull(data.plugins[1].userEnabled)
        assertEquals(listOf("partial"), data.warnings)
        assertEquals("/work", data.plugins[0].project)
    }
}
