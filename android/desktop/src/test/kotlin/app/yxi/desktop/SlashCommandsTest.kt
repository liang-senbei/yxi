package app.yxi.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import app.yxi.agent.ConfigRemote
import app.yxi.agent.Slash
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 斜杠菜单（M4k）的纯函数：筛选、合并去重、三种来源的解析、键盘语义、按主机的缓存。不组合 UI、不连主机。 */
class SlashCommandsTest {
    private fun names(list: List<SlashCommand>) = list.map { it.name }
    private val server = listOf(SlashCommand("mobile-release", "自定义命令"), SlashCommand("superpowers:brainstorm", "插件 superpowers 的技能"))

    @Test fun `built-ins mirror the shared list without emoji`() {
        val builtIns = SlashCommands.builtIns()
        assertEquals(Slash.ALL.map { it.name }, names(builtIns))
        assertTrue(builtIns.none { "⚠" in it.hint })
        assertEquals("清空重开（当前对话就没了）", builtIns.single { it.name == "clear" }.hint)
    }

    @Test fun `only a first slash word without whitespace opens the menu`() {
        assertEquals("", SlashCommands.word("/"))
        assertEquals("mo", SlashCommands.word("/mo"))
        assertNull(SlashCommands.word(""))
        assertNull(SlashCommands.word("看一下 /usr/bin"))
        assertNull(SlashCommands.word("/model "))
        assertNull(SlashCommands.word("/model opus"))
        assertNull(SlashCommands.word("/tmp/x\n第二行"))
    }

    @Test fun `suggestions filter by prefix, case-insensitively, including the part after a plugin namespace`() {
        val all = SlashCommands.merge(SlashCommands.builtIns(), server)
        assertEquals(all, SlashCommands.suggest(all, "/"))
        assertEquals(listOf("model", "mobile-release"), names(SlashCommands.suggest(all, "/mo")))
        assertEquals(listOf("model", "mobile-release"), names(SlashCommands.suggest(all, "/MO")))
        assertEquals(listOf("superpowers:brainstorm"), names(SlashCommands.suggest(all, "/brain")))
        assertEquals(listOf("superpowers:brainstorm"), names(SlashCommands.suggest(all, "/super")))
        assertEquals(emptyList(), SlashCommands.suggest(all, "/zzz"))
        assertEquals(emptyList(), SlashCommands.suggest(all, "/mo del"))
        assertEquals(emptyList(), SlashCommands.suggest(emptyList(), "/"))
    }

    @Test fun `a fully typed command closes the menu unless a longer one shares its prefix`() {
        val all = SlashCommands.builtIns()
        assertEquals(emptyList(), SlashCommands.suggest(all, "/usage"))
        assertEquals(emptyList(), SlashCommands.suggest(all, "/USAGE"))
        val longer = SlashCommands.merge(listOf(SlashCommand("review-pr")), all)
        // 打全的那条提到最前，回车补的就是它
        assertEquals(listOf("review", "review-pr"), names(SlashCommands.suggest(longer, "/review")))
        // 只有插件里带命名空间的那条：名字和词不一样，照列（回车补出完整写法）
        assertEquals(listOf("superpowers:brainstorm"), names(SlashCommands.suggest(server, "/brainstorm")))
    }

    @Test fun `merging keeps the first entry per name and drops unusable names`() {
        val merged = SlashCommands.merge(
            listOf(SlashCommand("compact", "内置说明"), SlashCommand("model")),
            listOf(SlashCommand("Compact", "运行器说明"), SlashCommand("deploy"), SlashCommand(""), SlashCommand("two words"), SlashCommand("deploy", "重复")),
        )
        assertEquals(listOf("compact", "model", "deploy"), names(merged))
        assertEquals("内置说明", merged.first().hint)
        assertEquals("", merged.last().hint)
    }

    @Test fun `a runner that reports its commands is the authority, built-in hints still win`() {
        val builtIns = SlashCommands.builtIns()
        assertEquals(builtIns, SlashCommands.forRunner(builtIns, null))
        val reported = listOf(SlashCommand("review", "Review a pull request"), SlashCommand("deploy", "Ship it"), SlashCommand("compact", "Clear history but keep a summary"))
        val list = SlashCommands.forRunner(builtIns, reported)
        // 内置里它报了的按内置顺序在前（compact 排第一），其余按运行器顺序；没报的 /config、/doctor 不列
        assertEquals(listOf("compact", "review", "deploy"), names(list))
        assertEquals(builtIns.first { it.name == "compact" }.hint, list.first().hint)
        assertEquals("Ship it", list.last().hint)
        assertEquals(emptyList(), SlashCommands.forRunner(builtIns, emptyList()))
    }

    @Test fun `server commands and skills come from the claude config tree, plugin ones namespaced`() {
        fun item(name: String, from: String = "") = ConfigRemote.Item(name, from = from)
        val tools = listOf(
            ConfigRemote.Tool("claude", "Claude Code", listOf(
                ConfigRemote.Cat("agents", "子 agent", listOf(item("reviewer"))),
                ConfigRemote.Cat("skills", "技能 Skills", listOf(item("pdf"), item("brainstorm", "superpowers@superpowers-marketplace"))),
                ConfigRemote.Cat("commands", "斜杠命令", listOf(item("mobile-release"), item("ship", "release-kit@acme"), item("pdf"))),
            )),
            ConfigRemote.Tool("codex", "Codex", listOf(ConfigRemote.Cat("prompts", "提示 prompts", listOf(item("codex-only"))))),
        )
        val list = SlashCommands.fromConfig(tools)
        // 命令在前、技能在后；同名的技能不再列；agent 和 Codex 的 prompts 不算
        assertEquals(listOf("mobile-release", "release-kit:ship", "pdf", "superpowers:brainstorm"), names(list))
        assertEquals(listOf("自定义命令", "插件 release-kit 的命令", "自定义命令", "插件 superpowers 的技能"), list.map { it.hint })
        assertEquals(emptyList(), SlashCommands.fromConfig(tools.drop(1)))
    }

    @Test fun `acp available_commands_update is parsed defensively`() {
        val update = JSONObject().put("sessionUpdate", "available_commands_update").put("availableCommands", JSONArray()
            .put(JSONObject().put("name", "web").put("description", "Search the web\nfor information").put("input", JSONObject().put("hint", "query")))
            .put(JSONObject().put("name", "/test").put("description", "Run tests"))
            .put(JSONObject().put("name", "bad name"))
            .put("not an object")
            .put(JSONObject().put("description", "no name")))
        assertEquals(listOf(SlashCommand("web", "Search the web for information"), SlashCommand("test", "Run tests")), SlashCommands.fromAcp(update))
        assertNull(SlashCommands.fromAcp(JSONObject().put("sessionUpdate", "plan")))
        assertNull(SlashCommands.fromAcp(JSONObject().put("sessionUpdate", "available_commands_update")))
        assertEquals(emptyList(), SlashCommands.fromAcp(JSONObject().put("sessionUpdate", "available_commands_update").put("availableCommands", JSONArray())))
    }

    @Test fun `claude initialize commands are optional`() {
        // 形同 Claude Code 2.1 的 SDK initialize 回包：commands[] 里 name / description / argumentHint
        val response = JSONObject().put("models", JSONArray()).put("commands", JSONArray()
            .put(JSONObject().put("name", "compact").put("description", "Clear conversation history but keep a summary").put("argumentHint", "<optional custom summarization instructions>"))
            .put(JSONObject().put("name", "release-kit:ship").put("description", "Ship it").put("argumentHint", "")))
        assertEquals(listOf("compact", "release-kit:ship"), SlashCommands.fromClaudeInitialize(response)?.let(::names))
        assertNull(SlashCommands.fromClaudeInitialize(JSONObject().put("models", JSONArray())))
    }

    @Test fun `keyboard mirrors the mention list and adds tab`() {
        var moved = -1; var accepted = 0; var dismissed = 0
        fun key(key: Key, shift: Boolean = false, type: KeyEventType = KeyEventType.KeyDown, count: Int = 3, composing: Boolean = false, current: Int = 0) =
            handleSlashKey(type, key, shift, count, composing, current, { moved = it }, { accepted++ }, { dismissed++ })
        assertTrue(key(Key.Tab)); assertEquals(1, accepted)
        assertTrue(key(Key.Enter)); assertEquals(2, accepted)
        assertFalse(key(Key.Tab, shift = true))
        assertFalse(key(Key.Enter, shift = true))
        assertTrue(key(Key.DirectionUp, current = 0)); assertEquals(2, moved)
        assertTrue(key(Key.DirectionDown, current = 2)); assertEquals(0, moved)
        assertTrue(key(Key.Escape)); assertEquals(1, dismissed)
        assertFalse(key(Key.Tab, type = KeyEventType.KeyUp))
        assertFalse(key(Key.Tab, composing = true))
        assertFalse(key(Key.Tab, count = 0))
        assertFalse(key(Key.A))
        assertEquals(2, accepted)
    }

    @Test fun `server commands load once per host and an empty read is not retried right away`() = runBlocking {
        val host = "slash-test-" + System.nanoTime()
        var loads = 0
        val tools = listOf(ConfigRemote.Tool("claude", "Claude Code", listOf(ConfigRemote.Cat("commands", "斜杠命令", listOf(ConfigRemote.Item("deploy"))))))
        RemoteSlashCommands.ensure(host) { loads++; tools }
        RemoteSlashCommands.ensure(host) { loads++; tools }
        assertEquals(1, loads)
        assertEquals(listOf("deploy"), names(RemoteSlashCommands.get(host)))
        // 读空（没连上）：不覆盖、一分钟内不再去读
        val other = "$host-empty"
        RemoteSlashCommands.ensure(other) { loads++; emptyList() }
        RemoteSlashCommands.ensure(other) { loads++; tools }
        assertEquals(2, loads)
        assertEquals(emptyList(), RemoteSlashCommands.get(other))
        RemoteSlashCommands.put(host, emptyList())
        assertEquals(listOf("deploy"), names(RemoteSlashCommands.get(host)))
    }
}
