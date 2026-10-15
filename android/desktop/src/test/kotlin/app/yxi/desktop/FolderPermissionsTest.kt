package app.yxi.desktop

import app.yxi.agent.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderPermissionsTest {
    @Test fun `remembers the last mode per host and folder, ignoring a trailing slash`() {
        var raw = "[]"
        raw = FolderPermissions.remember(raw, "h1", "/srv/app", PermissionMode.Auto)!!
        raw = FolderPermissions.remember(raw, "h1", "/srv/docs", PermissionMode.Edits)!!
        assertEquals(PermissionMode.Auto, FolderPermissions.recall(raw, "h1", "/srv/app/"))
        assertEquals(PermissionMode.Edits, FolderPermissions.recall(raw, "h1", "/srv/docs"))
        assertNull(FolderPermissions.recall(raw, "h2", "/srv/app"))
        raw = FolderPermissions.remember(raw, "h1", "/srv/app", PermissionMode.Manual)!!
        assertEquals(PermissionMode.Manual, FolderPermissions.recall(raw, "h1", "/srv/app"))
    }

    @Test fun `plan and bypass are never remembered and keep the previous choice`() {
        val raw = FolderPermissions.remember("[]", "h1", "/srv/app", PermissionMode.Edits)!!
        assertNull(FolderPermissions.remember(raw, "h1", "/srv/app", PermissionMode.Plan))
        assertNull(FolderPermissions.remember(raw, "h1", "/srv/app", PermissionMode.Bypass))
        assertEquals(PermissionMode.Edits, FolderPermissions.recall(raw, "h1", "/srv/app"))
        assertNull(FolderPermissions.remember(raw, "h1", "/srv/app", PermissionMode.Edits))   // 没变不写
    }

    @Test fun `keeps at most MAX folders, dropping the oldest, and survives garbage`() {
        var raw = "[]"
        for (i in 0..FolderPermissions.MAX) raw = FolderPermissions.remember(raw, "h", "/d$i", PermissionMode.Auto)!!
        assertNull(FolderPermissions.recall(raw, "h", "/d0"))
        assertEquals(PermissionMode.Auto, FolderPermissions.recall(raw, "h", "/d${FolderPermissions.MAX}"))
        assertNull(FolderPermissions.recall("not json", "h", "/d1"))
        assertEquals(PermissionMode.Auto, FolderPermissions.recall(FolderPermissions.remember("not json", "h", "/x", PermissionMode.Auto)!!, "h", "/x"))
    }
}
