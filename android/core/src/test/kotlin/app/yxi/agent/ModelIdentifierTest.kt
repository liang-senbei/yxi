package app.yxi.agent

import kotlin.test.*

class ModelIdentifierTest {
    @Test fun `provider qualified models remain selectable without allowing shell syntax`() {
        listOf("glm-5.3", "provider/real-model", "vendor/model:free", "model[1m]").forEach { assertTrue(Model.selectableId(it), it) }
        listOf("", "model name", "x'; touch /tmp/x", "\$(id)", "a\nb", "x;id", "x".repeat(513)).forEach { assertFalse(Model.selectableId(it)) }
    }
}
