package app.yxi.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Copy user files out of the installer-owned tree. Never delete or replace a source.
 *
 * 迁移是一个**可恢复的两阶段事务**（失败回退旧目录时，旧版会继续往旧目录写新
 * token/hosts——所以绝不能把半新半旧的残留在目标目录里留着，否则下次重试时
 * 「目标已存在」会拿旧半份压过新源）：
 *
 * 1. **记账**：目标缺位的白名单文件先写进 journal（点前缀隐藏文件，常驻目标目录）；
 * 2. **预备**：全部逐个拷到 `.名字.migrating` 临时位并逐字节校验，期间不碰任何目标位；
 * 3. **提交**：临时位全部就绪后才逐个 move 成目标；
 * 4. **落章**：先写完成标记，再删 journal；避免完成记录之间出现断档。
 *
 * 任一步抛异常：**只回滚本次自己创建的东西**——已 move 的目标（本次新建，绝非用户旧文件）
 * 删掉、剩余临时位删掉、journal 删掉，源一个字节不动。进程被硬杀（来不及回滚）时，
 * 下次启动靠 journal 恢复：journal 里记的名字（∩白名单）当年在目标目录不存在，
 * 是那个死掉进程新建的残留，删掉后从头再来——预先存在的用户文件永不入账、永不删除。
 *
 * ⚠️ `.名字.migrating` 临时位与 `.localappdata-migrating-journal-v1` 是本迁移的保留名。
 * 完成标记（`.localappdata-migrated-v1`）先查——标记在说明上次已完整迁完，直接返回。
 */
internal object UserDataMigration {
    private val files = setOf("hosts.json", "auth.json", "prefs.json", "window.json", "known_hosts",
        "workspace.json", "project-previews.json", "project-services.json", "plugin-operations.json",
        "instructions.json", "model-changes.json", "codex-tasks.json", "support-drafts.json",
        "shop-purchases.json", "id_ed25519", "id_ed25519.pub", "hosts.json.migration-copy.protected",
        "hosts.json.bak.migration-copy.protected", "hosts.json.damaged.migration-copy.protected")
    private const val JOURNAL = ".localappdata-migrating-journal-v1"
    private const val MARKER = ".localappdata-migrated-v1"

    /** 白名单判定（集合成员 + .bak/.protected 变体 + 带时间戳的保护副本前缀）。
     *  ⚠️ 复制过滤、回滚双保险、journal 恢复三处必须同一谓词——带时间戳的
     *  `hosts.json.protected.migration-*` 不在集合里，只走前缀，漏了它回滚就会留残。 */
    private fun isMigrationEntry(name: String) = name.none { it == '/' || it == '\\' || it < ' ' } && (name in files ||
        files.any { name == "$it.bak" || name == "$it.protected" || name == "$it.protected.bak" } ||
        name.startsWith("hosts.json.protected.migration-"))

    /** move 注入点：测试用它模拟提交中途失败。默认 = 真实 NIO move（同目录 rename）。 */
    fun migrate(source: File, destination: File, mover: (Path, Path) -> Path = { from, to -> Files.move(from, to) }) {
        require(destination.isDirectory || destination.mkdirs()) { "无法创建用户数据目录" }
        if (!source.isDirectory || source.canonicalFile == destination.canonicalFile) return
        val marker = File(destination, MARKER)
        if (marker.isFile) return
        require(!marker.exists()) { "迁移标记路径被占用，旧数据保留" }
        val journal = File(destination, JOURNAL)
        recoverHalfTransaction(destination, journal)
        val entries = source.listFiles() ?: error("无法读取旧用户数据目录")
        val pending = entries.filter { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) && isMigrationEntry(file.name)
        }.filter { !File(destination, it.name).exists() }   // Existing home data always wins, including an explicit sign-out.
        if (pending.isEmpty()) { marker.writeText("User data copied; original files retained.\n"); return }
        journal.writeText(pending.joinToString("\n") { it.name } + "\n")
        // 预备：只造临时位（CREATE_NEW——绝不覆盖任何已存在的路径，哪怕是临时名）。
        val temporaries = mutableListOf<Pair<File, File>>()   // (源, 临时位)，本进程新建的才登记
        val moved = mutableListOf<File>()
        try {
            for (old in pending) {
                val temporary = File(destination, ".${old.name}.migrating")
                // createFile 先占位（已存在路径会炸 = 拒绝覆盖任何预先存在的路径，哪怕是临时名），
                // 再往自己刚占的位里拷内容
                Files.createFile(temporary.toPath())
                temporaries += old to temporary
                Files.copy(old.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
                check(Files.mismatch(old.toPath(), temporary.toPath()) == -1L) { "旧配置复制校验失败：${old.name}" }
            }
            // 提交：临时位全就绪才开动；move 谁失败，已 move 成目标的全是本次新建的（预备时目标缺位才入账），回滚干净。
            for ((old, temporary) in temporaries) {
                val target = File(destination, old.name)
                mover(temporary.toPath(), target.toPath())
                moved += target
            }
            marker.writeText("User data copied; original files retained.\n")
        } catch (failure: Exception) {
            var restored = true
            (moved + temporaries.map { it.second }).forEach { created ->
                runCatching { Files.deleteIfExists(created.toPath()) }.onFailure { restored = false; failure.addSuppressed(it) }
            }
            runCatching { Files.deleteIfExists(marker.toPath()) }.onFailure { restored = false; failure.addSuppressed(it) }
            if (restored) journal.delete() // Keep recovery instructions if any rollback failed.
            throw failure
        }
        journal.delete()
    }

    /** 硬杀残留恢复：journal 在 = 上个进程死在事务里。只删「journal 记了 ∩ 白名单 ∩ 现在存在」
     *  的目标与遗留临时位；journal 损坏则停止，保留旧数据。 */
    private fun recoverHalfTransaction(destination: File, journal: File) {
        if (!journal.isFile) return
        val recorded = journal.readLines().filter { it.isNotEmpty() }
        require(recorded.all(::isMigrationEntry)) { "迁移记录损坏，旧数据保留，请检查数据目录" }
        recorded.forEach { name ->
            Files.deleteIfExists(File(destination, name).toPath())
            Files.deleteIfExists(File(destination, ".$name.migrating").toPath())
        }
        journal.delete()
    }

}
