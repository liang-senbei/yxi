package app.yxi

import app.yxi.agent.Health
import org.junit.Assert.*
import org.junit.Test

class HealthTest {

    /**
     * ⚠️ **这是真机上抓的输出**（2026-08-29 那台卡住的服务器），
     * 不是我编的。编一份「格式看起来对」的样本，测的就只是我对格式的想象。
     */
    private val real = """
        #load
        8.93 10.00 24.35 5/1518 1102226
        #cpu
        16
        #mem
        MemTotal:       16372796 kB
        MemAvailable:    6518004 kB
        SwapTotal:       8388604 kB
        SwapFree:        2299196 kB
        #stat1
        cpu  64336943 41224 18218903 2063372825 7558482 0 2339734 187424529 9616286 0
        #stat2
        cpu  64337005 41224 18218947 2063374080 7558483 0 2339739 187425207 9616286 0
        #disk
        /dev/vda1        507930276 69471912 438441980      14% /
    """.trimIndent()

    @Test fun 真机输出解析得出来() {
        val v = Health.parse(real)!!
        assertEquals(16, v.cores)
        assertEquals(8.93, v.load1, 0.001)
        assertEquals(61, v.memUsedPct)
        assertEquals(72, v.swapUsedPct)
        assertEquals(14, v.diskUsedPct)
    }

    /**
     * ⚠️ **steal 是这个功能的核心** —— 也是我 2026-08-29 诊断时漏掉的那个数。
     * 算错了整个功能就白做（它会永远显示「一切正常」）。
     * 这里的期望值 33% 跟当时 `vmstat` 报的 35% 对得上（采样噪声内）。
     */
    @Test fun steal算得对() {
        assertEquals(33, Health.parse(real)!!.steal)
    }

    /** ⚠️ 单次读 `/proc/stat` 只能算出「开机以来的平均」—— 拿不到两次就必须是 0，不能瞎猜。 */
    @Test fun 只有一次采样时不瞎猜() {
        assertEquals(0, Health.stealOf("cpu  1 2 3 4 5 6 7 8", ""))
        assertEquals(0, Health.stealOf("", ""))
        // 两次一模一样（机器完全没动）→ 除零要兜住
        val same = "cpu  1 2 3 4 5 6 7 8 9 0"
        assertEquals(0, Health.stealOf(same, same))
    }

    @Test fun 读不出来返回null不编数() {
        assertNull(Health.parse(""))
        assertNull(Health.parse("bash: 什么鬼"))
        // ⚠️ 体检报告里编一个数出来比不显示危险 —— 用户会照着它做决定
        assertNull(Health.parse("#load\n\n#cpu\n16"))
    }

    @Test fun 那台卡住的机器分数应该很难看() {
        val r = Health.score(Health.parse(real)!!)
        assertEquals(45, r.score)
        assertEquals("strained", Health.verdict(r.score))
        // steal 和 swap 都要被点出来
        assertTrue(r.issues.any { it.code == "steal" })
        assertTrue(r.issues.any { it.code == "swap" })
    }

    /** ⚠️ steal 和磁盘满**不能标成 fixable** —— 一键收拾解决不了它们，
     *  标错了用户按一次没反应只会更困惑。 */
    @Test fun 修不了的不许标成能修() {
        val r = Health.score(Health.parse(real)!!)
        assertFalse("steal 在虚拟机里面无解", r.issues.first { it.code == "steal" }.fixable)
    }

    @Test fun 健康的机器应该满分() {
        val ok = Health.Vitals(
            cores = 16, load1 = 1.0,
            memTotalKb = 16_000_000, memAvailKb = 12_000_000,
            swapTotalKb = 8_000_000, swapFreeKb = 8_000_000,
            steal = 0, diskUsedPct = 20,
        )
        val r = Health.score(ok)
        assertEquals(100, r.score)
        assertTrue(r.issues.isEmpty())
        assertEquals("easy", Health.verdict(r.score))
    }

    /** 没有 swap 分区的机器不能因为除零而算出奇怪的数。 */
    @Test fun 没有swap分区不炸() {
        val v = Health.Vitals(4, 1.0, 8_000_000, 6_000_000, 0, 0, 0, 30)
        assertEquals(0, v.swapUsedPct)
        assertEquals(100, Health.score(v).score)
    }

    // ────────── 一键收拾 ──────────

    @Test fun 只挑白名单里的东西() {
        val out = listOf(
            "111\t3200000\t7200\tgradle",
            "222\t120000\t900\trg",
            "333\t50000\t120\tkotlin",
        ).joinToString("\n")
        val j = Health.junkFrom(out)
        assertEquals(listOf(111, 222, 333), j.map { it.pid })
        assertEquals(setOf("gradle", "rg", "kotlin"), j.map { it.what }.toSet())
    }

    /** 认不出的行直接跳过 —— **宁可少杀不可错杀**。 */
    @Test fun 认不出的行不当成目标() {
        assertTrue(Health.junkFrom("乱七八糟").isEmpty())
        assertTrue(Health.junkFrom("abc\tdef\tghi\tjkl").isEmpty())   // pid 不是数字
        assertTrue(Health.junkFrom("111\t222").isEmpty())             // 字段不够
    }

    /**
     * ⚠️ **绝不能生成杀 pid 1 的命令**（那是 init，杀了机器就没了）。
     * 也不能在没有目标时生成一条空的 kill。
     */
    @Test fun 不许杀init也不许空跑() {
        assertNull(Health.killCommand(emptyList()))
        assertNull(Health.killCommand(listOf(Health.Junk(1, 0, 0, "gradle"))))
        val cmd = Health.killCommand(listOf(Health.Junk(1, 0, 0, "gradle"), Health.Junk(42, 0, 0, "rg")))!!
        assertFalse("命令里出现了 pid 1", Regex("""\b1\b""").containsMatchIn(cmd.substringAfter("kill -TERM").substringBefore(" 2>")))
        assertTrue("42" in cmd)
    }

    /** ⚠️ 先 TERM 再 KILL：Gradle 收到 TERM 会把缓存写完再退，直接 -9 会留下坏缓存。 */
    @Test fun 先好好说再动手() {
        val cmd = Health.killCommand(listOf(Health.Junk(42, 0, 0, "gradle")))!!
        assertTrue("kill -TERM" in cmd)
        assertTrue("kill -KILL" in cmd)
        assertTrue("TERM 必须在 KILL 前面", cmd.indexOf("-TERM") < cmd.indexOf("-KILL"))
    }

    /**
     * ⚠️ **只杀勾中的那几类。** 界面按类别归堆、逐类勾选，
     * 传给 [Health.killCommand] 的必须是**筛过的子集** ——
     * 传全表就等于「勾选是个摆设」，而用户以为自己保住了那个搜索。
     */
    @Test fun 只杀勾中的那类() {
        val all = listOf(
            Health.Junk(11, 1000, 7200, "gradle"),
            Health.Junk(12, 2000, 7200, "gradle"),
            Health.Junk(21, 500, 900, "rg"),
        )
        // 只勾了 gradle
        val chosen = all.filter { it.what in setOf("gradle") }
        val cmd = Health.killCommand(chosen)!!
        assertTrue("11" in cmd && "12" in cmd)
        assertFalse("没勾的 rg 被杀了", Regex("""\b21\b""").containsMatchIn(cmd))
    }

    /** 一个都没勾 → 不该生成命令（界面上那个按钮也是禁用的）。 */
    @Test fun 一个没勾就不出命令() {
        assertNull(Health.killCommand(emptyList()))
    }

    // ────────── 闲置会话 / 跑飞进程 ──────────

    /**
     * ⚠️ **扫描命令必须把自己排除掉。**
     *
     * 原来那版栽在这儿：awk 的程序正文里写着 `/GradleDaemon/`，而 `ps -eo args=`
     * **会把这条 awk 自己列出来** —— 于是它把自己认成「Gradle 编译守护进程」。
     * 用户手机上看到的「可以收拾 1 类 · 约 7 MB」，收的就是**它自己那三个临时 shell**。
     * 一键修复因此从上线起就是空的。
     *
     * 现在靠一个只可能出现在扫描命令自身里的记号自排除。这条断言钉住那个记号，
     * 谁把它删了这里立刻红。
     */
    @Test fun 扫描命令不能把自己算进去() {
        assertTrue("自排除的记号没了", "yxiscan" in Health.SCAN_COMMAND)
        assertTrue("少了跳过自己那一行", "line ~ /yxiscan/) next" in Health.SCAN_COMMAND)
    }

    /** 会话那类多一个字段（会话名），别把它当成坏行丢掉。 */
    /**
     * ⚠️⚠️ **「闲置」不许只看 tmux 的活动时间。**
     *
     * 那个值在没人 attach 时不更新 —— 一个正跑着的会话会显示「3 天没动」。
     * 真事：按它清理，杀掉了用户正在用的 `cc-hexingyang`（tmux 说闲了 3.9 天，
     * 转录 15 分钟前还在写）。**让「一键收拾」杀掉用户正在跑的活，是这功能最坏的失败方式。**
     * 下面每一条少一件，它就会杀活人。
     */
    @Test fun 闲置不许只看tmux活动时间() {
        val c = Health.SCAN_COMMAND
        assertTrue("没去读转录的 mtime", "projects" in c)
        assertTrue("没按 sessionId 找转录", "sessionId" in c)
        assertTrue("没读 Claude Code 自己的会话表", "sessions" in c)
        assertTrue("没排除正在忙的", "busy" in c)
        assertTrue("没排除正等你回答的", "waiting" in c)
        assertTrue("没取 max(tmux 活动, 转录 mtime)", "if (f[2]+0 > last) last = f[2]+0" in c)
    }

    @Test fun 会话行五个字段也认() {
        val j = Health.junkFrom("0\t2100000\t1468800\tidle\tcc-文件")
        assertEquals(1, j.size)
        assertEquals("idle", j[0].what)
        assertEquals("cc-文件", j[0].session)
        assertEquals(1468800L, j[0].ageSec)     // 「多久没动过」，不是「跑了多久」
    }

    /**
     * ⚠️ 缺了关键字段的行**整行丢掉**，别生成一条杀不掉或者乱杀的项：
     * 没有会话名的 idle 行会变成 `kill-session -t ''`，pid 是 0 的进程行更糟。
     */
    @Test fun 缺字段的行不生成目标() {
        assertTrue(Health.junkFrom("0\t100\t100\tidle\t").isEmpty())      // idle 没名字
        assertTrue(Health.junkFrom("0\t100\t100\thog").isEmpty())          // 进程行 pid=0
        assertTrue(Health.junkFrom("111\t100\t100\t").isEmpty())           // 没有类别
    }

    /**
     * ⚠️ **会话按名字收，不按 pid 杀。**
     * 一个会话底下是「shell → claude → 一堆子进程」，挨个 kill 会把 shell 杀在 claude 前头，
     * 留下一个挂在 init 底下、内存照占的孤儿。
     *
     * ⚠️ 而且必须**先 `cloud-forget`**：那套机器上的 `cloud-watchdog` 每 15 秒
     * 把「登记过但没在跑」的会话 `claude --resume` 拉回来，光 kill-session 十几秒就复活。
     */
    @Test fun 闲置会话走会话那条路() {
        val cmd = Health.killCommand(listOf(Health.Junk(0, 2100000, 1468800, "idle", "cc-文件")))!!
        assertTrue("cloud-forget" in cmd)
        assertTrue("cloud-forget 不在时要退回 kill-session", "tmux kill-session" in cmd)
        assertFalse("会话不该走 kill pid 那条路", "kill -TERM" in cmd)
    }

    /** 会话名里带单引号不能把命令劈开。 */
    @Test fun 会话名里的单引号要转义() {
        val cmd = Health.killCommand(listOf(Health.Junk(0, 0, 0, "idle", "it's")))!!
        assertTrue("单引号没转义，命令会被劈开", """'it'\''s'""" in cmd)
    }

    /** 两类混着勾：进程走 kill、会话走 forget，各走各的，一条命令里都要有。 */
    @Test fun 进程和会话可以一起收() {
        val cmd = Health.killCommand(listOf(
            Health.Junk(42, 1000, 7200, "gradle"),
            Health.Junk(0, 2000, 400000, "idle", "cc-旧的"),
        ))!!
        assertTrue("kill -TERM 42" in cmd)
        assertTrue("cc-旧的" in cmd)
    }
}
