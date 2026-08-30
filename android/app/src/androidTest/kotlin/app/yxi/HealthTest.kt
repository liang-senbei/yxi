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
}
