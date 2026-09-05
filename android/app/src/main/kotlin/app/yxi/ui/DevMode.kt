package app.yxi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.yxi.BuildConfig
import app.yxi.ssh.Host
import app.yxi.ssh.HostStore
import app.yxi.ssh.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开发者模式 —— **做它的唯一理由是：出问题时我在服务器这头是瞎的。**
 *
 * 用户报「连不上」，我能查的只有服务器：端口开着、外部机器连得通、日志里
 * 连他的失败记录都没有。**包根本没飞到**，那手机那头发生了什么，
 * 只能靠用户转述一句话 —— 而决定性的信息（超时还是拒绝、DNS 还是 TCP、
 * 哪个端口通哪个不通、WiFi 还是移动网络）全在那句话之外。
 * 让手机把这些自己测一遍、一键复制，比来回猜十轮快得多。
 *
 * ⚠️ **诊断文本里绝不能出现密码、私钥。** 它是给人贴到聊天里的，
 * 贴出去就等于公开。密码只报「有没有」，密钥只报指纹。
 */
object DevMode {

    // ── 口令闸门 ──────────────────────────────────────────────────
    // ⚠️ 仓库里只放哈希。口令本身不进 git —— 这个仓库虽然是私有的，
    //    但「私有」是个会变的属性，明文一旦提交就永远在历史里。
    private const val HASH = "6cb7b27ee530c505b84a92b7edff12ae10f08be70700139ce4f654a5431aad4b"

    fun check(input: String): Boolean = sha256(input.trim()) == HASH

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private const val PREFS = "yxi-dev"
    private const val KEY_ON = "unlocked"

    fun unlocked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setUnlocked(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    // ── 日志环 ────────────────────────────────────────────────────
    /** 只留最近这些条。够看清「最后一次连接发生了什么」就行，不是审计日志。 */
    private const val CAP = 120
    private val ring = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        ring.addLast("${stamp.format(Date())} [$tag] $msg")
        while (ring.size > CAP) ring.removeFirst()
    }

    /** 把一条异常连同它整条 cause 链记下来 —— 只看最外层那个常常什么都看不出来。 */
    fun logError(tag: String, e: Throwable) {
        val chain = buildString {
            var c: Throwable? = e
            var depth = 0
            while (c != null && depth < 6) {
                if (depth > 0) append("  ← ")
                append("${c::class.java.simpleName}: ${c.message}")
                c = c.cause.takeIf { it !== c }
                depth++
            }
        }
        log(tag, chain)
    }

    @Synchronized
    fun dump(): String = if (ring.isEmpty()) "（还没有记录）" else ring.joinToString("\n")

    @Synchronized
    fun clearLog() = ring.clear()

    fun copy(ctx: Context, text: String, label: String = "Yxi 诊断") {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    // ── 诊断 ──────────────────────────────────────────────────────
    /**
     * 一步一步走完整条连接路径，每步单独计时、单独报错。
     *
     * 关键是**最后那组端口探针**：同一个 IP 上多个端口一起试，
     * 一眼就能看出「整个 IP 不通」还是「只有某个端口被掐」——
     * 这两件事的修法完全不同，而 App 平时给的错误信息区分不了。
     */
    /**
     * 「灵动岛 / 灵动胶囊」到底认不认我们那条常驻通知。
     *
     * ⚠️ **这件事只能问手机，问不了代码。** `setRequestPromotedOngoing` 是**请求**：
     * 系统不给就当没看见 —— 不报错、不抛异常、什么都不发生。
     * 所以这里不猜，直接去 `getActiveNotifications()` 里把那条捞出来，
     * 看系统有没有真的盖上 `FLAG_PROMOTED_ONGOING`（0x40000）这个章。
     *
     * ⚠️ `hasPromotableCharacteristics()` 是新系统才有的方法，用反射调 ——
     * 版本号判断在国产 ROM 上不一定可靠（改过版本号的多得是），
     * 而反射「有就调、没有就说没有」，任何系统上都不会崩。
     */
    private fun capsule(ctx: Context): String = runCatching {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val n = nm.activeNotifications.firstOrNull { it.id == 1 }?.notification
            ?: return "常驻通知不在（铃铛一台都没开？）"
        val promoted = (n.flags and 0x40000) != 0
        val can = runCatching {
            android.app.Notification::class.java.getMethod("hasPromotableCharacteristics")
                .invoke(n) as Boolean
        }.getOrNull()
        buildString {
            append(if (promoted) "✓ 系统已提升（胶囊里应该看得见）" else "✗ 系统没有提升")
            append(" · 够格? ")
            append(when (can) { true -> "是"; false -> "否"; null -> "这个系统没有这个判定（Android 16 以下）" })
            append(" · Android ${Build.VERSION.RELEASE}")
        }
    }.getOrElse { "查不了：${it.message}" }

    suspend fun diagnose(
        ctx: Context,
        host: Host?,
        store: HostStore,
        keys: KeyManager,
        /**
         * App **此刻**的连接状态（`null` = 已连上）。
         *
         * ⚠️ 没有这一行的话，报告只说明「现在新建一条连接能不能成」——
         * 而用户抱怨的恰恰是「界面上写着连不上」。上一次就吃了这个亏：
         * 诊断五项全绿，用户说「但是我们显示还是连不上」，
         * 因为界面上那句话是更早一次失败留下的、再也不会自己清掉。
         */
        uiError: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val b = StringBuilder()
            fun line(s: String = "") = b.append(s).append('\n')

            line("── Yxi 诊断 ${stamp.format(Date())} ──")
            line("App    ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
            line("设备   ${Build.BRAND} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            line("网络   ${network(ctx)}")
            line("界面   " + (uiError?.let { "✗ 此刻显示：" + it.lineSequence().first() } ?: "已连上"))
            line("公钥   ${runCatching { keys.fingerprint() }.getOrElse { "读不出来: ${it.message}" }}")
            line("胶囊   ${capsule(ctx)}")
            line()

            if (host == null) {
                line("没有选中的主机 —— 先在「主机」页加一台再来诊断。")
                return@withContext b.toString()
            }

            // ⚠️ 括号里写的是**公网地址**，所以要用 hostname —— 第一版写成 connectHost，走内网时把 100.x 标成了「公网」（审查查出）
            line("目标   ${host.username}@${host.connectHost}:${host.port}" + if (host.viaTailscale) t("  （Tailscale 内网，公网 %s）").format(host.hostname) else "")
            line("认证   " + if (host.useKey) "密钥" else "密码（${if (host.sealedPassword != null) "已保存" else "没保存"}）")
            line("指纹   " + (host.hostKey?.let { "已记住" } ?: "未记住（首次连接会问）"))
            line()

            // ① DNS
            val ips: List<String>
            val t0 = System.currentTimeMillis()
            try {
                ips = InetAddress.getAllByName(host.connectHost).map { it.hostAddress ?: "?" }
                line("① 解析地址   OK  ${System.currentTimeMillis() - t0}ms  → ${ips.joinToString(", ")}")
            } catch (e: Throwable) {
                line("① 解析地址   ✗ ${e::class.java.simpleName}: ${e.message}")
                line()
                line("地址解析不了 —— 这一栏必须是 IP 或真实域名。")
                app.yxi.ssh.HostInput.suspiciousChar(host.connectHost)?.let {
                    line("⚠️ 地址里有个字符 $it —— 多半是中文输入法打的。")
                }
                return@withContext b.toString()
            }

            // ② TCP —— 分开报「超时」和「拒绝」，这两个是完全不同的方向
            val tcp = probe(host.connectHost, host.port, 12_000)
            line("② 连 TCP     ${tcp.text}")

            if (tcp.ok) {
                // ③ SSH banner —— 能读到就说明确实是 sshd 在对面
                line("③ SSH 招呼   ${banner(host.connectHost, host.port)}")
                // ④ 认证
                line("④ 认证       ${auth(host, store, keys)}")
            } else {
                line("③ SSH 招呼   跳过（TCP 都没通）")
                line("④ 认证       跳过")
            }

            // ⑤ 端口探针 —— 判断「整个 IP 不通」还是「只有这个端口被掐」
            line()
            line("⑤ 同一个 IP 上各端口（各 6 秒）")
            val ports = linkedSetOf(host.port, 22, 8443, 8899, 443, 80)
            for (p in ports) {
                line("   :${p.toString().padEnd(5)} ${probe(host.connectHost, p, 6_000).text}")
            }
            line()
            line(verdict(host, tcp, ports.map { it to probe(host.connectHost, it, 4_000) }))
            line()
            line("── 最近日志 ──")
            line(dump())
            b.toString()
        }

    private class Probe(val ok: Boolean, val text: String)

    private fun probe(hostname: String, port: Int, timeoutMs: Int): Probe {
        val t = System.currentTimeMillis()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(hostname, port), timeoutMs)
                Probe(true, "通  ${System.currentTimeMillis() - t}ms")
            }
        } catch (e: java.net.SocketTimeoutException) {
            // ⚠️ 超时 = 包被丢了（防火墙 / 运营商屏蔽 / 路由不通），对面根本没回话
            Probe(false, "超时 ${System.currentTimeMillis() - t}ms —— 包被丢弃，多半是被挡了")
        } catch (e: java.net.ConnectException) {
            // ⚠️ 拒绝 = 包到了，对面明确说「这个端口没人听」。跟超时是两回事
            Probe(false, "拒绝 ${System.currentTimeMillis() - t}ms —— 包到了对面，但那个端口没人听")
        } catch (e: Throwable) {
            Probe(false, "✗ ${e::class.java.simpleName}: ${e.message}")
        }
    }

    private fun banner(hostname: String, port: Int): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(hostname, port), 8_000)
            s.soTimeout = 8_000
            val buf = ByteArray(255)
            val n = s.getInputStream().read(buf)
            if (n <= 0) "对面不说话（连上了但没有 SSH 招呼 —— 这个端口上跑的可能不是 sshd）"
            else String(buf, 0, n).trim().lines().firstOrNull().orEmpty()
        }
    } catch (e: IOException) {
        "✗ ${e::class.java.simpleName}: ${e.message}"
    }

    private suspend fun auth(host: Host, store: HostStore, keys: KeyManager): String {
        val cfg = store.configFor(host, keys)
            ?: return "✗ 这台主机既没勾密钥也没存密码，没法认证"
        return try {
            // 诊断走的是「只认证不建通道」，首次指纹一律放行 —— 这里要回答的是
            // 「认证过不过」。指纹信不信任是另一个问题，混进来只会盖住答案；
            // 而且诊断**不写回** hostKey（KnownHosts.add 只在真连接时才存），
            // 所以不会把一台没核对过的机器悄悄变成「已信任」
            val known = app.yxi.ssh.KnownHosts(store, host.id, object : app.yxi.ssh.TrustPrompt {
                // ⚠️ **以前这里 `return true`** —— 等于对这台主机开了一次 StrictHostKeyChecking=no，
            //    而且 jsch 在 promptYesNo 返回 true 之后会把这把密钥**写进信任库**
            //    （旧注释说「诊断不写回 hostKey」是错的，审计反编译 jsch 核对过）。
            //    后果：用户被引导去跑诊断时链路上有中间人 → 假指纹被永久钉死，
            //    密码认证的主机还会把明文密码交给对面。诊断一律**拒绝新主机**：
            //    没连过的主机先在主机页正常连一次（那里有真的指纹确认框），再来诊断。
            override fun confirmNewHost(host: String, keyType: String, fingerprint: String) = false
            })
            val s = app.yxi.ssh.SshSession(cfg, known)
            s.connect()
            s.disconnect()
            "OK —— 密钥/密码是对的"
        } catch (e: Throwable) {
            val m = generateSequence(e as Throwable?) { it.cause.takeIf { c -> c !== it } }
                .joinToString(" ← ") { "${it::class.java.simpleName}: ${it.message}" }
            if ("Auth fail" in m || "Auth cancel" in m)
                "✗ 认证被拒 —— 服务器上的 authorized_keys 里没有这把公钥（或密码不对）\n              $m"
            else "✗ $m"
        }
    }

    /** 把探针结果读成一句结论 —— 这份东西是贴给人看的，不能只堆数据。 */
    private fun verdict(host: Host, target: Probe, all: List<Pair<Int, Probe>>): String {
        val open = all.filter { it.second.ok }.map { it.first }
        return when {
            target.ok -> "结论：TCP 通了，问题不在网络 —— 看上面 ③ ④ 两步。"
            open.isEmpty() ->
                "结论：这个 IP 上一个端口都不通。要么手机根本上不了网，" +
                    "要么整台机器不可达（关机 / 防火墙全关 / 地址填错）。"
            else ->
                "结论：只有 :${host.port} 不通，而 ${open.joinToString(", ") { ":$it" }} 是通的 —— " +
                    "IP 可达，被掐的是这一个端口。移动网络屏蔽 22 出站很常见：" +
                    "把端口改成上面通的那个（服务器上要先让 sshd 也听那个端口）。"
        }
    }

    private fun network(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "读不出来"
        val n = cm.activeNetwork ?: return "没有网络"
        val c = cm.getNetworkCapabilities(n) ?: return "读不出来"
        // WiFi 还是移动网络是关键信息：运营商屏蔽只发生在移动网络上，
        // 「WiFi 能连、插卡不能连」本身就是答案
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络（运营商可能屏蔽某些端口）"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其它"
        } + if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "" else " ⚠️ 未验证（可能上不了外网）"
    }
}
