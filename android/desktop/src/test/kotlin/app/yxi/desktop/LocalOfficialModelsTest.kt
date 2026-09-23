package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.*

class LocalOfficialModelsTest {
    @Test fun `model discovery follows native pages and excludes hidden entries`() = runBlocking {
        val seen = mutableListOf<String?>()
        val models = LocalOfficialModels.load { cursor ->
            seen.add(cursor)
            JSONObject(if (cursor == null) """{"result":{"data":[{"model":"native-a","isDefault":true},{"model":"hidden","hidden":true}],"nextCursor":"p2"}}"""
                else """{"result":{"data":[{"model":"native-b","displayName":"Native B"}],"nextCursor":null}}""")
        }
        assertEquals(listOf(null, "p2"), seen)
        assertEquals(listOf("native-a", "native-b"), models.map { it.id })
        assertTrue(models.first().isDefault)
    }
    @Test fun `empty or cyclic model catalog fails instead of inventing a default`() = runBlocking {
        assertFailsWith<IllegalStateException> { LocalOfficialModels.load { JSONObject("""{"result":{"data":[],"nextCursor":null}}""") } }
        assertFailsWith<IllegalStateException> { LocalOfficialModels.load { JSONObject("""{"result":{"data":[],"nextCursor":"same"}}""") } }
    }
}
