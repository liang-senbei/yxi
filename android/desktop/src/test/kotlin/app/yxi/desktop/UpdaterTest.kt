package app.yxi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 挑更新的纯逻辑：feed 是 Velopack 真实产出的 releases.win.json 片段（字段名 / Type 值照抄）。 */
class UpdaterTest {
    private val feed = """{"Assets":[
        {"PackageId":"Yxi","Version":"1.0.9","Type":"Delta","FileName":"Yxi-1.0.9-delta.nupkg","SHA1":"A4","SHA256":"BB","Size":1631558},
        {"PackageId":"Yxi","Version":"1.0.6","Type":"Full","FileName":"Yxi-1.0.6-full.nupkg","SHA1":"51","SHA256":"F3","Size":19649212},
        {"PackageId":"Yxi","Version":"1.0.10","Type":"Full","FileName":"Yxi-1.0.10-full.nupkg","SHA1":"0D","SHA256":"1C","Size":19719727}
    ]}"""

    @Test fun picksHighestFullNotDelta() = assertEquals("Yxi-1.0.10-full.nupkg", Updater.pickUpdate(feed, "1.0.9")?.getString("FileName"))
    @Test fun numericCompare() = assertEquals("1.0.10", Updater.pickUpdate(feed, "1.0.2")?.getString("Version"))   // 不是字符串比：1.0.10 > 1.0.2
    @Test fun sameOrNewerIsNull() { assertNull(Updater.pickUpdate(feed, "1.0.10")); assertNull(Updater.pickUpdate(feed, "1.1.0")); assertNull(Updater.pickUpdate(feed, "2.0.0")) }
    @Test fun cmpVer() { assert(Updater.cmpVer("1.0.10", "1.0.9") > 0); assert(Updater.cmpVer("1.0", "1.0.0") == 0); assert(Updater.cmpVer("1.0.1-beta", "1.0.1") == 0) }
    @Test fun emptyFeed() = assertNull(Updater.pickUpdate("""{"Assets":[]}""", "1.0.0"))
    @Test fun downloadedOlderPackageDoesNotHideNewRelease() {
        assertEquals("1.0.10", Updater.pickUpdate(feed, "1.0.2", "1.0.6")?.getString("Version"))
        assertNull(Updater.pickUpdate(feed, "1.0.2", "1.0.10"))
        assertNull(Updater.pickUpdate(feed, "1.0.2", "1.1.0"))
    }
    @Test fun foreignPackageDoesNotOverrideYxi() {
        val mixed = org.json.JSONObject(feed)
        mixed.getJSONArray("Assets").put(org.json.JSONObject("""{"PackageId":"Other","Version":"99.0.0","Type":"Full"}"""))
        assertEquals("1.0.10", Updater.pickUpdate(mixed.toString(), "1.0.2", "1.0.6")?.getString("Version"))
    }
}
