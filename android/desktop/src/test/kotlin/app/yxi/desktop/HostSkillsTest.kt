package app.yxi.desktop

import app.yxi.agent.ConfigRemote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Code 自定义页「技能」标签（M4o）的纯逻辑：取技能、排序去重、来源、搜索、SKILL.md 头部；和斜杠菜单共用的按主机缓存。不组合 UI、不连主机。 */
class HostSkillsTest {
    private fun item(name: String, from: String = "", path: String = "/home/dev/.claude/skills/$name/SKILL.md") = ConfigRemote.Item(name, path = path, from = from)
    private fun claude(vararg cats: ConfigRemote.Cat) = ConfigRemote.Tool("claude", "Claude Code", cats.toList())
    private fun skills(vararg items: ConfigRemote.Item) = ConfigRemote.Cat("skills", "技能 Skills", items.toList())

    @Test fun `user skills come first, plugin skills grouped by plugin, duplicates and bad names dropped`() {
        val tools = listOf(
            claude(
                ConfigRemote.Cat("commands", "斜杠命令", listOf(item("deploy"))),   // 命令不是技能
                skills(
                    item("writing-plans", "superpowers@superpowers-marketplace"),
                    item("Release-notes"),
                    item("brainstorming", "superpowers@superpowers-marketplace"),
                    item("pdf"),
                    item("brainstorming", "superpowers@superpowers-marketplace", "/home/dev/proj/.claude/plugins/superpowers/skills/brainstorming/SKILL.md"),
                    item("lint", "acme-kit@acme"),
                    item("PDF"),   // 区分大小写的文件系统上是另一个技能，不当重名
                    item(""), item("bad\u0001name"), item("x".repeat(201)),
                ),
            ),
            ConfigRemote.Tool("codex", "Codex", listOf(ConfigRemote.Cat("prompts", "提示 prompts", listOf(item("codex-only"))))),
        )
        val list = HostSkills.fromConfig(tools)
        assertEquals(listOf("pdf", "PDF", "Release-notes", "lint", "brainstorming", "writing-plans"), list.map { it.name })
        assertEquals(listOf("", "", "", "acme-kit", "superpowers", "superpowers"), list.map { it.plugin })
        // 同一插件装了两份：只留第一份的路径
        assertEquals("/home/dev/.claude/skills/brainstorming/SKILL.md", list.single { it.name == "brainstorming" }.path)
        assertEquals("superpowers@superpowers-marketplace", list.single { it.name == "brainstorming" }.pluginId)
        assertEquals(listOf("用户", "用户", "用户", "插件 acme-kit", "插件 superpowers", "插件 superpowers"), list.map(HostSkills::source))
        assertEquals(emptyList(), HostSkills.fromConfig(emptyList()))
        assertEquals(emptyList(), HostSkills.fromConfig(listOf(claude(ConfigRemote.Cat("commands", "斜杠命令", listOf(item("deploy")))))))
    }

    @Test fun `search matches the name or the source, trimmed and case-insensitive`() {
        val list = listOf(HostSkill("pdf"), HostSkill("Release-notes"), HostSkill("brainstorming", "superpowers"), HostSkill("writing-plans", "superpowers"))
        assertSame(list, HostSkills.filter(list, "  "))
        assertEquals(listOf("Release-notes"), HostSkills.filter(list, " release ").map { it.name })
        assertEquals(listOf("brainstorming", "writing-plans"), HostSkills.filter(list, "SUPERPOWERS").map { it.name })
        assertEquals(listOf("pdf", "Release-notes"), HostSkills.filter(list, "用户").map { it.name })
        assertEquals(listOf("writing-plans"), HostSkills.filter(list, "plan").map { it.name })
        assertEquals(emptyList(), HostSkills.filter(list, "nothing"))
    }

    @Test fun `the SKILL front matter is split from the body`() {
        val doc = HostSkills.document("---\r\nname: pdf\r\ndescription: \"Read and fill PDF forms\"\r\nlicense: MIT\r\n---\r\n\r\n# PDF\r\nUse pypdf.\r\n")
        assertEquals("Read and fill PDF forms", doc.description)
        assertEquals("# PDF\nUse pypdf.", doc.body)
        assertEquals("It's quoted", HostSkills.document("---\ndescription: 'It''s quoted'\n---\nbody").description)
        // 多行写法：「>」连成一行，「|」保留换行；下一个键之前为止
        assertEquals("Plan the work before coding.", HostSkills.document("---\ndescription: >\n  Plan the work\n  before coding.\nname: plans\n---\nSteps").description)
        assertEquals("line one\nline two", HostSkills.document("---\ndescription: |\n  line one\n  line two\n---\n").description)
        // 没有头部、头部没收尾：整篇当正文
        assertEquals(HostSkills.Document("", "# Just markdown"), HostSkills.document("\n# Just markdown\n"))
        assertEquals(HostSkills.Document("", "---\nname: open"), HostSkills.document("---\nname: open"))
        assertEquals(HostSkills.Document("", "Body"), HostSkills.document("﻿---\nname: x\n---\nBody"))
        assertEquals(2000, HostSkills.document("---\ndescription: ${"d".repeat(3000)}\n---\n").description.length)
    }

    @Test fun `skills share the slash cache, an empty read records an empty list but never wipes one`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = "skills-cache-" + System.nanoTime()
        val full = listOf(claude(skills(item("pdf")), ConfigRemote.Cat("commands", "斜杠命令", listOf(item("deploy")))))
        assertNull(RemoteSlashCommands.skills(host))
        assertFalse(RemoteSlashCommands.reading(host))
        // 斜杠菜单读过一次：技能页拿到同一次的技能表，不再读
        RemoteSlashCommands.ensure(host) { full }?.join()
        assertEquals(listOf("pdf"), RemoteSlashCommands.skills(host)?.map { it.name })
        assertEquals(listOf("deploy", "pdf"), RemoteSlashCommands.get(host).map { it.name })
        assertNull(RemoteSlashCommands.ensure(host) { error("不该再读") })
        // 读空、没读成都不覆盖已有的表；没读成记下，再读成就清掉
        RemoteSlashCommands.put(host, emptyList())
        RemoteSlashCommands.put(host, null)
        assertTrue(RemoteSlashCommands.failed(host))
        assertEquals(listOf("pdf"), RemoteSlashCommands.skills(host)?.map { it.name })
        RemoteSlashCommands.put(host, listOf(claude(skills(item("pdf"), item("lint", "acme-kit@acme")))))
        assertFalse(RemoteSlashCommands.failed(host))
        assertEquals(listOf("pdf", "lint"), RemoteSlashCommands.skills(host)?.map { it.name })
        // 一台没有 ~/.claude 的主机：读成了、是空的 —— 技能页要能说「没有技能」，不是「还没读到」
        val bare = "$host-bare"
        RemoteSlashCommands.ensure(bare) { emptyList() }?.join()
        assertEquals(emptyList(), RemoteSlashCommands.skills(bare))
        assertFalse(RemoteSlashCommands.failed(bare))
    }

    @Test fun `refresh rereads even with a list, shares an in-flight read and shows as reading`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = "skills-refresh-" + System.nanoTime()
        RemoteSlashCommands.put(host, listOf(claude(skills(item("pdf")))))
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val job = RemoteSlashCommands.refresh(host) { loads++; gate.await(); listOf(claude(skills(item("pdf"), item("charts")))) }
        assertTrue(RemoteSlashCommands.reading(host))
        assertSame(job, RemoteSlashCommands.refresh(host) { loads++; null })   // 正在读：同一次
        assertSame(job, assertNotNull(RemoteSlashCommands.ensure(host) { loads++; null }))
        gate.complete(Unit)
        withTimeout(2000) { job.join() }
        assertEquals(1, loads)
        assertFalse(RemoteSlashCommands.reading(host))
        assertEquals(listOf("charts", "pdf"), RemoteSlashCommands.skills(host)?.map { it.name })
        // 没读成（抛错）：表留着、记下失败
        RemoteSlashCommands.refresh(host) { error("连接断了") }.join()
        assertTrue(RemoteSlashCommands.failed(host))
        assertEquals(listOf("charts", "pdf"), RemoteSlashCommands.skills(host)?.map { it.name })
    }
}
