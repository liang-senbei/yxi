package app.yxi.desktop

import app.yxi.agent.ConfigRemote

/**
 * 服务器上 Claude Code 的一个技能（M4o，Code 自定义页的「技能」标签）：[name] 是技能目录名；[plugin] 是带来它的插件名
 * （`installed_plugins.json` 的键去掉「@市场」），用户自己放在 ~/.claude/skills 里的为空；[pluginId] 是那个完整的键；[path] 是 SKILL.md。
 */
internal data class HostSkill(val name: String, val plugin: String = "", val path: String = "", val pluginId: String = "")

/** 技能标签的纯逻辑（JVM 可单测）：从配置树取技能、排序去重、来源文案、搜索、SKILL.md 的头部。 */
internal object HostSkills {
    /** 一台主机最多列这么多条（服务器给的数据只信到这里）。 */
    private const val MAX = 500

    /**
     * [ConfigRemote.read] 的配置树里 Claude Code 的「skills」类。用户自己的排前面，插件带来的按插件名、再按技能名排（不分大小写）；
     * 同一个插件装了几份（用户级 + 项目级各一份）时同名技能只留第一条。名字空、过长、带控制字符的丢掉。
     */
    fun fromConfig(tools: List<ConfigRemote.Tool>): List<HostSkill> {
        val claude = tools.firstOrNull { it.key == "claude" } ?: return emptyList()
        val seen = HashSet<String>()
        return claude.cats.firstOrNull { it.key == "skills" }?.items.orEmpty().asSequence()
            .map { HostSkill(it.title.trim(), it.from.substringBefore('@').trim(), it.path, it.from.trim()) }
            .filter { s -> s.name.isNotEmpty() && s.name.length <= 200 && s.name.none { it < ' ' } }
            .filter { seen.add(it.pluginId + "\u0000" + it.name) }
            .take(MAX).toList()
            .sortedWith(compareBy<HostSkill>({ it.plugin.isNotEmpty() }, { it.plugin.lowercase() }, { it.name.lowercase() }))
    }

    /** SKILL.md 最多读这么多字节（再多的截断）。 */
    const val READ_LIMIT = 131_072

    /**
     * 读一个技能的 SKILL.md（只读）：只读普通文件（链到 /dev/zero、FIFO、设备的不读）、最多开头 [READ_LIMIT] 字节——exec 占着连接的锁、
     * 取消不了，不能让一个第三方插件的文件把它卡住或撑爆内存（交叉审查 10-15）。读不到、空的返回 null。
     */
    suspend fun read(ssh: app.yxi.ssh.SshSession?, path: String): String? {
        val p = app.yxi.ssh.Shell.q(path)
        return ssh?.exec("[ -f $p ] && head -c $READ_LIMIT -- $p 2>/dev/null").orEmpty().ifBlank { null }
    }

    /** 行右端的来源：「用户」或「插件 名字」。 */
    fun source(skill: HostSkill): String = if (skill.plugin.isEmpty()) "用户" else "插件 ${skill.plugin}"

    /** 搜索：技能名或来源文案里有 [query]（去掉首尾空白、不分大小写）；空的不筛。 */
    fun filter(skills: List<HostSkill>, query: String): List<HostSkill> {
        val q = query.trim()
        if (q.isEmpty()) return skills
        return skills.filter { it.name.contains(q, true) || source(it).contains(q, true) }
    }

    /** SKILL.md 拆成头部的 description（没有就空）和正文。 */
    data class Document(val description: String, val body: String)

    /**
     * SKILL.md 开头是一段 YAML（「---」到「---」，里面有 name、description），直接当 Markdown 画会变成分隔线和一个二级标题，所以先拆开：
     * 只取 description（单行值去掉成对的引号；「>」「|」这种多行写法取下面缩进的行，「>」连成一行、「|」保留换行），正文是头部之后的部分。
     * 没有头部、头部没收尾就整篇当正文。description 最长 2000。
     */
    fun document(text: String): Document {
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (lines.firstOrNull()?.trimEnd() != "---") return Document("", text.trim())
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." } ?: return Document("", text.trim())
        val head = lines.subList(1, end)
        val at = head.indexOfFirst { it.startsWith("description:") }
        val description = if (at < 0) "" else {
            val value = head[at].substringAfter(':').trim()
            if (value.isEmpty() || value[0] == '>' || value[0] == '|') {
                val more = head.drop(at + 1).takeWhile { it.isBlank() || it[0].isWhitespace() }.map { it.trim() }
                if (value.startsWith("|")) more.joinToString("\n").trim() else more.filter { it.isNotEmpty() }.joinToString(" ")
            } else unquote(value)
        }
        return Document(description.take(2000), lines.drop(end + 1).joinToString("\n").trim())
    }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> value.substring(1, value.length - 1).replace("\\\"", "\"")
        value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1).replace("''", "'")
        else -> value
    }
}
