package app.yxi.desktop

import app.yxi.agent.Rewind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 回溯适配器的协议锁。fixture 里的报错**原话**来自 hk13 claude 2.1.267 隔离实测
 * （2026-09-21，fake 会话 DELTA/EPSILON/ZETA）—— CLI 改措辞这里就该红。
 * 老板令与审查红线也钉在这里：无绕过、真 binary、同 cwd、有限 timeout、
 * argv 只回放白名单、拒绝发生在模型调用前、投递前先复核同一实例。
 */
class RewindTest {
    private val sid = "4b1d8860-e7b2-4d5e-82eb-fc05a8996fac"
    private val anchor = "e6934838-b2cb-4164-96e7-699d4e59277e"
    private val target = "40828818-2f71-46d6-a43b-e6126e7e1de6"
    private val exe = "/usr/local/bin/claude"
    private val cwd = "/root/src/workspace/yunxi/yxi"
    private val cap = Rewind.Capture(exe, "/tmp/yxi-argv.x", model = "opus", effort = "high")

    /**
     * 默认 = 单轮严格式（目标就是最后一轮，drops 声明 = 目标本身）；
     * `drops = null` = 多轮回退式（省略 --resume-drops-turn）。
     */
    private fun plan(prompt: String = "改后的消息", fork: Boolean = false, drops: String? = target) =
        Rewind.Plan(sid, anchor, target, drops, prompt, fork)

    @Test
    fun `合法计划通过校验`() {
        assertNull(Rewind.validate(plan()))
        assertNull(Rewind.validate(plan(drops = null)))
    }

    @Test
    fun `坏uuid和空文本都被拦`() {
        assertEquals("sid", Rewind.validate(plan().copy(sessionId = "not-a-uuid")))
        assertEquals("anchor", Rewind.validate(plan().copy(anchorUuid = "")))
        assertEquals("target", Rewind.validate(plan().copy(targetUuid = "abc")))
        assertEquals("drops", Rewind.validate(plan().copy(dropsTurnUuid = "abc")))
        // 声明了 drops 就必须声明目标本身 —— 声明别的值（哪怕 anchor）本地先拦
        assertEquals("drops-target", Rewind.validate(plan().copy(dropsTurnUuid = anchor)))
        // anchor==target 也拦（drops 置空，别让 drops-target 抢先报）
        assertEquals("same", Rewind.validate(plan(drops = null).copy(targetUuid = anchor)))
        assertEquals("prompt", Rewind.validate(plan(prompt = "  ")))
    }

    @Test
    fun `命令同cwd带timeout回放原配置且无任何权限绕过`() {
        assertNull(Rewind.validateExe(exe))
        val cmd = Rewind.command(exe, cwd, cap, plan(prompt = "it's 含'单引号"))
        // 先验 timeout 可用、再 cd 进会话 cwd（claude 按 cwd 解析项目），最后才拉 claude
        assertTrue(cmd.startsWith("command -v timeout"))
        assertTrue("cd '$cwd'" in cmd)
        // 变量值一律单引号包裹 + 转义（q() 只转义不加引号，裸值带空格会被 shell 裂开）
        assertTrue("timeout ${Rewind.PRINT_TIMEOUT_SEC} $exe --resume '$sid'" in cmd)
        assertTrue(" -p --output-format json -- " in cmd)
        assertTrue("--resume-session-at '$anchor'" in cmd)
        assertTrue("--resume-drops-turn '$target'" in cmd)
        // 原模型配置回放 —— 不回放就会落到默认模型，等于换了线路在跑（审查指出的）
        assertTrue("--model 'opus'" in cmd)
        assertTrue("--effort 'high'" in cmd)
        // 老板令：禁止新增绕过 —— 一个都不许出现；也不许走 bashrc 函数
        assertTrue("IS_SANDBOX" !in cmd)
        assertTrue("skip-permissions" !in cmd)
        assertTrue("permission-mode" !in cmd)
        assertTrue("command claude" !in cmd)
        // 单引号被 '\'' 转义，不会提前闭合
        assertTrue("'\\''" in cmd)
        assertTrue("__YXI_REWIND__:rc=$" in cmd)   // 尾锚在远端展开退出码
    }

    @Test
    fun `capture没有模型配置时命令不带model旗标`() {
        val clean = Rewind.Capture(exe, "/tmp/yxi-argv.x")
        assertTrue("--model" !in Rewind.command(exe, cwd, clean, plan()))
        assertTrue("--effort" !in Rewind.command(exe, cwd, clean, plan()))
    }

    @Test
    fun `多轮回退省略drops旗标`() {
        // 探针F 实测（2026-09-21，隔离假会话）：session-at 不带 drops 校验全过（追加分支，
        // 会话 id 不变、旧尾巴留文件成旁支）；带声明必被守卫拒 —— 多轮命令里
        // 一个 --resume-drops-turn 都不能出现
        val cmd = Rewind.command(exe, cwd, cap, plan(drops = null))
        assertTrue("--resume-drops-turn" !in cmd)
        assertTrue(" -p --output-format json -- " in cmd)
        assertTrue("--resume-session-at '$anchor'" in cmd)
    }

    @Test
    fun `怪binary路径被拒`() {
        assertEquals("exe-name", Rewind.validateExe("claude"))                  // 裸名：过 charset 但不含 /claude
        assertEquals("exe-path", Rewind.validateExe("/opt/my tool/claude"))     // 空格
        assertEquals("exe-name", Rewind.validateExe("/usr/bin/node"))           // 不是 claude 本体
    }

    @Test
    fun `fork 计划带 fork-session 旗标`() {
        assertTrue("--fork-session" in Rewind.command(exe, cwd, cap, plan(fork = true)))
        assertTrue("--fork-session" !in Rewind.command(exe, cwd, cap, plan(fork = false)))
    }

    @Test
    fun `预检命令按字段精确匹配三个UUID`() {
        val cmd = Rewind.verifyCommand(cwd, plan())
        // source：项目目录编码在服务器端算（跟 Dirs 拉起同一 sed），命令里带原始路径
        assertTrue("sed 's/[^A-Za-z0-9]/-/g'" in cmd)
        assertTrue("printf %s '$cwd'" in cmd)
        assertTrue("/$sid.jsonl" in cmd)
        // anchor / target 都是「"uuid":"<值>"」整字段匹配，不是正文 grep
        assertTrue("\"uuid\":\"$anchor\"" in cmd)
        assertTrue("\"uuid\":\"$target\"" in cmd)
        // 父子关系、user 类型、非 isMeta、行序，一个不少
        assertTrue("\"parentUuid\":\"$anchor\"" in cmd)
        assertTrue("\"isMeta\":true" in cmd)
        assertTrue("\"type\":\"user\"" in cmd)
        assertTrue(" -lt " in cmd)
    }

    @Test
    fun `预检通过返回null失败给代号`() {
        assertNull(Rewind.parseVerify("__YXI_REWIND_CHK__:ok\n"))
        assertEquals("not-child", Rewind.parseVerify("noise\n__YXI_REWIND_CHK__:not-child\n"))
        assertEquals("missing-session", Rewind.parseVerify("__YXI_REWIND_CHK__:missing-session\n"))
        assertEquals("no-anchor", Rewind.parseVerify("__YXI_REWIND_CHK__:no-anchor\n"))
        assertEquals("no-target", Rewind.parseVerify("__YXI_REWIND_CHK__:no-target\n"))
        assertEquals("order", Rewind.parseVerify("__YXI_REWIND_CHK__:order\n"))
        assertEquals("meta", Rewind.parseVerify("__YXI_REWIND_CHK__:meta\n"))
        assertEquals("not-user", Rewind.parseVerify("__YXI_REWIND_CHK__:not-user\n"))
        assertEquals("noresult", Rewind.parseVerify("nothing here\n"))
    }

    @Test
    fun `捕获命令读argv不碰environ且带身份基准`() {
        val cmd = Rewind.captureCommand("cc-yxi")
        // pid 定位：登记表按 tmux 名精确匹配（带冒号界），退回 pane 子进程认 cmdline
        assertTrue("\"tmux\":\"cc-yxi:" in cmd)
        assertTrue("pane_pid" in cmd)
        assertTrue("grep -q claude" in cmd)
        // exe 解析 + argv 落 600 临时文件
        assertTrue("readlink /proc/" in cmd)
        assertTrue("chmod 600" in cmd)
        // relaunch 复核基准：pid + pane id（`%N`，pane 重建即变）
        assertTrue("__YXI_REWIND_CAP__:pid=\$pid" in cmd)
        assertTrue("#{pane_id}" in cmd)
        assertTrue("__YXI_REWIND_CAP__:pane=\$pn" in cmd)
        // 老板令：environ（密钥老家）一个字都不许读
        assertTrue("environ" !in cmd)
        // 只回放白名单 model/effort/name；其余旗标只报名不报值
        assertTrue("/^--model\$/{p=1}" in cmd)
        assertTrue("/^--effort\$/{p=1}" in cmd)
        assertTrue("/^(-n|--name)\$/{p=1}" in cmd)
        assertTrue("grep -vE" in cmd)
    }

    @Test
    fun `捕获解析出白名单与其余旗标名`() {
        val out = """
            __YXI_REWIND_CAP__:file=/tmp/yxi-argv.a1b2c3
            __YXI_REWIND_CAP__:exe=/usr/local/bin/claude
            __YXI_REWIND_CAP__:pid=4242
            __YXI_REWIND_CAP__:pane=%28
            __YXI_REWIND_CAP__:model=claude-opus-5[1m]
            __YXI_REWIND_CAP__:effort=high
            __YXI_REWIND_CAP__:other=--dangerously-skip-permissions --settings
        """.trimIndent() + "\n"
        val c = (Rewind.parseCapture(out) as Rewind.Got).capture
        assertEquals(exe, c.exe)
        assertEquals("/tmp/yxi-argv.a1b2c3", c.tmpFile)
        assertEquals("4242", c.pid)
        assertEquals("%28", c.paneId)
        assertEquals("claude-opus-5[1m]", c.model)
        assertEquals("high", c.effort)
        assertNull(c.name)
        // 权限类旗标在 others 里 = 原地重启的硬拒绝项
        assertTrue("--dangerously-skip-permissions" in c.others)
        assertTrue("--settings" in c.others)
        assertTrue(!c.inPlaceAllowed)
    }

    @Test
    fun `干净argv允许原地重启`() {
        val out = "__YXI_REWIND_CAP__:file=/tmp/yxi-argv.x\n__YXI_REWIND_CAP__:exe=$exe\n"
        val c = (Rewind.parseCapture(out) as Rewind.Got).capture
        assertTrue(c.others.isEmpty())
        assertTrue(c.inPlaceAllowed)
        assertNull(c.model)
        // 没捕获到模型配置时，执行命令绝不带 --model（别落到默认模型上）
        assertTrue("--model" !in Rewind.command(exe, cwd, c, plan()))
    }

    @Test
    fun `怪旗标值降级进others不进shell`() {
        val out = "__YXI_REWIND_CAP__:file=/tmp/t\n__YXI_REWIND_CAP__:exe=$exe\n" +
            "__YXI_REWIND_CAP__:model=\$(rm -rf /)\n"
        val c = (Rewind.parseCapture(out) as Rewind.Got).capture
        assertNull(c.model)
        assertTrue("--model" in c.others)
    }

    @Test
    fun `怪pid怪pane进不了比对基准`() {
        val out = "__YXI_REWIND_CAP__:file=/tmp/t\n__YXI_REWIND_CAP__:exe=$exe\n" +
            "__YXI_REWIND_CAP__:pid=rm -rf\n__YXI_REWIND_CAP__:pane=hello\n"
        val c = (Rewind.parseCapture(out) as Rewind.Got).capture
        assertEquals("", c.pid)
        assertEquals("", c.paneId)
    }

    @Test
    fun `捕获失败认得出代号`() {
        assertEquals("gone", (Rewind.parseCapture("__YXI_REWIND_CAP__:gone\n") as Rewind.Failed).code)
        assertEquals("noexe", (Rewind.parseCapture("__YXI_REWIND_CAP__:noexe\n") as Rewind.Failed).code)
        assertEquals("notag", (Rewind.parseCapture("nothing\n") as Rewind.Failed).code)
        assertEquals("badcap", (Rewind.parseCapture("__YXI_REWIND_CAP__:file=/tmp/t\n") as Rewind.Failed).code)
    }

    @Test
    fun `重启命令先复核实例再投递且同cwd真binary`() {
        val c = Rewind.Capture(exe, "/tmp/yxi-argv.x", model = "opus", effort = "high", name = "我的 会话", pid = "4242", paneId = "%28")
        val rt = "4321:\$3:1727000000"
        val cmd = Rewind.relaunchCommand("cc-yxi", c, sid, cwd, rt)
        // 复核与投递在**同一条 exec**里：复核不过只回 identity，一个键都不发
        assertTrue(cmd.startsWith("rt=\$(tmux display-message -p -t 'cc-yxi' '#{pid}:#{session_id}:#{session_created}'"))
        assertTrue("[ \"\$rt\" = '$rt' ]" in cmd)
        assertTrue("[ \"\$pn\" = '%28' ]" in cmd)
        assertTrue("__YXI_REWIND_ID__:identity" in cmd)
        // pane 里先 cd 回会话 cwd，再真 binary --resume + 白名单旗标（值一律单引号包裹）
        assertTrue("tmux send-keys -t \"\$pn\" -l -- '" in cmd)
        assertTrue("--resume" in cmd && "--model" in cmd && "--effort" in cmd)
        assertTrue("&& tmux send-keys -t \"\$pn\" Enter &&" in cmd)
        assertTrue("__YXI_REWIND_ID__:sent" in cmd)
        assertTrue("skip-permissions" !in cmd)
        assertTrue("IS_SANDBOX" !in cmd)
    }

    @Test
    fun `runtimeId缺失拒绝投递`() {
        val c = Rewind.Capture(exe, "/tmp/t", paneId = "%7")
        val cmd = Rewind.relaunchCommand("cc-yxi", c, sid, cwd, "")
        assertTrue("\"\$rt\" = '" !in cmd)
        assertTrue("send-keys" !in cmd)
        assertEquals("identity", Rewind.parseRelaunch("__YXI_REWIND_ID__:identity"))
    }

    @Test
    fun `投递结果认得出锚`() {
        assertNull(Rewind.parseRelaunch("__YXI_REWIND_ID__:sent\n"))
        assertEquals("identity", Rewind.parseRelaunch("__YXI_REWIND_ID__:identity\n"))
        assertEquals("noresult", Rewind.parseRelaunch("noise\n"))
    }

    @Test
    fun `清理命令删的是那个临时文件`() {
        val c = Rewind.Capture(exe, "/tmp/yxi-argv.x")
        assertEquals("rm -f -- '/tmp/yxi-argv.x'", Rewind.cleanupCommand(c))
    }

    @Test
    fun `成功输出认出会话id与答复`() {
        val out = """{"type":"result","result":"ZETA","session_id":"$sid"}""" + "\n__YXI_REWIND__:rc=0\n"
        val ok = Rewind.parse(out) as Rewind.Outcome.Ok
        assertEquals(sid, ok.sessionId)
        assertEquals("ZETA", ok.result)
    }

    @Test
    fun `fork成功认出新会话id`() {
        val forkSid = "11111111-2222-4333-8444-555555555555"
        // 实机 result 对象带 "type":"result"（hk13 claude 2.1.267 探针F 原样输出），parse 按它认
        val out = """{"type":"result","result":"好的","session_id":"$forkSid"}""" + "\n__YXI_REWIND__:rc=0\n"
        assertEquals(forkSid, (Rewind.parse(out) as Rewind.Outcome.Ok).sessionId)
    }

    @Test
    fun `guard拒绝按原话认`() {
        val out = "Resume rejected by --resume-drops-turn: resuming at $anchor would discard " +
            "entries not attributable to turn $target: range does not start with the declared turn prompt\n" +
            "__YXI_REWIND__:rc=1\n"
        val f = Rewind.parse(out) as Rewind.Outcome.Failed
        assertEquals("guard-refused", f.code)
        assertTrue("range does not start" in f.detail)
    }

    @Test
    fun `查无此消息按原话认`() {
        val out = "No message found with message.uuid of: $anchor\n__YXI_REWIND__:rc=1\n"
        assertEquals("no-message", (Rewind.parse(out) as Rewind.Outcome.Failed).code)
    }

    @Test
    fun `查无此会话按原话认`() {
        val out = "No conversation found with session ID: $sid\n__YXI_REWIND__:rc=1\n"
        assertEquals("no-conversation", (Rewind.parse(out) as Rewind.Outcome.Failed).code)
    }

    @Test
    fun `timeout收掉的轮认得出124`() {
        val f = Rewind.parse("partial output\n__YXI_REWIND__:rc=124\n") as Rewind.Outcome.Failed
        assertEquals("timeout", f.code)
    }

    @Test
    fun `没timeout命令和cd失败原样上抛代号`() {
        assertEquals("notimeout", (Rewind.parse("__YXI_REWIND__:rc=notimeout\n") as Rewind.Outcome.Failed).code)
        assertEquals("cwd", (Rewind.parse("__YXI_REWIND__:rc=cwd\n") as Rewind.Outcome.Failed).code)
    }

    @Test
    fun `没有尾锚一律失败不猜`() {
        val f = Rewind.parse("some random output\n") as Rewind.Outcome.Failed
        assertEquals("notag", f.code)
    }

    @Test
    fun `rc零但没有json结果按失败处理`() {
        assertEquals("empty", (Rewind.parse("__YXI_REWIND__:rc=0\n") as Rewind.Outcome.Failed).code)
    }

    @Test
    fun `未知非零退出带原文尾巴`() {
        val out = "Segmentation fault\n__YXI_REWIND__:rc=139\n"
        val f = Rewind.parse(out) as Rewind.Outcome.Failed
        assertEquals("rc", f.code)
        assertNotNull(f.detail)
    }

    // ───── 以下为审查补漏的命令契约锁（真 bash 执行的行为验证在 RewindShellTest）─────

    @Test
    fun `前导横杠prompt经双横杠分隔不当旗标解析`() {
        val p = plan(prompt = "--model evil --effort low")
        assertNull(Rewind.validate(p))
        val cmd = Rewind.command(exe, cwd, cap, p)
        // prompt 走 `--` 之后的唯一位置参数位 —— 前导 - 的文本不会被 getopt 吃成旗标
        assertTrue(" -p --output-format json -- '--model evil --effort low'" in cmd)
        // 绝不出现把 evil 当值回放的形态
        assertTrue("--model 'evil'" !in cmd)
        assertTrue("--effort 'low'" !in cmd)
    }

    @Test
    fun `exit命令先原子核对再逐级发exit三连`() {
        val c = cap.copy(pid = "4242", paneId = "%28")
        val rt = "4321:\$3:1727000000"
        val cmd = Rewind.exitCommand("cc-yxi", c, rt)
        // 复核与 /exit 在同一条 exec 里：复核不过只回 identity，一个键都不发
        assertTrue(cmd.startsWith("rt=\$(tmux display-message -p -t 'cc-yxi' '#{pid}:#{session_id}:#{session_created}'"))
        assertTrue("[ \"\$rt\" = '$rt' ]" in cmd)
        assertTrue("[ \"\$pn\" = '%28' ]" in cmd)
        assertTrue("__YXI_REWIND_ID__:identity" in cmd)
        // Esc 收浮层 → /exit（-l -- 字面）→ Enter，逐级 &&：任一步失败就没有 exit-sent
        assertTrue("tmux send-keys -t \"\$pn\" Escape && sleep 0.3 &&" in cmd)
        assertTrue("tmux send-keys -t \"\$pn\" -l -- '/exit' && tmux send-keys -t \"\$pn\" Enter &&" in cmd)
        assertTrue(cmd.endsWith("echo \"__YXI_REWIND_ID__:exit-sent\""))
        assertTrue("skip-permissions" !in cmd)
    }

    @Test
    fun `exit命令缺复核基准一律只回identity`() {
        assertEquals("echo '__YXI_REWIND_ID__:identity'", Rewind.exitCommand("cc-yxi", cap, ""))
        assertEquals(
            "echo '__YXI_REWIND_ID__:identity'",
            Rewind.exitCommand("cc-yxi", cap.copy(paneId = ""), "4321:\$3:1727000000"),
        )
    }

    @Test
    fun `退出结果认得出锚`() {
        assertNull(Rewind.parseExit("__YXI_REWIND_ID__:exit-sent\n"))
        assertEquals("identity", Rewind.parseExit("__YXI_REWIND_ID__:identity\n"))
        assertEquals("noresult", Rewind.parseExit("noise\n"))
    }

    @Test
    fun `等壳命令只认真shell名单`() {
        val cmd = Rewind.waitShellCommand("cc-yxi")
        // 审查补漏：只有真 shell（含 -bash 登录形态）算回壳 —— 「不是 claude」不再是 shell 证据
        assertTrue("case \"\$c\" in bash|-bash|zsh|-zsh|sh|-sh|dash|-dash|ash|-ash|ksh|-ksh) echo SHELL; exit 0;;" in cmd)
        assertTrue("vim" !in cmd && "top" !in cmd)
        // 旧逻辑的两个「直接放行」词不能再出现在认壳名单里
        assertTrue("claude" !in cmd.substringAfter("case"))
        // 默认 24×0.5s 有界；短轮询参数可调（bash 行为测试用）
        assertTrue("seq 1 24" in cmd && "sleep 0.5" in cmd)
        val short = Rewind.waitShellCommand("cc-yxi", iterations = 2, intervalSec = "0.1")
        assertTrue("seq 1 2" in short && "sleep 0.1" in short)
    }
}
