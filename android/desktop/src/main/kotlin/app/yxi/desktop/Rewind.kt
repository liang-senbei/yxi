package app.yxi.desktop

import app.yxi.agent.Rewind
import app.yxi.agent.SessionProbe
import app.yxi.agent.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * 回溯的编排层：把 [Rewind]（纯命令/解析）接到桌面这条 [Conn] 上跑。
 *
 * **不碰 ChatPane**（root 负责 UI 接线）——这里只暴露挂起函数。
 *
 * 老板令红线（谁改谁负责）：
 *  1. **不自动退出生产 pane** —— [rewind] 全程只读 + 一次 print 模型调用，
 *     pane 里跑着的原 claude 一根手指头都不动；载入新分支是 UI 拿着 [RewindController.Report]
 *     问过用户之后**单独调 [relaunch]** 的事。
 *  2. **不猜保留、不悄悄降级** —— 原启动上下文靠 [Rewind.captureCommand] 从 /proc 明读
 *     （argv 进服务器 600 临时文件，只回放白名单旗标 model/effort/name，凭据没有出口）；
 *     [Rewind.Capture.others] 非空且用户没选分支模式 → **在模型调用前**就拒绝（审查定下的
 *     两阶段收敛：一切拒绝都发生在花钱之前），清单结构化交回 UI 走分支模式（fork 新会话）。
 *  3. **不走 shell 函数 claude** —— 所有 claude 调用都用 /proc 解析出的真实 binary。
 *
 * 流程（准备 / 执行两阶段，审查收敛版）：
 *
 * ```
 * [rewind] —— 全程在 [delivery] 锁内
 *   准备（全只读，拒绝全在这里）：
 *     校验 Plan → （可选）[RewindTargets] 的 inspect 结果交叉核对
 *       → 门禁（空闲 + claude + 会话在；记下 runtimeId / cwd）
 *       → 结构核对：source/anchor/target 三 UUID 字段级匹配（Rewind.verifyCommand）
 *       → 捕获原进程上下文（Rewind.captureCommand → /proc argv → 600 临时文件）
 *       → 模式裁定：原地模式要求 others 为空，非空 → Failed("unpreserved")，模型一个 token 都没花
 *   执行（唯一模型调用）：
 *     → exec Rewind.command（cd 会话 cwd + timeout 上限 + 原 model/effort + 真 binary，无绕过）
 *     → Report（带 runtimeId / cwd，root 原样传给 [relaunch]）。pane 没动过。
 * [relaunch]（UI 问过用户才调，同样在 [delivery] 锁内）
 *     → 门禁再跑一遍（此刻还空闲吗）
 *     → Esc + /exit 干净退出 pane 里的 claude（此刻才允许碰 pane）
 *     → 等窗格回 shell —— **回不来就停**：Failed("exit-stuck")，一个键都不再发（审查定的）
 *     → send-keys Rewind.relaunchCommand：**复核与投递同一条 exec** ——
 *       服务器侧先比 runtimeId + paneId（同名重建/pane 顶包当场拒绝），过了才投
 *       `cd <cwd> && 真 binary --resume + 白名单旗标`
 * ```
 *
 * ⚠️ **[delivery] 锁**：[rewind]/[relaunch]/[cleanup] 串行 —— 投递按键绝不能跟自己的
 * 其他操作交错。跟**消息队列**的 send-keys 竞争由两道闸挡：门禁要求会话 Idle
 * （队列有活 = Working，进不来）+ [Rewind.relaunchCommand] 的复核与投递原子；
 * root 接线时也别在 relaunch 进行中往同一会话排队新消息。
 *
 * ⚠️ **不吞取消**：snapshot 等 suspend 调用只捕一般异常，`CancellationException`
 * 原样重抛（审查指出的 runCatching 吞取消）。连接半断（exec 返回空/残缺）由各
 * parse 的 fail-closed 代号兜住，不猜成功。
 *
 * ⚠️ [rewind] 执行步占着这条连接的 exec 通道直到模型答完或 [Rewind.PRINT_TIMEOUT_SEC]
 * 到点（chanLock 串行）—— 期间这个主机的会话列表刷新会排队等，但**有上界**。
 */
class RewindController(private val conn: Conn) {

    /** 本控制器的操作串行锁：投递按键的事一件一件来。 */
    private val delivery get() = conn.instructionDeliveryMutex

    /** 一步不缺的全流程结果。 */
    data class Report(
        /** [rewind] = print 截断轮结果；[relaunch] 拒绝时 Failed(...)、成功时 null（看 [relaunched]）。 */
        val outcome: Rewind.Outcome?,
        /** 原进程上下文（null = 捕获失败，看 [captureCode]）。 */
        val capture: Rewind.Capture?,
        /** gone / noexe / notag / badcap —— capture 为 null 时的原因。 */
        val captureCode: String? = null,
        /** 结构化「无法保留」清单：未回放的旗标名（如 `--dangerously-skip-permissions`）。 */
        val unpreserved: List<String> = emptyList(),
        /** [relaunch] 专用：pane 里的 claude 是否干净退出。 */
        val exited: Boolean = false,
        /** [relaunch] 专用：重启命令是否已投进 pane。 */
        val relaunched: Boolean = false,
        /** 真 binary 直拉不经 cloud-enter 函数 = watchdog 登记丢了，如实告诉 UI。 */
        val registryLost: Boolean = false,
        /** 门禁 snapshot 时的 [app.yxi.agent.Session.runtimeId]；[relaunch] 要原样传回复核。 */
        val runtimeId: String? = null,
        /** 门禁 snapshot 时的会话 cwd；[relaunch] 要原样传回（pane 里先 cd 到它）。 */
        val cwd: String? = null,
    )

    /**
     * 回溯本体：准备（核对/捕获/模式裁定）→ 执行（print 截断轮）。**不碰 pane。**
     *
     * @param sessionName tmux 会话名（`cc-<目录>`）。
     * @param plan 已过 [Rewind.validate] 的截断计划。
     * @param inspected root 侧 [RewindTargets.inspect] 的只读解析结果（可选但**强烈建议传**）：
     *   这里会拿它跟 plan 交叉核对（同一会话、同一消息、anchor = 其 parent；
     *   目标之后还有别的轮次 = 多轮回退，plan 里就不许带 drops 声明 —— 带了当场拒）。
     *   核对不齐当场拒，执行前仍有 [Rewind.verifyCommand] 对转录文件二次复核
     *   （老板令：执行仍须复核）。
     */
    // ⚠️ internal：参数带 root 的 internal RewindTarget（public 会编译不过）；
    //    调用方 ChatPane 就在同模块里，不受影响。
    internal suspend fun rewind(sessionName: String, plan: Rewind.Plan, inspected: RewindTarget? = null): Report =
        delivery.withLock {
            Rewind.validate(plan)?.let {
                return@withLock Report(Rewind.Outcome.Failed(it), capture = null)
            }
            // ── 跟 root 的 inspect 结果交叉核对（全在模型调用之前）。
            if (inspected != null) {
                if (inspected.sessionId != plan.sessionId || inspected.messageUuid != plan.targetUuid)
                    return@withLock Report(Rewind.Outcome.Failed("target-mismatch"), capture = null)
                val parent = inspected.parentUuid
                    ?: return@withLock Report(Rewind.Outcome.Failed("first"), capture = null)
                if (parent != plan.anchorUuid)
                    return@withLock Report(Rewind.Outcome.Failed("target-mismatch"), capture = null)
                // 多轮（目标之后还有别的轮次）必须省略 drops：带声明 CLI 守卫必拒
                // （实测原话 range contains a user entry not attributable…），提前拦省一趟调用。
                if (inspected.laterUserMessages > 0 && plan.dropsTurnUuid != null)
                    return@withLock Report(Rewind.Outcome.Failed("later"), capture = null)
            }

            // ── 门禁：列表里认得出这个会话、是 claude、且空闲（不在干活、没有等你答的选择器）。
            //    ⚠️ 不用 runCatching：吞掉 CancellationException 是 bug（审查指出的）。
            val snap = try {
                SessionProbe.snapshot(conn.ssh)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (snap == null) return@withLock Report(Rewind.Outcome.Failed("probe"), capture = null)
            val s = snap.firstOrNull { it.name == sessionName }
                ?: return@withLock Report(Rewind.Outcome.Failed("no-session"), capture = null)
            if (s.isCodex) return@withLock Report(Rewind.Outcome.Failed("codex"), capture = null)
            if (s.state == SessionState.Working || s.state == SessionState.NeedsYou)
                return@withLock Report(Rewind.Outcome.Failed("busy"), capture = null)

            // ── 结构核对：source jsonl 在、anchor/target 字段级精确匹配（父子关系 + type=user +
            //    非 isMeta）。UI 传来的 sourceUuid 哪怕来自旧转录/另一会话，这里也对不上而拒绝。
            Rewind.parseVerify(conn.ssh.exec(Rewind.verifyCommand(s.cwd, plan)))?.let {
                return@withLock Report(Rewind.Outcome.Failed(it), capture = null, runtimeId = s.runtimeId, cwd = s.cwd)
            }

            // ── 捕获原进程上下文（只读 /proc，不碰 environ；值不回传，只回放白名单）。
            val cap = when (val c = Rewind.parseCapture(conn.ssh.exec(Rewind.captureCommand(sessionName)))) {
                is Rewind.Got -> c.capture
                is Rewind.Failed -> return@withLock Report(
                    Rewind.Outcome.Failed("capture"), capture = null, captureCode = c.code,
                    runtimeId = s.runtimeId, cwd = s.cwd,
                )
            }

            // ── 模式裁定（审查：拒绝必须发生在模型调用之前）——
            //    原地模式（fork=false）要求原启动参数可全量回放；回不去就明说，让用户选分支模式。
            if (!plan.fork && !cap.inPlaceAllowed) {
                return@withLock Report(
                    Rewind.Outcome.Failed("unpreserved"), capture = cap, unpreserved = cap.others,
                    runtimeId = s.runtimeId, cwd = s.cwd,
                )
            }

            // ── 执行：print 截断轮 —— 同一会话 cwd、原 model/effort、真 binary、timeout 有上界、零绕过。
            val out = conn.ssh.exec(Rewind.command(cap.exe, s.cwd, cap, plan))
            val outcome = if (out.isBlank()) Rewind.Outcome.Failed("exec") else Rewind.parse(out)
            Report(outcome, capture = cap, unpreserved = cap.others, runtimeId = s.runtimeId, cwd = s.cwd)
        }

    /**
     * 载入新分支：UI **问过用户**才调（[rewind] 成功后 pane 里的旧 claude 还抱着旧上下文，
     * 不重启就跟不上新分支 —— 这个决定必须让用户自己做）。
     *
     * @param sessionId 用 [Rewind.Outcome.Ok.sessionId]（fork 时是新 id，别拿旧的）。
     * @param runtimeId / cwd 从 [rewind] 的 [Report] 原样传回 —— 服务器侧投递前会拿
     *   runtimeId + paneId 复核还是**同一个实例**，同名会话被重建顶包当场拒绝（一键不发）。
     */
    suspend fun relaunch(sessionName: String, capture: Rewind.Capture, sessionId: String, runtimeId: String?, cwd: String?): Report =
        delivery.withLock {
            if (cwd.isNullOrBlank()) return@withLock Report(Rewind.Outcome.Failed("no-cwd"), capture = capture)
            // ── 老板令：回不去就明说，拒绝原地重启，给分支模式让路 —— 不悄悄降级。
            if (!capture.inPlaceAllowed) {
                return@withLock Report(Rewind.Outcome.Failed("unpreserved"), capture = capture, unpreserved = capture.others)
            }
            // ── 门禁再跑一遍：此刻还空闲吗。（不吞取消；探不了就不猜，直接拒。）
            val snap = try {
                SessionProbe.snapshot(conn.ssh)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (snap == null) return@withLock Report(Rewind.Outcome.Failed("probe"), capture = capture)
            val s = snap.firstOrNull { it.name == sessionName }
                ?: return@withLock Report(Rewind.Outcome.Failed("no-session"), capture = capture)
            if (s.state == SessionState.Working || s.state == SessionState.NeedsYou)
                return@withLock Report(Rewind.Outcome.Failed("busy"), capture = capture)

            // ── 此刻才允许碰 pane：Esc 收浮层 → /exit → 等 pane 回 shell。
            //    pane 里本来就是 shell 时跳过 —— 不然 /exit 敲进 bash 报 command not found。
            val n = sessionName.replace("'", "'\\''")
            val inClaude = s.cmd in setOf("claude", "node", "bun")
            if (inClaude) {
                conn.ssh.exec("tmux send-keys -t '$n' Escape; sleep 0.3; tmux send-keys -t '$n' '/exit' Enter")
            }
            val exited = if (inClaude) waitShell(n) else true
            // ── 审查定的硬闸：退不干净就**停在这里** —— 一个键都不再发、如实报错。
            //    （绝对不能拿着"没退干净"的 pane 硬塞 --resume，那会敲进 TUI 里当输入。）
            if (!exited) {
                return@withLock Report(Rewind.Outcome.Failed("exit-stuck"), capture = capture, exited = false)
            }
            // ── 投递：复核（runtimeId + paneId，同一条 exec 里原子完成）过了才 send-keys。
            val deliver = conn.ssh.exec(Rewind.relaunchCommand(sessionName, capture, sessionId, s.cwd, runtimeId.orEmpty()))
            val code = Rewind.parseRelaunch(deliver)
            if (code != null) {
                return@withLock Report(
                    Rewind.Outcome.Failed(if (code == "identity") "identity" else "deliver"),
                    capture = capture, exited = exited,
                )
            }
            Report(null, capture = capture, exited = exited, relaunched = true, registryLost = true)
        }

    /** argv 临时文件用完即删（[relaunch] 之后 / 用户放弃回放，两条路都要走到）。 */
    suspend fun cleanup(capture: Rewind.Capture) = delivery.withLock {
        conn.ssh.exec(Rewind.cleanupCommand(capture))
    }

    /**
     * 等 pane 回到 shell（`pane_current_command` 不再是 claude/node/bun）。
     * ⚠️ 轮询放进**一条** exec 里跑 shell 循环 —— 别在 Kotlin 侧每 0.5s 发一条 exec，
     * chanLock 串行会把通道挤成蜂窝。24×0.5s 自带上界。
     */
    private suspend fun waitShell(n: String): Boolean {
        // ⚠️ jsch exec 把整串交给远端 shell **求值一次** —— 想让它求值的就是 `${'$'}(...)`，
        //    不需要反斜杠（那是两层 shell 的写法，这里用了反而变成字面量）。
        val out = conn.ssh.exec(
            "for i in ${'$'}(seq 1 24); do " +
                "c=${'$'}(tmux display-message -p -t '$n' '#{pane_current_command}' 2>/dev/null); " +
                "case \"${'$'}c\" in claude|node|bun) sleep 0.5;; *) echo SHELL; exit 0;; esac; done; echo STUCK"
        )
        return "SHELL" in out.lineSequence().map { it.trim() }.toList()
    }
}
