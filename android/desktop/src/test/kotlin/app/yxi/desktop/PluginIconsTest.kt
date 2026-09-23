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
    @TempDir lateinit var root: File
    private fun plugin(name: String, website: String, logo: String? = null) = NativePlugin(name, name, name, "", "", "fixture", null,
        "", false, false, true, logo, "fixture", "", "fixture", websiteUrl = website)
    @Test fun `bundled publisher icons render without blocked CDN requests`() = runBlocking {
        val loader = PluginIconLoader(root) { error("Bundled official icon should not need network") }
        for ((name, website) in listOf("canva" to "https://www.canva.com", "gmail" to "https://workspace.google.com/products/gmail/",
            "github" to "https://github.com", "slack" to "https://slack.com", "dropbox" to "https://www.dropbox.com",
            "google-drive" to "https://drive.google.com", "notion" to "https://notion.so", "linear" to "https://linear.app")) {
            val bytes = assertNotNull(loader.load(plugin(name, website, "https://files.openai.com/unavailable"), false))
            assertTrue(bytes.size > 500)
            File("/results/plugin-$name.png").writeBytes(bytes)
            org.jetbrains.skia.Image.makeFromEncoded(bytes).use { assertEquals(64, it.width); assertEquals(64, it.height) }
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
