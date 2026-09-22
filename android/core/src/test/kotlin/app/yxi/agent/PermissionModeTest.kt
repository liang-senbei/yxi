package app.yxi.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PermissionModeTest {
    @Test fun `mode comes from native footer not conversation text`() {
        assertEquals(PermissionMode.Manual, PermissionMode.fromScreen("bypass permissions on\n❯ \n────\n⏸ manual mode on"))
        assertEquals(PermissionMode.Bypass, PermissionMode.fromScreen("❯ \n────\n⏵⏵ bypass permissions\non (shift+tab to cycle)"))
        assertNull(PermissionMode.fromScreen("bypass permissions on\n❯ "))
        assertNull(PermissionMode.fromScreen("❯ 1. Yes\nEsc to cancel"))
        assertNull(PermissionMode.fromScreen("❯ \nmanual mode on auto mode on"))
    }
}
