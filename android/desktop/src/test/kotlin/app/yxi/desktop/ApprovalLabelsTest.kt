package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalLabelsTest {
    @Test fun `four native choices preserve distinct consent scopes`() {
        assertEquals("允许一次", zhOption("Yes"))
        assertEquals("不再询问", zhOption("Yes, and don’t ask again for git:*"))
        assertEquals("切换自动审批", zhOption("Yes, and switch to auto mode · auto mode handles these prompts for you"))
        assertEquals("拒绝", zhOption("No"))
    }

    @Test fun `unknown extended consent is never mislabeled as one time`() {
        val unknown = "Yes, and enable a future permission policy"
        assertEquals(unknown, zhOption(unknown))
        assertEquals("本会话允许", zhOption("Yes, allow this session"))
        assertEquals("不再询问", zhOption("Yes, and don't ask again for this command"))
    }
}
