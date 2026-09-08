package app.yxi.agent

import app.yxi.ssh.SshSession
import app.yxi.agent.Tr.t

/**
 * **从机** —— 从「主机」跳一层看另一台机器（老板 2026-09-07：
 * 「服务器当主机、Mac 当从机，点进从机去看它活着的会话、开它的终端」）。
 *
 * 机制就一句话：**App 连的还是主机，所有命令前面加一层 `ssh <从机>`**。
 * 手机不需要能直接够到从机 —— 够得到主机就行，这正是「买台服务器当中转」那个场景。
 * （同一套路子微信那个功能已经在用：App → 服务器 → `ssh laptop` → Windows。）
 *
 * ⚠️ **这一层最难的不是连上，是连不上时说清为什么。** 实测踩过的三种，长得一模一样（都是「连不上」），
 * 但修法完全不同：tailnet 的访问策略挡了 TCP（Tailscale 自己的 ping 还通！）、主机的公钥没装进从机、
 * 从机上压根没有 tmux。所以 [probe] 不返回布尔，返回**一个带修法的结论**（[Result]），界面照着指导用户。
 */
object Slave {

    /** 从机上的一个会话。字段跟 [SessionProbe] 那套对齐，界面可以共用渲染。 */
    data class Sess(
        val name: String,
        val windows: Int,
        /** epoch 秒 */
        val activity: Long,
        val attached: Boolean,
        val cwd: String,
        val command: String,
    )

    sealed class Result {
        /** 通了。[sessions] 可能是空表 —— 那是「连上了但它上面没开会话」，跟连不上是两回事。 */
        data class Ok(val sessions: List<Sess>, val hasTmux: Boolean) : Result()

        /**
         * 连不上 / 用不了。[what] 一句话说清现象，[why] 说根因，[how] 是**照着做就能好**的步骤，
         * [cmd] 非空时界面给一个「复制命令」——用户拿去在能操作的那台机器上跑。
         */
        data class Trouble(val what: String, val why: String, val how: String, val cmd: String = "") : Result()
    }

    /**
     * 探一台从机。[target] 是**主机上能用的 SSH 目标**：`ssh_config` 里的别名，或者 `user@100.x.x.x`。
     *
     * 一趟往返里把该分的因都分了 —— 分开探要好几秒，而用户在等着。
     */
    suspend fun probe(alive: suspend (Long) -> SshSession?, target: String, tailscaleIp: String = ""): Result {
        // ⚠️⚠️ **必须等一条活着的连接，不能接一个抓好的 SshSession**（TROUBLESHOOTING #282）。
        //   `exec` 在连接已死时**不抛异常、返回空串** —— 手机息屏 / WiFi 切 4G 之后那条连接就是死的，
        //   空串进 [parse] 会被判成「从机那头没有任何输出」，于是**把手机自己掉线报成从机的毛病**，
        //   还给一条指向从机的修复命令。诊断页最不能犯的就是这种错。
        val s = alive(20_000) ?: return Result.Trouble(
            what = t("主机没连上"),
            why = t("从机是从主机跳过去的，主机自己都没连上时探不了。"),
            how = t("先把主机连上（回主机页下拉刷新一次），再点进从机。"),
        )
        val q = app.yxi.ssh.Shell.q(target)
        val ip = tailscaleIp.ifBlank { target }
        val out = s.exec(probeCommand(q, app.yxi.ssh.Shell.q(ip)))
        // 连接活着但一个字都没回来 = 命令发出去的瞬间断了，同样不能赖到从机头上
        if (out.isBlank()) return Result.Trouble(
            what = t("跟主机的连接断了"),
            why = t("命令发出去了，但主机一个字都没回 —— 多半是手机这头刚掉线（息屏、切网络）。"),
            how = t("等一下再点「重试」。要是一直这样，回主机页下拉刷新重连一次。"),
        )
        return parse(out, target, ip)
    }

    /**
     * 一条命令探完该分的因，用标记分段：
     * ① SSH 到底走到哪一步（`BatchMode=yes`：不通就立刻失败，别挂在密码提示上）
     * ② Tailscale 自己的 ping 通不通（**它通、而 SSH 连不上 = 访问策略挡了 22**，这是最难猜的一种）
     * ③ 顺带看从机上有没有 tmux、有哪些会话
     *
     * ⚠️ **主机可能是 macOS，命令必须是 BSD 也能跑的**（老板的场景就是「服务器当主机」，但 Mac 当主机同样得成立）。
     *   实测踩的三样：macOS **没有 `timeout`**（那是 GNU coreutils）、`tailscale` **不在非交互 shell 的 PATH 里**
     *   （App Store 版连 `/usr/local/bin` 软链都没有，CLI 只在 app 包内）、从机侧 `tmux`/`claude` 常在 `~/.local/bin`。
     *   所以：端口通不通**不另外探**，直接从 ssh 自己的报错里读（见 [parse]）——少一次往返，还天然可移植；
     *   ping 用 `tailscale` 自带的 `--timeout`，不借 `timeout`；PATH 主机侧和从机侧各补一次。
     */
    fun probeCommand(quotedTarget: String, quotedIp: String): String =
        "export PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
            "echo __SSH__; " +
            "ssh -o ConnectTimeout=6 -o ServerAliveInterval=3 -o ServerAliveCountMax=2 " +
            "-o BatchMode=yes -o StrictHostKeyChecking=accept-new -- $quotedTarget " +
            "'export PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:\$PATH\"; " +
            "echo __OK__; command -v tmux >/dev/null 2>&1 && echo __TMUX__ || echo __NOTMUX__; " +
            "tmux list-sessions -F \"#{session_windows}|#{session_activity}|#{session_attached}|#{pane_current_path}|#{pane_current_command}|#{session_name}\" 2>/dev/null" +
            "' 2>&1; " +
            "echo __TSPING__; " +
            "TS=\$(command -v tailscale || echo /Applications/Tailscale.app/Contents/MacOS/Tailscale); " +
            "[ -x \"\$TS\" ] && \"\$TS\" ping --c 1 --timeout 5s $quotedIp 2>&1 | head -1 || echo NO_TS; " +
            "echo __END__"

    /** ssh 报这些 = 根本没够到 22 端口（网络层就断了），跟「够到了但认证没过」是两回事 */
    private val UNREACHABLE = listOf(
        "timed out", "Connection refused", "No route to host", "Network is unreachable", "Host is down",
    )

    fun parse(out: String, target: String, ip: String): Result {
        val sshPart = out.substringAfter("__SSH__", "").substringBefore("__TSPING__")
        val pingPart = out.substringAfter("__TSPING__", "").substringBefore("__END__")

        val reachedShell = sshPart.contains("__OK__")
        // 端口通不通直接读 ssh 的报错 —— 「连接超时/被拒」是网络层没够到，别的（认证失败、指纹变了）都说明够到了
        val portOpen = UNREACHABLE.none { sshPart.contains(it, ignoreCase = true) }
        // ⚠️ 判据是 **"pong from"** 不是 "pong"：光判 "pong" 的话，`no pong` / `no matching peer` 这类
        //    失败输出里只要蹭到这四个字母就被当成通了，直接把「机器没开机」误诊成「ACL 挡了」——
        //    诬陷一个没问题的地方，比不诊断更糟（测试抓出来的）。
        //    实测：成功是 `pong from echomac-mini-1 (100.76.20.101) via … in 39ms`，失败是 `no matching peer`。
        val tsPongs = pingPart.contains("pong from", ignoreCase = true)

        if (reachedShell) {
            val hasTmux = sshPart.contains("__TMUX__")
            return Result.Ok(parseSessions(sshPart), hasTmux)
        }

        // ── 连不上。按「哪一层断的」分因，每一种给不同的修法 ──

        // ⚠️ 最容易被误判的一种：Tailscale 的 ping 通（它走 tailscaled 自己的通道，**不过访问策略**），
        //    而 TCP 22 不通 —— 说明网络是通的，是 tailnet 的 ACL 没放行 SSH。实测就是这个。
        if (tsPongs && !portOpen) return Result.Trouble(
            what = t("网络通，但 SSH 端口被挡住了"),
            why = t("Tailscale 自己的 ping 能通（说明两台机器在同一个内网里、线路没问题），但 22 端口连不上 —— 这是 tailnet 的**访问策略（ACL）**没放行 SSH。"),
            how = t("去 Tailscale 后台 → Access controls，在 ACL 里加一条允许 22 端口的规则，保存后一两秒生效。改完回这里再点一次。"),
            cmd = ACL_SNIPPET,
        )

        // 名字根本解析不出来 —— 跟「机器没开机」是两回事，别让人去查电源
        if (sshPart.contains("Could not resolve hostname", true) ||
            sshPart.contains("Name or service not known", true)
        ) return Result.Trouble(
            what = t("主机不认识这个名字"),
            why = t("主机上解析不出 %s 这个名字。Tailscale 的 MagicDNS 没开时，只有 100.x 的地址能用。").format(target),
            how = t("在 Tailscale 后台把 MagicDNS 打开，或者在主机的 ~/.ssh/config 里给它加一条指向 IP 的别名。"),
            cmd = "ssh -o StrictHostKeyChecking=accept-new -- ${app.yxi.ssh.Shell.q(ip)} true",
        )

        // ⚠️ 主机上压根没有 tailscale 命令时 ping 那一段是 `NO_TS` —— 这时**分不出**是 ACL 还是机器没醒，
        //    那就老实说分不出，别把「ACL 挡了」的锅甩给一台没问题的机器（这一支存在的意义就是不诬陷）。
        if (!portOpen && pingPart.contains("NO_TS")) return Result.Trouble(
            what = t("连不上这台机器"),
            why = t("22 端口不通。主机上没装 Tailscale 命令行，所以**分不出**是那台机器没醒，还是 tailnet 的访问策略挡了 SSH。"),
            how = t("① 先确认那台机器醒着、开了远程登录；② 都没问题的话，去 Tailscale 后台 → Access controls 看 ACL 有没有放行 22。"),
        )

        if (!portOpen) return Result.Trouble(
            what = t("连不上这台机器"),
            why = t("22 端口不通：要么它关机 / 睡着了，要么它的 Tailscale 没启动，要么它根本没开 SSH（远程登录）。"),
            how = t("① 确认那台机器醒着；② 它上面的 Tailscale 要处于已启动状态；③ macOS 要在「系统设置 → 通用 → 共享」里打开「远程登录」，Linux 要装并启动 sshd。"),
        )

        // 端口通但没进到 shell = 认证没过
        val authish = sshPart.contains("Permission denied", true) || sshPart.contains("publickey", true) ||
            sshPart.contains("Authentication failed", true)
        if (portOpen && authish) return Result.Trouble(
            what = t("端口通了，但主机没有登录这台机器的权限"),
            why = t("从机是**主机**跳过去的，所以要把**主机的公钥**装进从机的 authorized_keys —— 跟手机上那把公钥是两回事。"),
            how = t("在主机上取公钥，贴进从机的 ~/.ssh/authorized_keys（下面这条命令一次做完，跑之前把 %s 换成你能登录从机的方式）。").format(target),
            cmd = "ssh-copy-id -o StrictHostKeyChecking=accept-new -- ${app.yxi.ssh.Shell.q(target)}",
        )

        // ⚠️ 判据**不能是 "host key"**：`no matching host key type found. Their offer: ssh-rsa`
        //    （老 Debian / NAS / 路由器对上 OpenSSH ≥ 8.8 就是这句）里正好含这两个词，
        //    会被误判成「指纹变了、可能有人冒充」，还让人去删一条本来没错的指纹。第三次栽在松判据上了。
        val hostKeyish = sshPart.contains("HOST IDENTIFICATION", true) || sshPart.contains("has changed", true)
        if (hostKeyish) return Result.Trouble(
            what = t("这台机器的指纹变了，主机拒绝连接"),
            why = t("从机重装过系统 / 换过机器时会这样。也可能是**中间有人冒充** —— 所以默认拒绝是对的。"),
            how = t("确认确实是你自己重装的，再在主机上删掉旧指纹那一行。"),
            // ⚠️ known_hosts 是**按 ssh 当时用的那个名字**存的：我们连的是 target，只删 IP 那条等于没删。
            //    两个都删掉才不会下次又撞上（多删一条不存在的不报错）。
            cmd = "ssh-keygen -R ${app.yxi.ssh.Shell.q(target)}; ssh-keygen -R ${app.yxi.ssh.Shell.q(ip)}",
        )

        return Result.Trouble(
            what = t("连不上，原因没看明白"),
            why = sshPart.lines().firstOrNull { it.isNotBlank() && !it.startsWith("__") }?.take(160)
                ?: t("主机那头没有任何输出。"),
            how = t("把上面这句报错发给我，或者在主机上手动跑一次这条命令看完整输出。"),
            cmd = "ssh -v -- ${app.yxi.ssh.Shell.q(target)} true",
        )
    }

    /**
     * ⚠️ **会话名放在最后一个字段**：名字是用户自己起的，里面出现 `|` 完全可能，
     *   放在开头的话一个 `|` 就把后面所有字段挤位 —— 名字被截断、窗口数/时间变成垃圾，
     *   点一下还会接到**别的会话**上去。放最后 + `limit` 切分，名字里带几个 `|` 都原样保住。
     *
     * ⚠️ **信任边界**：名字会被拼进 `tmux -t …`。虽然 [attachCommand] 已经整条引用过一次，
     *   这里仍按 [app.yxi.ssh.Shell.safeName] 再拦一道 —— 跟 [SessionProbe] 那个边界同样的两道防线。
     */
    fun parseSessions(text: String): List<Sess> =
        text.lines().mapNotNull { line ->
            val p = line.trim().split('|', limit = 6)
            if (p.size < 6) return@mapNotNull null
            val name = p[5]
            if (name.isBlank() || name.startsWith("__") || !app.yxi.ssh.Shell.safeName(name)) return@mapNotNull null
            Sess(
                name = name,
                windows = p[0].toIntOrNull() ?: 1,
                activity = p[1].toLongOrNull() ?: 0L,
                // `#{session_attached}` 是**客户端个数**，不是 0/1：两个客户端连着时 == "2"
                attached = p[2] != "0",
                cwd = p[3],
                command = p[4],
            )
        }

    /** 贴进 Tailscale 后台 Access controls 的那段。写成最小可用的一条，别教人把 ACL 开成全通。 */
    private val ACL_SNIPPET = """
        // 允许 tailnet 内互相 SSH（贴进 "acls" 数组）
        {"action": "accept", "src": ["autogroup:member"], "dst": ["*:22"]}
    """.trimIndent()

    /** 在从机上开一个会话（没有就建）并接上去 —— 终端模式用这条，走主机跳过去。 */
    fun attachCommand(target: String, session: String): String {
        val n = app.yxi.ssh.Shell.q(session)
        // ⚠️ **`=` 必须在引号里面。** tmux 用 `-t =名字` 表示精确匹配，但 `=名字` 顶在引号外时
        //    **zsh 会拿它做「命令路径展开」**（`=ls` → `/bin/ls`）—— 从机的登录 shell 是 zsh 时，
        //    `-t =cc-macdemo` 直接变成 `zsh: cc-macdemo not found`，会话根本接不上（真机 E2E 抓到的）。
        val exact = app.yxi.ssh.Shell.q("=$session")
        // ⚠️ **引号只能套一层。** 原来把 `Shell.q(session)` 直接插进一段已经被单引号包住的字符串里 ——
        //    它自己的那个开引号**把外层引号闭掉了**，会话名当场落到主机 shell 的裸上下文里：
        //    从机上一个叫 `x;id;#` 的会话，点一下就在**主机**上执行了 `id`（审查实测）。
        //    正确做法是：先按从机的语义拼好整条远端命令，再整体 `Shell.q` 一次交给 ssh。
        val remote = "export PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:\$PATH\"; " +
            // ⚠️ `-t =名字` 是**精确匹配**：不加 `=` 时 tmux 走前缀/通配匹配，
            //    探测到点击之间那个会话要是没了，`has-session -t x` 会匹配上 `x-other` 并接过去。
            "tmux has-session -t $exact 2>/dev/null || tmux new-session -d -s $n; tmux attach -d -t $exact"
        // ⚠️ `-t` 不能省：不分配 tty 的话 tmux 会说 "open terminal failed: not a terminal"。
        // ⚠️ `-d` 踢掉别的客户端 —— 同一个会话被电脑上的大窗口占着时，手机这头会被撑成那个尺寸（同 Workspace 里那条）。
        // ⚠️ `--` 不能省：目标名来自对方自报的主机名，`-o…` 开头的名字不挡就是给 ssh 塞选项。
        return "ssh -t -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new -- " +
            app.yxi.ssh.Shell.q(target) + " " + app.yxi.ssh.Shell.q(remote)
    }
}
