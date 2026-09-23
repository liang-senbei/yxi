package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import kotlin.test.*

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class PluginIconsTest {
    @Test fun `unavailable publisher icons back off briefly and recover without restarting`(): Unit = runBlocking {
        var clock = 1000L; var requests = 0; var available = false
        val loader = PluginIconLoader(root, now = { clock }) {
            requests++
            if (!available) error("offline")
            "<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64'><rect width='64' height='64' fill='#0055ff'/></svg>".toByteArray()
        }
        val entry = plugin("fixture", "https://publisher.example", "https://publisher.example/icon.svg")
        assertNull(loader.load(entry, false))
        val initial = requests
        assertTrue(initial > 0)
        repeat(10) { assertNull(loader.load(entry, false)) }
        assertEquals(initial, requests)
        available = true; clock += 60_001
        assertNotNull(loader.load(entry, false))
        assertEquals(initial + 1, requests)
    }
    @Test fun `cancelled icon requests do not retry publisher fallbacks`(): Unit = runBlocking {
        var requests = 0
        val loader = PluginIconLoader(root) { requests++; throw kotlinx.coroutines.CancellationException("cancelled") }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            loader.load(plugin("fixture", "https://publisher.example", "https://publisher.example/icon.svg"), false)
        }
        assertEquals(1, requests)
    }
    @Test fun `SVG intrinsic dimensions are scaled into the icon viewport without clipping`() {
        fun pixels(svg: String) = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(PluginIconLoader.render(svg.toByteArray())))
        for (size in listOf(34, 192)) {
            val image = pixels("""<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size"><rect width="$size" height="$size" fill="#0055ff"/></svg>""")
            assertEquals(0xff0055ff.toInt(), image.getRGB(62, 62))
        }
        val image = pixels("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"><rect width="200" height="100" fill="#0055ff"/></svg>""")
        assertEquals(0, image.getRGB(32, 1) ushr 24)
        assertEquals(0xff0055ff.toInt(), image.getRGB(32, 32))
    }
    @TempDir lateinit var root: File
    private fun plugin(name: String, website: String, logo: String? = null) = NativePlugin(name, name, name, "", "", "fixture", null,
        "", false, false, true, logo, "fixture", "", "fixture", websiteUrl = website)
    @Test fun `bundled publisher icons render without blocked CDN requests`() = runBlocking {
        val loader = PluginIconLoader(root) { error("Bundled official icon should not need network") }
        for ((name, website) in listOf("canva" to "https://www.canva.com", "gmail" to "https://workspace.google.com/products/gmail/",
            "github" to "https://github.com", "slack" to "https://slack.com", "dropbox" to "https://www.dropbox.com",
            "google-drive" to "https://drive.google.com", "notion" to "https://notion.so", "linear" to "https://linear.app",
            "figma" to "https://www.figma.com", "stripe" to "https://stripe.com", "vercel" to "https://vercel.com",
            "supabase" to "https://supabase.com", "google-calendar" to "https://calendar.google.com",
            "outlook-email" to "https://outlook.com", "outlook-calendar" to "https://www.microsoft.com/microsoft-365/outlook")) {
            val bytes = assertNotNull(loader.load(plugin(name, website, "https://files.openai.com/unavailable"), false))
            val pixels = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            val visible = (0 until pixels.height).flatMap { y -> (0 until pixels.width).map { x -> pixels.getRGB(x, y) } }.filter { it ushr 24 > 0 }
            assertTrue(visible.size > 64, "$name must contain visible artwork")
            assertTrue(visible.distinct().size > 1, "$name must not render as a solid empty tile")
            File("/results/plugin-$name.png").writeBytes(bytes)
            org.jetbrains.skia.Image.makeFromEncoded(bytes).use { assertEquals(64, it.width); assertEquals(64, it.height) }
        }
    }
    @Test fun `matching names on unrelated domains never receive bundled brand icons`(): Unit = runBlocking {
        val loader = PluginIconLoader(root) { error("unavailable") }
        for (name in listOf("figma", "stripe", "vercel", "supabase", "google-calendar", "outlook-email")) {
            assertNull(loader.load(plugin(name, "https://unrelated.example"), false))
        }
    }
    @Test fun `blocked catalog logo falls back to the publisher declared icon and then caches it`() = runBlocking {
        val seen = mutableListOf<String>()
        val loader = PluginIconLoader(root) { url ->
            seen += url
            when (url) {
                "https://catalog.example/logo" -> error("HTTP 403")
                "https://publisher.example/app" -> "<html><link rel='apple-touch-icon' href='/official.svg'></html>".toByteArray()
                "https://publisher.example/official.svg" -> "<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64'><rect width='64' height='64' fill='#0055ff'/></svg>".toByteArray()
                else -> error("Unexpected request")
            }
        }
        val p = plugin("fixture", "https://publisher.example/app", "https://catalog.example/logo")
        val first = assertNotNull(loader.load(p, false))
        val count = seen.size
        assertContentEquals(first, loader.load(p, false))
        assertEquals(count, seen.size, "Cached icon must avoid repeated publisher requests")
        assertEquals(listOf("https://catalog.example/logo", "https://publisher.example/app", "https://publisher.example/official.svg"), seen)
    }
    @Test fun `website icons resolve relative URLs while unsafe schemes and external SVG resources are rejected`() {
        val icons = PluginIconLoader.websiteIcons(URI("https://publisher.example/app/"), """<link rel="icon" href="./logo.png"><link rel="apple-touch-icon" href="/touch.png"><link rel="icon" href="file:///secret">""")
        assertEquals(listOf("https://publisher.example/touch.png", "https://publisher.example/app/logo.png"), icons)
        assertFalse(PluginIconLoader.hostMatches("https://not-canva.com", "canva.com"))
        assertFailsWith<IllegalArgumentException> { PluginIconLoader.checkedUrl("http://publisher.example/logo") }
        assertFailsWith<IllegalArgumentException> { PluginIconLoader.render("<svg xmlns='http://www.w3.org/2000/svg'><image href='file:///secret'/></svg>".toByteArray()) }
    }
}
