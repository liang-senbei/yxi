package app.yxi.desktop

import kotlin.test.*

class ModelContextDeclarationTest {
    @Test fun `clearing an enabled model removes its declaration instead of saving a bare marker`() {
        assertEquals("", setOneM("", true))
        assertEquals("", setOneM(" [1M] ", true))
        assertEquals("glm-5.3[1m]", setOneM("glm-5.3[1M]", true))
        assertEquals("glm-5.3", setOneM("glm-5.3[1m]", false))
    }
    @Test fun `legacy duplicate suffix normalizes without changing meaningful model punctuation`() {
        assertEquals("provider/model:latest[1m]", setOneM(" provider/model:latest[1M][1m] ", true))
        assertEquals("provider/model:latest", oneMBase(" provider/model:latest[1M][1m] "))
        assertEquals("model[custom]", oneMBase("model[custom]"))
        assertTrue(hasOneM("glm-5.3[1M] "))
    }
}
