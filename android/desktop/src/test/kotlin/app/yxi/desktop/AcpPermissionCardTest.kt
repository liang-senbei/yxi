package app.yxi.desktop

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class AcpPermissionCardTest {
    @Test fun `native repeated labels retain distinct scopes and exact response ids`() {
        val params = JSONObject().put("options", JSONArray().put(JSONObject().put("optionId", "one").put("name", "Allow").put("kind", "allow_once"))
            .put(JSONObject().put("optionId", "persist").put("name", "Allow").put("kind", "allow_always")))
        val choices = acpPermissionChoices(params)
        assertEquals(listOf("one", "persist"), choices.map { it.id })
        assertEquals(listOf("仅本次允许", "持续允许"), choices.map { it.scope })
        params.getJSONArray("options").getJSONObject(1).put("optionId", "one")
        assertFailsWith<IllegalArgumentException> { acpPermissionChoices(params) }
        params.getJSONArray("options").getJSONObject(1).put("optionId", "persist").put("kind", "unknown")
        assertFailsWith<IllegalStateException> { acpPermissionChoices(params) }
    }
}
