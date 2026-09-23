package app.yxi.desktop

import org.json.JSONObject
import kotlin.test.*

class NativePluginCatalogTest {
    @Test fun `recovered installation ledger remains blocked after another restart`() {
        val root = java.nio.file.Files.createTempDirectory("native-plugin-ledger-").toFile()
        try {
            val file = root.resolve("ledger.json")
            file.writeText("broken")
            root.resolve("ledger.json.bak").writeText("""{"version":1,"pendingId":"sample@fixture"}""")
            assertTrue(NativePluginStore(null, file).error.isNotBlank())
            assertTrue(NativePluginStore(null, file).error.isNotBlank())
            assertTrue(root.resolve("ledger.json.needs-review").isFile)
        } finally { root.deleteRecursively() }
    }
    @Test fun `catalog preserves machine-local install state and policy`() {
        val raw = JSONObject("""{"marketplaces":[{"name":"fixture","path":"/tmp/market/.agents/plugins/marketplace.json","plugins":[
            {"id":"gmail@fixture","name":"gmail","installed":false,"enabled":false,"installPolicy":"AVAILABLE","authPolicy":"ON_INSTALL","source":{"type":"remote"},"interface":{"displayName":"Gmail","shortDescription":"Mail","category":"Communication","logoUrl":"https://example.org/logo.png","websiteUrl":"https://workspace.google.com/products/gmail/","logoUrlDark":"https://example.org/dark.svg","composerIconUrl":"https://example.org/composer.png"}},
            {"id":"blocked@fixture","name":"blocked","installed":false,"enabled":false,"installPolicy":"AVAILABLE","availability":"DISABLED_BY_ADMIN","source":{"type":"remote"}},
            {"id":"interstitial@fixture","name":"interstitial","installed":false,"enabled":false,"installPolicy":"AVAILABLE","mustShowInstallationInterstitial":true,"source":{"type":"remote"}},
            {"id":"sample@fixture","name":"sample","installed":true,"enabled":false,"installPolicy":"AVAILABLE","source":{"type":"local","path":"/tmp/sample"}}
        ]}],"marketplaceLoadErrors":[{"message":"unavailable"}]}""")
        val snapshot = NativePluginSnapshot.parse(raw)
        assertEquals(1, snapshot.errors)
        assertEquals("Gmail", snapshot.entries[0].title)
        assertEquals("https://workspace.google.com/products/gmail/", snapshot.entries[0].websiteUrl)
        assertEquals("https://example.org/dark.svg", snapshot.entries[0].iconUrlDark)
        assertEquals("https://example.org/composer.png", snapshot.entries[0].composerIconUrl)
        assertTrue(snapshot.entries[0].installable)
        assertFalse(snapshot.entries[1].installable)
        assertFalse(snapshot.entries[2].installable)
        assertTrue(snapshot.entries[3].installed)
        assertFalse(snapshot.entries[3].enabled)
        assertEquals("/tmp/market/.agents/plugins/marketplace.json", snapshot.entries[0].installParams().getString("marketplacePath"))
        assertFalse(snapshot.entries[0].installParams().has("remoteMarketplaceName"))
    }
}
