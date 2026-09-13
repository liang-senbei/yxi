package app.yxi.desktop

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun runHostStoreNativeSmoke(reopen: Boolean) {
    check(System.getProperty("os.name").startsWith("Windows"))
    val root = File(System.getProperty("yxi.host.smokeRoot") ?: error("An isolated host fixture is required")).canonicalFile
    check(root.isDirectory)
    check(File(System.getProperty("user.home")).canonicalFile == root)
    check(File(System.getenv("APPDATA") ?: "").canonicalFile == File(root, "Roaming").canonicalFile)
    check(File(System.getenv("LOCALAPPDATA") ?: "").canonicalFile == File(root, "Local").canonicalFile)
    val roaming = File(root, "Roaming/Yxi")
    val local = File(root, "Local/Yxi")
    val marker = File(root, "host-store-fixture")
    if (!reopen) {
        check(!marker.exists())
        check(listOf(roaming, local).all { directory -> directory.listFiles().orEmpty().none { it.name.startsWith("hosts.json") } })
        roaming.mkdirs()
        val hosts = JSONArray((1..2).map { number -> JSONObject()
            .put("id", "host-fixture-$number").put("alias", "测试主机$number")
            .put("hostname", "fixture-$number.invalid").put("port", if (number == 1) 22 else 2222)
            .put("username", "fixture$number").put("password", "synthetic-host-$number").put("keyPath", "") })
        File(roaming, "hosts.json").writeText(hosts.toString())
        File(roaming, "hosts.json.bak").writeText(hosts.toString())
        marker.writeText("synthetic-host-store-v1")
    } else check(marker.readText() == "synthetic-host-store-v1")
    val loaded = Store.hosts()
    check(loaded.map { it.id } == listOf("host-fixture-1", "host-fixture-2"))
    loaded.forEachIndexed { index, host ->
        check(host.hostname == "fixture-${index + 1}.invalid")
        check(host.username == "fixture${index + 1}" && host.password == "synthetic-host-${index + 1}")
        check(host.port == if (index == 0) 22 else 2222)
    }
    check(File(roaming, "hosts.json").readText() == "[]")
    check(File(roaming, "hosts.json.bak").readText() == "[]")
    check(File(local, "hosts.json.protected").isFile)
    check(local.listFiles().orEmpty().filter { it.name.startsWith("hosts.json") }.none { it.readText().contains("synthetic-host-") })
    if (reopen) {
        check(loaded.first().alias == "已保存测试")
        check(Store.pref("lastHost", "") == "host-fixture-2")
        check(Store.pref("reconnectOnStart", "") == "0")
        println("host Store cross-process reopen ok")
    } else {
        Store.save(loaded.mapIndexed { index, host -> if (index == 0) host.copy(alias = "已保存测试") else host })
        Store.setPref("lastHost", "host-fixture-2")
        Store.setPref("reconnectOnStart", "0")
        println("host Store migration and save ok")
    }
}
