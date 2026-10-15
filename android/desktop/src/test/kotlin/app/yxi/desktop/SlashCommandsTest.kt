package app.yxi.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import app.yxi.agent.ConfigRemote
import app.yxi.agent.Slash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 斜杠菜单（M4k）的纯函数：筛选、合并去重、三种来源的解析、键盘语义、高亮、按主机的缓存。不组合 UI、不连主机。 */
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

    @Test fun `highlight follows the picked name and falls back to the first row`() {
        val list = listOf(SlashCommand("compact"), SlashCommand("context"), SlashCommand("cost"))
        assertEquals(1, SlashCommands.highlight(list, "context"))
        // 主机的命令表晚到、前面多了一条：按 ↓ 选中的那条还是它
        assertEquals(2, SlashCommands.highlight(listOf(SlashCommand("codegen")) + list, "context"))
        assertEquals(0, SlashCommands.highlight(list, "gone"))
        assertEquals(0, SlashCommands.highlight(list, null))
        assertEquals(0, SlashCommands.highlight(emptyList(), "context"))
    }

    private val deploy = listOf(ConfigRemote.Tool("claude", "Claude Code", listOf(ConfigRemote.Cat("commands", "斜杠命令", listOf(ConfigRemote.Item("deploy"))))))

    @Test fun `a host load survives its requester and runs once for all chats on that host`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = "slash-cancel-" + System.nanoTime()
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        // 第一个对话开始读，随即草稿补完 / 离开页面（请求它的协程被取消）
        val requester = launch { RemoteSlashCommands.ensure(host) { loads++; gate.await(); deploy }; awaitCancellation() }
        yield()
        requester.cancelAndJoin()
        // 同一台主机的另一个对话也打了「/」：在读就不再读，拿到的是同一次
        val shared = RemoteSlashCommands.ensure(host) { loads++; deploy }
        assertTrue(shared != null && shared.isActive)
        gate.complete(Unit)
        withTimeout(2000) { shared.join() }
        assertEquals(1, loads)
        assertEquals(listOf("deploy"), names(RemoteSlashCommands.get(host)))
        assertNull(RemoteSlashCommands.ensure(host) { loads++; deploy })   // 有了就不再读
        assertEquals(1, loads)
    }

    @Test fun `failed reads back off briefly, empty reads for a minute, and neither overwrites a list`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = "slash-retry-" + System.nanoTime()
        var clock = 1_000_000L
        val real = RemoteSlashCommands.now
        RemoteSlashCommands.now = { clock }
        try {
            var loads = 0
            // 没读成（截断、脚本出错、抛错）：15 秒内再打「/」不再去读，过了再读
            RemoteSlashCommands.ensure(host) { loads++; null }?.join()
            assertNull(RemoteSlashCommands.ensure(host) { loads++; deploy })
            clock += RemoteSlashCommands.FAILED_WAIT - 1
            assertNull(RemoteSlashCommands.ensure(host) { loads++; deploy })
            clock += 1
            RemoteSlashCommands.ensure(host) { loads++; error("连接断了") }?.join()
            assertNull(RemoteSlashCommands.ensure(host) { loads++; deploy })
            clock += RemoteSlashCommands.FAILED_WAIT
            RemoteSlashCommands.ensure(host) { loads++; deploy }?.join()
            assertEquals(3, loads)
            assertEquals(listOf("deploy"), names(RemoteSlashCommands.get(host)))
            // 读成了但是空的：一分钟内不再读，没读成也不会把它缩短
            val empty = "$host-empty"
            RemoteSlashCommands.ensure(empty) { loads++; emptyList() }?.join()
            RemoteSlashCommands.put(empty, null)
            clock += RemoteSlashCommands.FAILED_WAIT
            assertNull(RemoteSlashCommands.ensure(empty) { loads++; deploy })
            clock += RemoteSlashCommands.EMPTY_WAIT
            RemoteSlashCommands.ensure(empty) { loads++; deploy }?.join()
            assertEquals(5, loads)
            assertEquals(listOf("deploy"), names(RemoteSlashCommands.get(empty)))
            // 有了表之后，读空、没读成都不覆盖
            RemoteSlashCommands.put(host, emptyList())
            RemoteSlashCommands.put(host, null)
            assertEquals(listOf("deploy"), names(RemoteSlashCommands.get(host)))
        } finally { RemoteSlashCommands.now = real }
    }

    @Test fun `a truncated or missing config output is a failed read, an empty one is not`() {
        val marker = "__YXI_CFG_V1__"   // ConfigRemote 抓取脚本的输出标记
        val full = "$marker\n" + JSONObject().put("claude", JSONObject().put("commands", JSONArray()
            .put(JSONObject().put("name", "deploy").put("path", "/home/dev/.claude/commands/deploy.md")))).toString()
        assertEquals(listOf("deploy"), SlashCommands.fromConfig(assertNotNull(ConfigRemote.parseOutput(full))).map { it.name })
        // 连接中途断了：标记有、JSON 只到一半；或者标记后面什么都没有
        assertNull(ConfigRemote.parseOutput(full.dropLast(12)))
        assertNull(ConfigRemote.parseOutput("$marker\n"))
        assertNull(ConfigRemote.parseOutput(""))   // 没连上：exec 吞掉错误返回空串
        assertNull(ConfigRemote.parseOutput("Traceback (most recent call last):"))
        // 跑完了但没有 ~/.claude、~/.codex（或没有 python3，脚本输出 {}）：读成了，是空的
        assertEquals(emptyList(), ConfigRemote.parseOutput("$marker\n{}"))
    }
}
