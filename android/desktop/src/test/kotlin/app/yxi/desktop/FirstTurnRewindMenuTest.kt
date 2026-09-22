package app.yxi.desktop

import kotlin.test.*

class FirstTurnRewindMenuTest {
    private val menu = """
        Rewind

        Restore the code and/or conversation to the point before…
          duplicate
          ⚠ No code restore
        ❯ duplicate
          No code changes
          (current)
        Enter to continue · Esc to cancel
    """.trimIndent()
    @Test fun `duplicate text keeps its selected position and requires every row to match`() {
        val view = assertNotNull(FirstTurnRewindMenu.parse(menu, FirstTurnRewindMenu.VERSION))
        assertEquals(1, view.selectedIndex)
        assertTrue(view.matches(listOf("duplicate", "duplicate")))
        assertFalse(view.matches(listOf("duplicate")))
        assertFalse(view.matches(listOf("different", "duplicate")))
        assertFalse(view.currentSelected)
    }
    @Test fun `unknown scrolling partial layout and multiple selections are rejected`() {
        assertNull(FirstTurnRewindMenu.parse(menu, "unknown"))
        assertNull(FirstTurnRewindMenu.parse(menu.replace("  (current)", "❯ (current)"), FirstTurnRewindMenu.VERSION))
        assertNull(FirstTurnRewindMenu.parse(menu.replace("No code changes", "↑ 3 more above"), FirstTurnRewindMenu.VERSION))
        assertNull(FirstTurnRewindMenu.parse(menu.replace("(current)", "missing"), FirstTurnRewindMenu.VERSION))
        assertNull(FirstTurnRewindMenu.parse(menu.replace("⚠ No code restore", "unexpected metadata"), FirstTurnRewindMenu.VERSION))
    }
    @Test fun `confirmation must explicitly leave code unchanged and select conversation only`() {
        val confirm = """
            Confirm you want to restore the conversation to the point before you sent this message:
            │ duplicate
            │ (21s ago)
            The conversation will be forked.
            The code will be unchanged.
            ❯ 1. Restore conversation
              2. Summarize from here
              3. Summarize up to here
              4. Never mind
        """.trimIndent()
        assertTrue(FirstTurnRewindMenu.confirmsConversationOnly(confirm, FirstTurnRewindMenu.VERSION, "duplicate"))
        assertFalse(FirstTurnRewindMenu.confirmsConversationOnly(confirm, FirstTurnRewindMenu.VERSION, "other"))
        assertFalse(FirstTurnRewindMenu.confirmsConversationOnly(confirm.replace("Restore conversation", "Restore code and conversation"), FirstTurnRewindMenu.VERSION, "duplicate"))
        assertFalse(FirstTurnRewindMenu.confirmsConversationOnly(confirm.replace("unchanged", "restored"), FirstTurnRewindMenu.VERSION, "duplicate"))
    }
}
