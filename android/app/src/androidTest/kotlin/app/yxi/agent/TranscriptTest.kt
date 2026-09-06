package app.yxi.agent

import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Test

class TranscriptTest {

    /**
     * ⚠️ 目录名规则是**实测**出来的，不是猜的：本机 `~/.claude/projects/` 下真的存在
     * `-opt-workspace-----` 这种目录（对应 `/opt/workspace/日常对话`，四个汉字四个横杠）。
     * 一开始只把斜杠换成横杠，中文路径的会话就永远「找不到转录」。
     */
    @Test fun 项目目录名() {
        assertEquals("-root-src-workspace-Yxi", Transcript.projectDirOf("/root/src/workspace/Yxi"))
        assertEquals("-tmp", Transcript.projectDirOf("/tmp"))
        // 汉字：一个字一个横杠
        assertEquals("-opt-workspace-----", Transcript.projectDirOf("/opt/workspace/日常对话"))
        assertEquals("-opt-workspace---", Transcript.projectDirOf("/opt/workspace/环境"))
        // 点、空格、横杠本身也都变横杠
        assertEquals("-root--claude", Transcript.projectDirOf("/root/.claude"))
        assertEquals("-a-b-c", Transcript.projectDirOf("/a b.c"))
        assertEquals("-x-y", Transcript.projectDirOf("/x-y"))
    }

    /**
     * **切了模型、它还没回话时，顶栏也要显示新模型。**
     *
     * ⚠️ 用户报过：在会话里切到 Opus 5，App 顶栏还写着 opus-4-8，以为没切成。
     * 病根是顶栏那个名字来自**最后一条 assistant 消息**的 model —— 切换不会改写旧消息。
     * 所以要认 `/model` 的回执（命令输出里的 `Set model to …`）并覆盖。
     *
     * ⚠️ 回执里带 ANSI 加粗序列；而别名形式 `claude-opus-5[1m]` 里的 `[1m` 跟加粗序列长得一样，
     * **不能先整体清 ANSI**，否则显示成 `claude-opus-5]`。这条用例盯着这两点。
     */
    @Test fun 切完模型还没回话也显示新模型() {
        val esc = "\u001B"
        fun line(text: String) =
            """{"type":"user","message":{"role":"user","content":${JSONObject.quote(text)}}}"""
        val assistant =
            """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-4-8",""" +
                """"usage":{"input_tokens":10,"cache_read_input_tokens":90},"content":[]}}"""

        // 只有 assistant：显示它自己的模型
        Transcript.Incremental().apply { add(sequenceOf(assistant)) }.let {
            assertEquals("claude-opus-4-8", it.ctx?.model)
        }
        // 之后来一条切换回执（带 ANSI 加粗）：显示新模型，token 数保持
        Transcript.Incremental().apply {
            add(sequenceOf(assistant, line("<local-command-stdout>Set model to $esc[1mOpus 5 (1M context)$esc[22m and saved as your default for new sessions</local-command-stdout>")))
        }.let {
            assertEquals("Opus 5 (1M context)", it.ctx?.model)
            assertEquals(100L, it.ctx?.tokens)
        }
        // 别名形式：`[1m` 是模型名的一部分，别被当成 ANSI 吃掉
        Transcript.Incremental().apply {
            add(sequenceOf(assistant, line("<local-command-stdout>Set model to claude-opus-5[1m]</local-command-stdout>")))
        }.let { assertEquals("claude-opus-5[1m]", it.ctx?.model) }
        // 「Kept model as …」= 没切，不能误判
        Transcript.Incremental().apply {
            add(sequenceOf(assistant, line("<local-command-stdout>Kept model as $esc[1mOpus 4.8$esc[22m</local-command-stdout>")))
        }.let { assertEquals("claude-opus-4-8", it.ctx?.model) }
    }

    /**
     * **顶栏要显示这个会话的模型 + 模式。**
     *
     * ⚠️ `effort` 在转录行的**顶层**（跟 `type`/`uuid` 平级），**不在 message 里** —— 找错地方永远是空。
     * ⚠️ 模式来自单独的 `{"type":"mode",…}` 行（**没有 message 字段**），
     * 得在「非消息行静默跳过」之前接住，否则永远读不到。
     */
    /**
     * **回执单独落在下一批里，也要认。**
     *
     * ⚠️ 转录是一段一段追加解析的（[Transcript.Incremental.add] 每来一段调一次），
     * 而 `/model` 的回执经常**单独成一批**（那一批只有一条 user 消息）。
     * 原来 `lastCtx` 是 `parseInto` 里的局部变量、每批重置成 null，
     * 于是「切了模型但还没回话」那一支直接被丢掉 ——
     * 表现是「已经切到 Opus 5 了，顶栏还写着 fable-5-1」（老板 2026-09-06 报的）。
     *
     * ⚠️ 上面那条用例把 assistant 和回执放在**同一个 add()** 里，所以一直是绿的，没抓到这个。
     * 分两批才是真实情况。
     */
    @Test fun 回执单独一批也认() {
        fun line(text: String) =
            """{"type":"user","message":{"role":"user","content":${JSONObject.quote(text)}}}"""
        val assistant =
            """{"type":"assistant","message":{"role":"assistant","model":"claude-fable-5-1",""" +
                """"usage":{"input_tokens":10,"cache_read_input_tokens":90},"content":[]}}"""

        Transcript.Incremental().apply {
            add(sequenceOf(assistant))                       // 第一批：只有回话
            add(sequenceOf(line("<local-command-stdout>Set model to `Opus 5 (1M context) (default)` and saved as your default for new sessions</local-command-stdout>")))
        }.let {
            // 反引号和 `(default)` 都不是模型名的一部分，要摘掉
            assertEquals("Opus 5 (1M context)", it.ctx?.model)
            assertEquals(100L, it.ctx?.tokens)               // 用量沿用上一批的，别清零
        }
    }

    /**
     * **模式 / ponytail 也要跨批活下来。**
     *
     * ⚠️ 第一版跨批修复是把它们塞进 [Transcript.Ctx] 捎带的，而 Ctx 只有见过
     * 带用量的 assistant 行之后才存在 —— 「开着计划模式进对话页」时，那行 mode
     * 在**更早的批**里，于是照样死在批边界上（审查抓到的，跟模型名是同一个 bug）。
     * 现在用 [Transcript.Carry] 单独带。
     */
    @Test fun 模式和强度也跨批() {
        val assistant =
            """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-5",""" +
                """"usage":{"input_tokens":10,"cache_read_input_tokens":90},"content":[]}}"""
        Transcript.Incremental().apply {
            add(sequenceOf("""{"type":"mode","mode":"plan","sessionId":"s1"}"""))  // 第一批：只有模式
            add(sequenceOf(assistant))                                             // 第二批：才有用量
        }.let { assertEquals("plan", it.ctx?.mode) }
    }

    @Test fun 顶栏带思考强度和模式() {
        val assistant =
            """{"type":"assistant","effort":"max","message":{"role":"assistant","model":"claude-opus-5",""" +
                """"usage":{"input_tokens":10,"cache_read_input_tokens":90},"content":[]}}"""
        val planLine = """{"type":"mode","mode":"plan","sessionId":"s1"}"""
        val normalLine = """{"type":"mode","mode":"normal","sessionId":"s1"}"""

        // effort 取到；没有 mode 行时 mode 为空
        Transcript.Incremental().apply { add(sequenceOf(assistant)) }.let {
            assertEquals("max", it.ctx?.effort)
            assertEquals("claude-opus-5", it.ctx?.model)
            assertEquals("", it.ctx?.mode)
        }
        // 先进计划模式再回话：带上 plan
        Transcript.Incremental().apply { add(sequenceOf(planLine, assistant)) }
            .let { assertEquals("plan", it.ctx?.mode) }
        // 回完话之后才切模式：也要立刻反映（用量保持）
        Transcript.Incremental().apply { add(sequenceOf(assistant, planLine)) }.let {
            assertEquals("plan", it.ctx?.mode)
            assertEquals(100L, it.ctx?.tokens)
            assertEquals("max", it.ctx?.effort)
        }
        // 切回 normal
        Transcript.Incremental().apply { add(sequenceOf(assistant, planLine, normalLine)) }
            .let { assertEquals("normal", it.ctx?.mode) }
    }

    /**
     * ponytail 强度（lite/full/ultra）——顶栏也要显示。
     * ⚠️ 它藏在钩子输出的那段文字里（`PONYTAIL MODE ACTIVE — level: x`），破折号是 em dash。
     * ⚠️ 等级为空的注入（实测真有）不能把已知值冲掉。
     */
    @Test fun 认得出ponytail强度() {
        val assistant =
            """{"type":"assistant","effort":"max","message":{"role":"assistant","model":"claude-opus-5",""" +
                """"usage":{"input_tokens":10,"cache_read_input_tokens":90},"content":[]}}"""
        fun hook(text: String) =
            """{"type":"system","attachment":{"type":"hook_success","text":${JSONObject.quote(text)}}}"""

        // 注入在回话之前
        Transcript.Incremental().apply {
            add(sequenceOf(hook("PONYTAIL MODE ACTIVE \u2014 level: full"), assistant))
        }.let { assertEquals("full", it.ctx?.ponytail) }

        // 注入在回话之后：也要立刻反映
        Transcript.Incremental().apply {
            add(sequenceOf(assistant, hook("PONYTAIL MODE CHANGED \u2014 level: ultra")))
        }.let {
            assertEquals("ultra", it.ctx?.ponytail)
            assertEquals(100L, it.ctx?.tokens)      // 用量不受影响
        }

        // 空等级的注入不能把已知值冲掉
        Transcript.Incremental().apply {
            add(sequenceOf(hook("PONYTAIL MODE ACTIVE \u2014 level: ultra"), assistant,
                hook("PONYTAIL MODE ACTIVE \u2014 level: ")))
        }.let { assertEquals("ultra", it.ctx?.ponytail) }

        // 压根没注入过 → 空着，不瞎猜
        Transcript.Incremental().apply { add(sequenceOf(assistant)) }
            .let { assertEquals("", it.ctx?.ponytail) }
    }
}
