package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Copy user files out of the installer-owned tree. Never delete or replace a source. */
internal object UserDataMigration {
    private val files = setOf("hosts.json", "auth.json", "prefs.json", "window.json", "known_hosts",
        "workspace.json", "project-previews.json", "project-services.json", "plugin-operations.json",
        "instructions.json", "model-changes.json", "codex-tasks.json", "support-drafts.json",
        "shop-purchases.json", "id_ed25519", "id_ed25519.pub", "hosts.json.migration-copy.protected",
        "hosts.json.bak.migration-copy.protected", "hosts.json.damaged.migration-copy.protected")

    fun migrate(source: File, destination: File) {
        require(destination.isDirectory || destination.mkdirs()) { "无法创建用户数据目录" }
        if (!source.isDirectory || source.canonicalFile == destination.canonicalFile) return
        val marker = File(destination, ".localappdata-migrated-v1")
        if (marker.isFile) return
        val entries = source.listFiles() ?: error("无法读取旧用户数据目录")
        entries.filter { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                (file.name in files || files.any { file.name == "$it.bak" || file.name == "$it.protected" || file.name == "$it.protected.bak" } ||
                    file.name.startsWith("hosts.json.protected.migration-"))
        }.forEach { old ->
            val target = File(destination, old.name)
            if (target.exists()) return@forEach // Existing home data always wins, including an explicit sign-out.
            val temporary = File(destination, ".${old.name}.migrating")
            Files.copy(old.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(Files.mismatch(old.toPath(), temporary.toPath()) == -1L) { "旧配置复制校验失败：${old.name}" }
            Files.move(temporary.toPath(), target.toPath())
        }
        marker.writeText("User data copied; original files retained.\n")
    }
}
