package app.yxi.desktop

import kotlin.test.*

class PreviewServicePlanTest {
    private val plan = PreviewServicePlan("a".repeat(64), "/work/project", "npm run dev", 3000)
    @Test fun `configuration changes have distinct ownership signatures`() {
        assertNotEquals(plan.signature, plan.copy(command = "npm run other").signature)
        assertNotEquals(plan.signature, plan.copy(directory = "/other/project").signature)
        assertNotEquals(plan.signature, plan.copy(port = 4000).signature)
        assertEquals(plan.signature, plan.copy().signature)
    }
    @Test fun `invalid requests cannot become server commands`() {
        assertFails { plan.copy(directory = "relative") }
        assertFails { plan.copy(port = 0) }
        assertFails { plan.copy(command = "") }
        assertFails { plan.stopCommand("other; kill-server") }
    }
    @Test fun `missing or malformed receipts never imply a running service`() {
        assertFails { PreviewServicePlan.result("") }
        assertFails { PreviewServicePlan.result("__YXI_PREVIEW__:{\"state\":\"running\"}") }
        assertEquals("missing", PreviewServicePlan.result("__YXI_PREVIEW__:{\"state\":\"missing\"}").getString("state"))
    }
}
