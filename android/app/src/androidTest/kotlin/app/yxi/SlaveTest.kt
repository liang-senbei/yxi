package app.yxi

import app.yxi.agent.Slave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从机探测的**分因诊断**。
 *
 * ⚠️ 这几种现象在界面上长得一模一样（都是「连不上」），但修法完全不同 ——
 * 分错因的代价是让人去改一个根本没问题的地方。样本取自 2026-09-07 在 Mac mini 上实测的真输出。
 */
class SlaveTest {

    // ⚠️ 端口通不通不再单独探（主机可能是 macOS，那边没有 `timeout` / `/dev/tcp`）——
    //    parse 从 ssh 自己的报错里读，所以样本里 ssh 那段的措辞就是判据。
    private fun out(ssh: String, ping: String) = "__SSH__\n$ssh\n__TSPING__\n$ping\n__END__"

    @Test fun 通了_列出会话() {
        val r = Slave.parse(
            out(
                "__OK__\n__TMUX__\n1|1788768927|0|/Users/echo/mac-demo|zsh|cc-macdemo\n2|1788768000|1|/tmp|claude|cc-two", "pong from echomac-mini-1 (100.76.20.101) via …",
            ),
            "echomac-mini-1", "100.76.20.101",
        )
        val ok = r as Slave.Result.Ok
        assertTrue(ok.hasTmux)
        assertEquals(2, ok.sessions.size)
        assertEquals("cc-macdemo", ok.sessions[0].name)
        assertEquals("/Users/echo/mac-demo", ok.sessions[0].cwd)
        assertEquals(false, ok.sessions[0].attached)
        assertEquals(true, ok.sessions[1].attached)
        assertEquals(2, ok.sessions[1].windows)
    }

    /** 连上了但没开会话 —— 跟「连不上」是两回事，不能报成故障 */
    @Test fun 通了但没有会话() {
        val r = Slave.parse(out("__OK__\n__TMUX__", "pong from x"), "mac", "100.1.1.1")
        val ok = r as Slave.Result.Ok
        assertTrue(ok.hasTmux); assertTrue(ok.sessions.isEmpty())
    }

    @Test fun 通了但没装tmux() {
        val r = Slave.parse(out("__OK__\n__NOTMUX__", "pong from x"), "mac", "100.1.1.1")
        val ok = r as Slave.Result.Ok
        assertTrue(!ok.hasTmux); assertTrue(ok.sessions.isEmpty())
    }

    /**
     * **最难猜的一种，也是实测撞到的那种**：Tailscale 的 ping 通（它不过 ACL），TCP 22 不通。
     * 必须指向「tailnet 的访问策略」，而不是「机器没开机」。
     */
    @Test fun ping通但端口不通_要指向ACL() {
        val r = Slave.parse(
            out("ssh: connect to host 100.76.20.101 port 22: Connection timed out", "pong from echomac-mini-1 (100.76.20.101) via 39.171.214.208:15716 in 39ms"),
            "echomac-mini-1", "100.76.20.101",
        )
        val t = r as Slave.Result.Trouble
        // ACL 这一支的稳定特征：给一条含 `:22` 的可粘规则（别的支都不给）
        assertTrue("没给可粘的 ACL 规则", t.cmd.contains(":22") && t.cmd.contains("accept"))
    }

    /**
     * ping 也不通 = 机器 / Tailscale / sshd 三选一，**不能诬陷成 ACL**。
     * ⚠️ 断言不比对文案：`t()` 在英文环境会把中文翻过去，比中文串会假红（第一版就这么红了一条）。
     *    比的是**分因的稳定特征**：ACL 那一支必然给一条含 `:22` 的可粘规则，这一支没有。
     */
    @Test fun 端口和ping都不通_不该说是ACL() {
        // 样本用 tailscale 真实的失败输出（实测：探一个不存在的节点就是这句）
        val t = Slave.parse(
            out("ssh: connect to host … timed out", "2026/09/07 17:37:38 no matching peer"),
            "mac", "100.1.1.1",
        ) as Slave.Result.Trouble
        assertTrue("被误判成 ACL 了（给了 ACL 规则）", !t.cmd.contains(":22"))
        assertTrue(t.cmd.isEmpty())          // 这一支没有可复制的命令：要用户自己去开机 / 开远程登录
        assertTrue(t.what.isNotBlank() && t.why.isNotBlank() && t.how.isNotBlank())
    }

    /** 回归钉子：`no pong` 里蹭到 "pong" 四个字母，第一版判据 contains("pong") 把它当成了通 → 误诊成 ACL */
    @Test fun 含pong字样的失败输出_不算通() {
        val t = Slave.parse(out("timed out", "no pong"), "mac", "100.1.1.1") as Slave.Result.Trouble
        assertTrue("又被 'no pong' 骗了", !t.cmd.contains(":22"))
    }

    @Test fun 端口通但认证没过_要说主机的公钥() {
        val r = Slave.parse(
            out("echo@100.1.1.1: Permission denied (publickey).", "pong from x"),
            "echo@100.1.1.1", "100.1.1.1",
        )
        val t = r as Slave.Result.Trouble
        // 认证这一支的稳定特征：给 ssh-copy-id（把**主机**的公钥装进从机）
        assertTrue("没给装公钥的命令：${t.cmd}", t.cmd.startsWith("ssh-copy-id"))
    }

    @Test fun 指纹变了_给删指纹的命令() {
        val r = Slave.parse(
            out("@@@ WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! @@@", "pong from x"),
            "mac", "100.1.1.1",
        )
        val t = r as Slave.Result.Trouble
        assertTrue(t.cmd.contains("ssh-keygen -R") && t.cmd.contains("100.1.1.1"))
    }

    @Test fun 没头没尾的输出_兜底不崩() {
        val t = Slave.parse("", "mac", "100.1.1.1") as Slave.Result.Trouble
        assertTrue(t.what.isNotBlank() && t.how.isNotBlank())
    }

    /** 会话行少字段 / 是标记行时不能混进列表 */
    @Test fun 坏行不混进会话列表() {
        val s = Slave.parseSessions("__OK__\n__TMUX__\n\nbad|line\n1|100|0|/tmp|zsh|cc-x")
        assertEquals(1, s.size); assertEquals("cc-x", s[0].name)
    }

    /** 从机可能是 macOS：tmux / claude 常在 ~/.local/bin，非交互 shell 的 PATH 里没有 */
    @Test fun 探测命令要自己补PATH() {
        val c = Slave.probeCommand("'mac'", "'100.1.1.1'")
        assertTrue(c.contains(".local/bin"))
        assertTrue(c.contains("homebrew/bin"))
        assertTrue("必须 BatchMode，否则会挂在密码提示上", c.contains("BatchMode=yes"))
    }

    /** 主机也可能是 macOS：那边没有 `timeout`（GNU coreutils），`tailscale` 也不在 PATH 里 */
    @Test fun 探测命令在mac当主机时也要能跑() {
        val c = Slave.probeCommand("'mac'", "'100.1.1.1'")
        // ⚠️ 判「命令位置上的 timeout」，不是判子串 —— tailscale 自己的 `--timeout 5s` 里就含 "timeout "
        assertTrue("macOS 没有 timeout 这个命令", !Regex("""(^|[;&|(] *)timeout """).containsMatchIn(c))
        assertTrue("BSD 上别指望 /dev/tcp 探端口", !c.contains("/dev/tcp"))
        assertTrue("找不到 tailscale 就没法分 ACL 那一支", c.contains("/Applications/Tailscale.app"))
        assertTrue("主机侧的 PATH 也得补", c.startsWith("export PATH="))
    }

    @Test fun 接终端的命令_要有tty和踢人() {
        val c = Slave.attachCommand("mac", "cc-demo")
        assertTrue("没有 -t 会报 not a terminal", c.contains("ssh -t"))
        assertTrue("没有 -d 会被电脑端撑大尺寸", c.contains("attach -d"))
        assertTrue(c.contains("has-session"))
        // ⚠️ `=` 要在**引号里面**：顶在引号外时 zsh 会拿 `=名字` 做命令路径展开（`=ls` → `/bin/ls`），
        //    从机是 zsh 的话直接 `cc-demo not found`（真机 E2E 抓到的）。
        assertTrue("不加 = 的话 tmux 走前缀匹配，会接到别的会话上", c.contains("'=cc-demo'"))
        assertTrue("= 顶在引号外面会被 zsh 展开", !c.contains("-t ='"))
    }

    /**
     * ⚠️⚠️ **命令注入**（审查实测抓到的）：第一版把 `Shell.q(会话名)` 插进一段**已经被单引号包住**的字符串里，
     * 它自己的开引号把外层引号闭掉了 —— 从机上一个叫 `x;id;#` 的会话，点一下就在**主机**上执行了 `id`。
     * 判据：整条命令里，除了最外层那对，**危险字符不能落在引号外面**。
     */
    @Test fun 会话名里的分号不能跑到主机上去() {
        val c = Slave.attachCommand("mac", "x;id;#")
        // 按 POSIX 的引用规则扫一遍：引号外的 `\x` 是转义（`'"'"\''"'"'` 那种序列就是这么拼的），
        // 危险字符只要落在引号外面，就是漏到主机 shell 里去了
        var i = 0
        var inQ = false
        var leaked = false
        while (i < c.length) {
            val ch = c[i]
            when {
                ch == '\'' -> { inQ = !inQ; i++ }
                !inQ && ch == '\\' -> i += 2
                !inQ && ch in ";&|`$" -> { leaked = true; i++ }
                else -> i++
            }
        }
        assertTrue("会话名漏到主机 shell 的裸上下文里了：$c", !leaked)
        assertTrue("引号没配平：$c", !inQ)
    }

    /** 主机名来自对方自报，`-` 开头的名字不挡就是给 ssh 塞选项（`-oProxyCommand=…`） */
    @Test fun 目标名要有双横线挡着() {
        assertTrue(Slave.attachCommand("-oProxyCommand=touch /tmp/x", "s").contains(" -- "))
        assertTrue(Slave.probeCommand("'-oProxyCommand=x'", "'100.1.1.1'").contains(" -- "))
    }

    /** `#{session_attached}` 是客户端个数：两个客户端连着时是 "2"，不是 "1" */
    @Test fun 两个客户端连着也算已连() {
        val s = Slave.parseSessions("1|100|2|/tmp|zsh|cc-x").single()
        assertTrue(s.attached)
    }

    /**
     * 会话名里带 `|`：名字放在**最后一个字段**，所以它只会被 `safeName` 整条丢掉（fail-closed），
     * 而**不会**把窗口数 / 时间 / 路径挤位。
     * ⚠️ 名字要是搁在第一个字段，`cc|x|y` 会被截成 `cc`，后面的字段整体错一格 ——
     *   于是列表上显示一个叫 `cc` 的会话，点下去 attach 到的是另一个东西。
     */
    @Test fun 会话名里带竖线_整条丢掉而不是错位() {
        assertTrue(Slave.parseSessions("3|100|0|/tmp|zsh|cc|x|y").isEmpty())
        // 同一批里正常的那条不受影响 —— 错位的话它会跟着一起坏
        val ok = Slave.parseSessions("3|100|0|/tmp|zsh|cc|x|y\n2|200|0|/srv|claude|cc-good").single()
        assertEquals("cc-good", ok.name)
        assertEquals(2, ok.windows)
        assertEquals("/srv", ok.cwd)
    }

    /** 危险名字整条丢掉 —— 跟 SessionProbe 那个信任边界同样的两道防线 */
    @Test fun 危险会话名整条丢掉() {
        assertTrue(Slave.parseSessions("1|100|0|/tmp|zsh|bad\u0007name").isEmpty())
    }

    /** `no matching host key type found` 里含 "host key" —— 不能判成「指纹变了、可能有人冒充」 */
    @Test fun 协商不出host_key算法_不是指纹变了() {
        val t = Slave.parse(
            out("Unable to negotiate with 100.1.1.1 port 22: no matching host key type found. Their offer: ssh-rsa", "pong from x"),
            "mac", "100.1.1.1",
        ) as Slave.Result.Trouble
        assertTrue("被误判成指纹变了，让人去删一条没错的指纹", !t.cmd.contains("ssh-keygen -R"))
    }

    /** 指纹真变了的时候，两个名字都要删：known_hosts 是按 ssh 当时用的那个名字存的 */
    @Test fun 删指纹要连别名一起删() {
        val t = Slave.parse(
            out("@@@ WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! @@@", "pong from x"),
            "echomac-mini-1", "100.1.1.1",
        ) as Slave.Result.Trouble
        assertTrue(t.cmd.contains("echomac-mini-1") && t.cmd.contains("100.1.1.1"))
    }

    /** 主机上没有 tailscale 命令时**分不出**是 ACL 还是机器没醒 —— 那就别硬扣一个因 */
    @Test fun 没装tailscale就别硬说是哪一种() {
        val t = Slave.parse(out("ssh: connect to host … timed out", "NO_TS"), "mac", "100.1.1.1") as Slave.Result.Trouble
        assertTrue("没给 ACL 规则是对的", !t.cmd.contains(":22"))
        assertTrue("要说清是分不出，不是一口咬定", t.why.contains("分不出"))
    }

    /** 名字解析不了 ≠ 机器没开机 */
    @Test fun 名字解析不了_要说MagicDNS() {
        val t = Slave.parse(out("ssh: Could not resolve hostname mac: nodename nor servname provided", "NO_TS"), "mac", "100.1.1.1")
            as Slave.Result.Trouble
        assertTrue(t.what.contains("名字") || t.why.contains("MagicDNS"))
    }
}

/** macOS 上 tailscale 不在非交互 shell 的 PATH 里（App Store 版只在 app 包内），实测被报成「没装」 */
class TailscaleCmdTest {
    @Test fun 取内网设备的命令_要能在mac上找到tailscale() {
        val c = app.yxi.agent.TailscaleStatus.CMD
        assertTrue(c.contains("/Applications/Tailscale.app/Contents/MacOS/Tailscale"))
        assertTrue(c.contains("/usr/local/bin"))
        assertTrue(c.contains("status --json"))
    }
}
