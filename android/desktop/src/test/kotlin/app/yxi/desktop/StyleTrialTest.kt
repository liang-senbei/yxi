package app.yxi.desktop

import kotlin.test.*

class StyleTrialTest {
    @Test fun `pixel values normalize and remain bounded`() {
        assertEquals("36px", StyleTrial.normalize("font-size", "36"))
        assertEquals("12.5px", StyleTrial.normalize("padding", "12.50px"))
        listOf("NaN", "Infinity", "10000", "-2", "calc(1px)", "12);alert(1)//").forEach { assertFails { StyleTrial.normalize("font-size", it) } }
    }
    @Test fun `only explicit color values and approved properties are allowed`() {
        assertEquals("#aabbcc", StyleTrial.normalize("color", "#AABBCC"))
        assertFails { StyleTrial.normalize("background-image", "url(https://example.com)") }
        assertFails { StyleTrial.normalize("color", "red;position:fixed") }
    }
    @Test fun `computed values seed controls without applying them`() {
        assertEquals("28", StyleTrial.initial("font-size", "28px"))
        assertEquals("#202536", StyleTrial.initial("color", "rgb(32, 37, 54)"))
    }
    @Test fun `script arguments remain JSON data`() {
        val script = StyleTrial.script("quote\"\nmarker", "operation", "apply", "font-size", "36")
        assertTrue(script.contains("quote\\\"\\nmarker"))
        assertFalse(script.contains("quote\"\nmarker"))
    }
    @Test fun `font choices are explicit and cannot inject additional styles`() {
        StyleTrial.fonts.keys.forEach { assertEquals(it, StyleTrial.normalize("font-family", it)) }
        assertFails { StyleTrial.normalize("font-family", "serif; color:red") }
        assertFails { StyleTrial.normalize("font-family", "url(https://example.com/font)") }
        assertEquals("", StyleTrial.initial("font-family", "Inter, Arial, sans-serif"))
        assertEquals("serif", StyleTrial.initial("font-family", "serif"))
    }
}
