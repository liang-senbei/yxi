package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodePanelWidthTest {
    @Test fun `the card keeps the spec width when there is room and shrinks before covering the conversation`() {
        assertEquals(1040f, CodePanelWidth.fit(CodePanelWidth.WIDE, 1760f))                 // 2048 宽、侧栏开着：同规格
        assertEquals(1248f - 8f - 360f, CodePanelWidth.fit(CodePanelWidth.WIDE, 1248f))    // 1920×1080 @125%、侧栏开着：缩到 880，对话还在
        assertNull(CodePanelWidth.fit(CodePanelWidth.WIDE, 700f))                           // 再窄就铺满主区
        assertEquals(578.4f, CodePanelWidth.fit(CodePanelWidth.TERMINAL, 1248f))            // 终端本来就窄，不用缩
        assertEquals(440f, CodePanelWidth.fit(CodePanelWidth.TERMINAL, 808f))
    }
}
