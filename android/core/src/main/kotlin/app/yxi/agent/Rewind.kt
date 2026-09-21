package app.yxi.agent

/**
 * 「点历史消息上的笔 → 精准回到那一轮并继续」的后端适配器（Claude 侧）。
 *
 * ## CLI 能力（hk13 claude 2.1.267 实测，2026-09-21）
 *
 * Claude Code 有一个**隐藏旗标**（不在 --help 正文里）：
 *
 * ```
 * --resume-session-at <message id>
 *   When resuming, only messages up to and including the chain entry with
 *   <message.id> — any chain-entry UUID, typically the kept turn's last entry
 *   (use with --resume in print mode)
 * --resume-drops-turn <message id>
 *   … declare the prompt uuid of the turn the truncating resume intends to
 *   discard; the resume is refused if the discarded range contains anything
 *   not attributable to that turn … Ignored outside print mode.
 * ```
 *
 * 实测钉死的硬事实：
 *
 * 1. **截断语义是「含锚点」**：保留转录里到 `anchorUuid` 为止（含它自己）的消息，
 *    之后的全部丢弃，然后 `-p` 的新 prompt 作为下一条 user 消息接上。
 *    （CLI 源码 `messages.slice(0, ke+1)`，ke = 按 uuid findIndex 的位置。）
 * 2. **只在 print（`-p`）模式生效**：交互式 TUI 带上它会被**静默忽略**
 *    （隔离 tmux 实测：DELTA/EPSILON 两轮的会话带锚点重开，两轮全量渲染）。
 *    所以回溯必须走一次 `-p`，不能在已开着的交互会话里原地截。
 * 3. **转录 JSONL 只追加、不改写**：回溯在文件里**追加一条新分支**
 *    （新消息的 `parentUuid` 指回锚点），被丢弃那轮的原始条目**原样保留**。
 *    交互式 `claude --resume <sid>` 重开时跟随**最新的叶子**，所以用户看到的就是
 *    截断后的样子 —— 我们**从头到尾不碰 JSONL 文件**，没有伪造历史。
 * 4. **多轮回退成立**（隔离假会话三链目 U1→A1→U2→A2→U3→A3 实测，全部 pre-API 零成本）：
 *    · `--resume-drops-turn` **可省** —— 不带它直接过校验（探针A 查消息层 / 探针F 一路
 *      走到 API 层报 Not logged in，校验全过）；
 *    · 带了声明则 CLI 守卫复核「丢弃范围恰好这一轮」—— 范围里混进任何别的轮次的
 *      user 条目**当场拒绝**（原话 `range contains a user entry not attributable…`），
 *      所以**多轮回退必须省略 drops**，丢弃的正确性由我们自己的 [verifyCommand] 把守；
 *    · 省略 drops 的回退是**追加分支**：新轮 `parentUuid` 指回锚点，旧尾巴原样留在
 *      文件里成为旁支（实测文件 6 行 → 18 行，U2/A2/U3/A3 都在），**数据不丢**；
 *    · `--fork-session` 与 session-at 组合无冲突；fork 时**会话 id 变新**（实测新 id），
 *      不 fork 时 id 不变。
 *    ⚠️ UI 渲染推论（给 root 的接口事实）：回退后文件里**同时存在两条分支**，
 *    按「顺序 append」渲染会把旧尾巴和新轮交错显示 —— 必须**从最新叶子沿 parentUuid
 *    回走**渲染当前链；另外 CLI 回退轮自己会追加 `queue-operation`/`attachment`/`mode`
 *    等簿记条目，渲染层不认识的 type 一律跳过。
 *
 * ## 因此 yxi 的回溯流程（desktop [app.yxi.desktop.RewindController] 编排，老板令修订版二）
 *
 * **准备 / 执行两阶段**（审查定下的收敛）—— **所有拒绝都发生在模型调用之前**：
 *
 *   准备（全只读，零副作用除了 argv 临时文件）：
 *   1. 门禁：该 tmux 会话空闲（不在干活、没有等你答的选择器）、agent 是 claude；
 *      记下 [app.yxi.agent.Session.runtimeId]（tmux `pid:session_id:created`，同名重建即变）
 *      —— relaunch 时要拿它做**同一实例复核**。
 *   2. 结构核对：source/anchor/target 三个 UUID 对转录文件做**字段级**匹配（[verifyCommand]）。
 *   3. 模式裁定：捕获原进程启动上下文（[captureCommand]）后，**立即**裁定——
 *      `fork=true`（分支模式）放行；`fork=false`（原地模式）要求 `Capture.others` 为空，
 *      非空就**在模型调用前**拒绝（`unpreserved`），清单结构化交 UI。
 *   执行（唯一的模型调用）：
 *   4. SSH exec 跑一次 [command]：`cd <会话cwd> && timeout <PRINT_TIMEOUT_SEC> <真binary>
 *      --resume … --model <原model> --effort <原effort> …`——**同一 cwd、原模型配置**，
 *      **无任何权限绕过**，不走 bashrc 的 claude 函数。线路不受影响：线路就是
 *      `~/.claude/settings.json` 的 `env` 块（Lines 机制同 CC Switch），任何 claude 进程都读它。
 *      有限 timeout（[PRINT_TIMEOUT_SEC]）保证这条 exec 最坏也在这时间内把通道还回来，
 *      不无限吊死整条 SSH。结果见 [parse]。
 *
 *   之后的「载入新分支」是**独立的、用户点了才走**的 [app.yxi.desktop.RewindController.relaunch]：
 *   · **原地重启**只允许在「原启动参数可可靠回放」时（白名单旗标全量恢复、其余一个没有）
 *     —— 有任何无法保留项就**拒绝**，结构化清单交回 UI，让用户走**分支模式**（fork 出
 *     新会话打开），绝不悄悄降级。
 *   · 重启用的是 [relaunchCommand]：**一条 exec 里先复核 runtimeId + paneId 再 send-keys**
 *     （复核和投递原子，杜绝「检查完被同名新会话顶包」的窗口），pane 里先 `cd <会话cwd>`
 *     再拉 真 binary + `--resume` + 白名单旗标，**不重新自动加任何 bypass**。
 *
 * ## 边界怎么选（UI 侧换算）
 *
 *   · **回退到任意历史轮的 user 消息 M 重发**（单轮多轮都行）：
 *     anchor = M 的 `parentUuid`，target = M 自己的 uuid，prompt = 改后的文本 / M 原文。
 *     M 是**最后一轮** → drops = M（严格声明，CLI 守卫白送一层复核）；
 *     M 之后还有别的轮次 → drops = null（多轮回退，见类注第 4 条）。
 *   · M 是**会话第一条消息**时没有 parentUuid，anchor 无处安放 —— 不支持
 *     （第一轮回退 = 开新会话，本来就是全新对话，用已有的新建入口）。
 *   · **跳过某轮的回复、在锚点继续**：anchor = 那轮最后一条链目，target/drops 不适用
 *     —— 这条路没有 user 消息目标，走「编辑上一条 user 消息」同款流程更一致，
 *     v1 不单开入口。
 *
 * **回不去的东西**（结构化上抛 UI，不装看不见）：原进程内存态（进行中的轮次被门禁挡掉）、
 * 未进白名单的启动旗标（如权限类旗标 —— 拒绝原地重启的依据）、cloud-enter 的 watchdog
 * 登记（真 binary 直拉不经函数，[app.yxi.desktop.RewindController] 的 Report 里如实标注）。
 */
object Rewind {

    const val TAG = "__YXI_REWIND__"

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** 一次「截断重放」的完整参数。全部字段过了 [validate] 才允许进 [command]。 */
    data class Plan(
        /** claude 会话 id（转录文件名）。 */
        val sessionId: String,
        /**
         * 保留到这条链目为止（**含它**）。⚠️ 是「转录行顶层的 `uuid`」，
         * 不是 message 内容里的什么 id —— 传错查无此消息，CLI 直接拒（见 [parse]）。
         * 回退到目标 user 消息 M 时 = M 的 `parentUuid`。
         */
        val anchorUuid: String,
        /**
         * 回退的**目标 user 消息**（它的 `parentUuid` 恰好是 [anchorUuid]）。
         * [verifyCommand] 按它做字段级父子核对；多轮/单轮都传它。
         */
        val targetUuid: String,
        /**
         * 严格单轮声明（**可选**）：值必须就是 [targetUuid]（声明「丢弃的恰好是这一轮」），
         * CLI 守卫会再复核一遍丢弃范围。**仅当目标是最后一轮时才声明** ——
         * 目标之后还有别的轮次时带声明必被守卫拒绝（实测原话
         * `range contains a user entry not attributable to the declared turn`），
         * 所以多轮回退必须留 null（省略 `--resume-drops-turn`，探针F 实测校验全过）。
         * null 时丢弃范围的正确性由 [verifyCommand] 的结构核对把守。
         */
        val dropsTurnUuid: String? = null,
        /** 接在锚点后面的新 user 消息（改后的文本 / 原文重发 / 新指示）。 */
        val prompt: String,
        /**
         * true = 加 `--fork-session`：分叉成**新会话 id**，原会话转录从此完全不再被追加。
         * 默认 false：同一会话 id 内开分支（文件追加，历史条目不动）——
         * 界面上还停留在同一个对话里，跟 Claude Code 自带 rewind 的行为一致。
         */
        val fork: Boolean = false,
    )

    /** @return null = 可以执行；否则给人看的失败原因代号（给 UI 层翻话术）。 */
    fun validate(p: Plan): String? = when {
        !UUID.matches(p.sessionId) -> "sid"
        !UUID.matches(p.anchorUuid) -> "anchor"
        !UUID.matches(p.targetUuid) -> "target"
        p.dropsTurnUuid != null && !UUID.matches(p.dropsTurnUuid!!) -> "drops"
        // 声明了 drops 就必须声明「丢弃的就是目标这一轮」—— 别的值守卫必拒，不如本地先拦
        p.dropsTurnUuid != null && p.dropsTurnUuid != p.targetUuid -> "drops-target"
        p.anchorUuid == p.targetUuid -> "same"
        p.prompt.isBlank() -> "prompt"
        '\u0000' in p.prompt -> "prompt"
        p.prompt.length > 100_000 -> "prompt-long"
        else -> null
    }

    /** shell 单引号里安全地嵌一个值（跟 [Dirs.q] 同一个四字符窍门）。 */
    private fun q(v: String) = v.replace("'", "'\\''")

    /** print 截断轮的远端硬上限（秒）。有限 timeout：这条 exec 最坏也在这时间内把 SSH 通道还回来。 */
    const val PRINT_TIMEOUT_SEC = 600

    /**
     * print 模式截断轮的完整命令（给 `SshSession.exec`）—— **执行阶段**唯一一步。
     *
     * ⚠️ **先 `cd` 进会话 cwd 再拉 claude**（审查指出：claude 按 cwd 解析项目与会话，
     * 在错误目录里 resume 可能张冠李戴）。cwd 用门禁那趟 snapshot 的**实况**值。
     * ⚠️ **`timeout` 硬上限**（[PRINT_TIMEOUT_SEC]）：模型答不完也到点收摊，
     * 这条 exec 把通道还给连接，不吊死同连接上的一切其他操作。
     * 机器上没有 `timeout` 命令 → 回 `rc=notimeout`（[parse] 认），宁败不裸奔。
     * ⚠️ **回放原 model/effort**（[Capture] 白名单旗标，审查指出：不回放就会落到
     * 默认模型上，等于换了线路配置在跑）。
     * ⚠️ `exe` 是已解析的**真实 binary**（[captureCommand] 从 `/proc/<pid>/exe` 拿）——
     *   老板令：不用 shell 函数 `claude`（它会隐式走 cloud-enter 扩权登记），执行解析出的本体。
     * ⚠️ **不加任何权限绕过**（老板令）：没有 `IS_SANDBOX=1`、没有
     * `--dangerously-skip-permissions`、也不设 permission-mode —— 回溯续聊就该是
     * 一轮**纯文本对话**；模型真要碰工具就跟交互会话一样走正常权限路子，
     * 而 -p 模式答不了选择器，那种轮子会失败退出（[parse] 认得出）—— 宁败不绕。
     * ⚠️ `2>&1`：拒绝类的报错（guard 拒绝 / 查无此消息）全走 **stderr**，
     * 不合进来 [parse] 就只能看到 rc=1 什么都解释不了。
     * ⚠️ 结尾的 TAG 行是结果锚 —— exec 的输出里 JSON 和报错混在一起，认行不猜。
     */
    fun command(exe: String, cwd: String, cap: Capture, p: Plan): String {
        require(validate(p) == null && validateExe(exe) == null)
        val fork = if (p.fork) " --fork-session" else ""
        // ⚠️ 变量值一律「单引号包裹 + 转义」—— q() 只转义不加引号，裸值带空格会被 shell 裂开
        val args = buildString {
            append(q(exe))
            append(" --resume '").append(q(p.sessionId)).append("'")
            append(" --resume-session-at '").append(q(p.anchorUuid)).append("'")
            // 多轮回退 dropsTurnUuid=null → 省略旗标（实测带声明必被守卫拒，见类注第 4 条）
            p.dropsTurnUuid?.let { append(" --resume-drops-turn '").append(q(it)).append("'") }
            cap.model?.let { append(" --model '").append(q(it)).append("'") }
            cap.effort?.let { append(" --effort '").append(q(it)).append("'") }
            append(fork)
            append(" -p --output-format json -- '").append(q(p.prompt)).append("' 2>&1")
        }
        return "command -v timeout >/dev/null 2>&1 || { echo \"$TAG:rc=notimeout\"; exit 0; }; " +
            "cd '${q(cwd)}' || { echo \"$TAG:rc=cwd\"; exit 0; }; " +
            "timeout $PRINT_TIMEOUT_SEC $args; echo \"$TAG:rc=\$?\""
    }

    /** 真实 binary 路径的形态门槛（常规安装都在这 charset 里；怪路径就拒，别硬塞进 shell）。 */
    private val EXE_OK = Regex("^[A-Za-z0-9/._-]{2,300}$")

    /** @return null = exe 路径可用；否则失败代号。 */
    fun validateExe(exe: String): String? = when {
        !EXE_OK.matches(exe) -> "exe-path"
        !exe.contains("/claude") && !exe.endsWith("/claude") -> "exe-name"
        else -> null
    }

    /** 捕获结果 TAG。 */
    const val CAP_TAG = "__YXI_REWIND_CAP__"

    /**
     * 捕获原运行器启动上下文（老板令：明确读取并保留，不猜；不输出凭据）。
     *
     * 做四件事，全程只读进程元数据，**不碰 environ**（环境块里是密钥的老家，读不得）：
     *
     * 1. 找 pid：先查 cloud-enter 登记表（~/.claude/sessions 下的登记 json，按 tmux 名精确匹配
     *    `"tmux":"<名>:"`）；没有就退回 pane 直接子进程里认 cmdline 含 `claude` 的那个；
     * 2. 解析真 binary：`readlink /proc/<pid>/exe`；
     * 3. argv 存**服务器本地** mktemp（chmod 600）—— 值不回传客户端；
     * 4. 只回传：白名单旗标（`--model`/`--effort`/`-n|--name`）的值 + 其余旗标的**名**
     *    （值一律不回传，凭据没有出口）+ pid 与 pane id（`#{pane_id}`）——
     *    后两个是 relaunch 时**同一实例复核**的比对基准（runtimeId 在门禁 snapshot 里有）。
     */
    fun captureCommand(sessionName: String): String {
        val n = q(sessionName)
        return "reg=\$(grep -h '\"tmux\":\"$n:' \"\$HOME/.claude/sessions/\"*.json 2>/dev/null | head -1); " +
            "pid=\$(printf '%s' \"\$reg\" | sed -n 's/.*\"pid\":\\([0-9]*\\).*/\\1/p'); " +
            "[ -n \"\$pid\" ] || { pp=\$(tmux display-message -p -t '$n' '#{pane_pid}' 2>/dev/null); " +
            "for c in \$(pgrep -P \"\$pp\" 2>/dev/null); do " +
            "if tr '\\0' ' ' < /proc/\$c/cmdline 2>/dev/null | grep -q claude; then pid=\$c; break; fi; done; }; " +
            "[ -n \"\$pid\" ] && [ -r /proc/\$pid/cmdline ] || { echo \"$CAP_TAG:gone\"; exit 0; }; " +
            "exe=\$(readlink /proc/\$pid/exe 2>/dev/null); " +
            "[ -n \"\$exe\" ] || { echo \"$CAP_TAG:noexe\"; exit 0; }; " +
            "tf=\$(mktemp \"/tmp/yxi-argv.XXXXXX\") && chmod 600 \"\$tf\" && " +
            "tr '\\0' '\\n' < /proc/\$pid/cmdline > \"\$tf\" 2>/dev/null; " +
            "echo \"$CAP_TAG:file=\$tf\"; echo \"$CAP_TAG:exe=\$exe\"; " +
            "echo \"$CAP_TAG:pid=\$pid\"; " +
            "pn=\$(tmux display-message -p -t '$n' '#{pane_id}' 2>/dev/null); " +
            "echo \"$CAP_TAG:pane=\$pn\"; " +
            "a=\$(tr '\\0' '\\n' < /proc/\$pid/cmdline); " +
            "m=\$(printf '%s\\n' \"\$a\" | awk 'p{print;p=0} /^--model\$/{p=1}' | head -1); " +
            "[ -n \"\$m\" ] && echo \"$CAP_TAG:model=\$m\"; " +
            "e=\$(printf '%s\\n' \"\$a\" | awk 'p{print;p=0} /^--effort\$/{p=1}' | head -1); " +
            "[ -n \"\$e\" ] && echo \"$CAP_TAG:effort=\$e\"; " +
            "nn=\$(printf '%s\\n' \"\$a\" | awk 'p{print;p=0} /^(-n|--name)\$/{p=1}' | head -1); " +
            "[ -n \"\$nn\" ] && echo \"$CAP_TAG:name=\$nn\"; " +
            "o=\$(printf '%s\\n' \"\$a\" | grep -oE '^(-{1,2}[A-Za-z][A-Za-z0-9-]*)' | sort -u | " +
            "grep -vE '^--?(model|effort|n|name)\$' | tr '\\n' ' '); " +
            "[ -n \"\$o\" ] && echo \"$CAP_TAG:other=\$o\"; true"
    }

    /**
     * 捕获到的「原运行器上下文」。[others] 非空 = 有回不去的旗标 → 原地重启必须被拒绝
     * （老板令：提供明确的分支模式，不悄悄降级）。
     */
    data class Capture(
        /** 已解析真实 binary（/proc/<pid>/exe），过了 [validateExe] 才有。 */
        val exe: String,
        /** 服务器上 600 的 argv 临时文件；[cleanupCommand] 用完即删。 */
        val tmpFile: String,
        /** 回放白名单旗标的值（值已过 [ARG_OK]；过不了的进 [others]）。 */
        val model: String? = null,
        val effort: String? = null,
        val name: String? = null,
        /** 未回放旗标的**名**（无值）。权限类旗标在这里 = 原地重启的硬拒绝项。 */
        val others: List<String> = emptyList(),
        /** 捕获到的 pane 里 claude 进程 pid（/proc 核实过在跑）；relaunch 前复核用。 */
        val pid: String = "",
        /** 捕获到的 tmux pane id（`%N`）；relaunch 前复核用 —— pane 重建即变。 */
        val paneId: String = "",
    ) {
        /** 原地重启是否被允许（白名单全量可回放、其余一个没有）。 */
        val inPlaceAllowed: Boolean get() = others.isEmpty()
    }

    /** 回放旗标值的形态门槛：能原样进 shell 双引号里不闹事的字符才放行。 */
    private val ARG_OK = Regex("^[A-Za-z0-9 ._()\\[\\]:,+=@/^_-]{1,200}$")

    /**
     * 认 [captureCommand] 的输出。值过不了 [ARG_OK] 的旗标降级进 [Capture.others]
     * （宁可不回放也不把怪值塞进 shell）。认不出来整体失败（fail-closed）。
     */
    fun parseCapture(out: String): CaptureResult {
        val text = clean(out)
        val lines = text.lineSequence().map { it.trim() }.filter { it.startsWith("$CAP_TAG:") }.toList()
        val first = lines.firstOrNull() ?: return Failed("notag")
        when (val head = first.removePrefix("$CAP_TAG:").substringBefore('=')) {
            "gone" -> return Failed("gone")
            "noexe" -> return Failed("noexe")
        }
        var file = ""; var exe = ""
        var model = ""; var effort = ""; var name = ""
        var pid = ""; var pane = ""
        val others = ArrayList<String>()
        for (l in lines) {
            val body = l.removePrefix("$CAP_TAG:")
            val k = body.substringBefore('=')
            val v = body.substringAfter('=', "")
            when (k) {
                "file" -> file = v
                "exe" -> exe = v
                "pid" -> if (Regex("""^[0-9]{1,10}$""").matches(v)) pid = v
                "pane" -> if (Regex("""^%[0-9]{1,10}$""").matches(v)) pane = v
                "model" -> if (ARG_OK.matches(v)) model = v else others += "--model"
                "effort" -> if (ARG_OK.matches(v)) effort = v else others += "--effort"
                "name" -> if (ARG_OK.matches(v)) name = v else others += "--name"
                "other" -> v.split(' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { fl ->
                    if (fl !in others) others += fl
                }
            }
        }
        if (file.isBlank() || validateExe(exe) != null) return Failed("badcap")
        return Got(Capture(exe, file, model.ifBlank { null }, effort.ifBlank { null }, name.ifBlank { null }, others, pid, pane))
    }

    sealed interface CaptureResult
    data class Got(val capture: Capture) : CaptureResult
    data class Failed(val code: String) : CaptureResult

    /** 用完删掉 argv 临时文件（回放完成 / 用户放弃，两条路都要走到）。 */
    fun cleanupCommand(c: Capture): String {
        require(Regex("/tmp/yxi-argv\\.[A-Za-z0-9]+").matches(c.tmpFile))
        return "rm -f -- '${q(c.tmpFile)}'"
    }

    /** 实例复核 TAG：relaunch 那条 exec 的产出只有它（sent / identity）。 */
    const val ID_TAG = "__YXI_REWIND_ID__"

    /**
     * 原地重启的投递命令：**复核与 send-keys 在同一条 exec 里**（审查指出的 identity 复核 +
     * 投递竞争 —— 拆成两条 exec 的话，复核完、按键前这个窗口里同名会话可能被重建顶包）。
     *
     * 复核两道：
     *  1. **runtimeId**（tmux `pid:session_id:session_created`，与门禁 snapshot 拿到的一致）
     *     —— 会话被 kill 后同名重建即变；
     *  2. **paneId**（`#{pane_id}`，与 [Capture.paneId] 一致）—— pane 重建即变。
     *
     * 任一不符：回 `ID_TAG:identity`，**一个键都不发**。runtimeId 为空（snapshot 降级没拿到）
     * 时退化为只核 paneId。
     *
     * 按键内容：pane 里先 `cd <会话cwd>`（审查：与 print 轮同一 cwd）再拉
     * **真 binary + `--resume` + 白名单旗标原值回放**；值在 [parseCapture] 已过
     * [ARG_OK]（shell 双引号里安全），这里不再收客户端给的自由文本。不加任何 bypass。
     *
     * @param runtimeId 门禁 snapshot 的 [app.yxi.agent.Session.runtimeId]（relaunch 时原样传回）
     */
    fun relaunchCommand(sessionName: String, c: Capture, sessionId: String, cwd: String, runtimeId: String): String {
        if (runtimeId.isBlank() || c.paneId.isBlank()) return "echo '$ID_TAG:identity'"
        require(UUID.matches(sessionId) && validateExe(c.exe) == null)
        val n = q(sessionName)
        // ⚠️ 同 [command]：变量值一律「单引号包裹 + 转义」（q() 只转义不加引号）
        val keys = buildString {
            append("cd '").append(q(cwd)).append("' && ")
            append(c.exe)
            append(" --resume '").append(q(sessionId)).append("'")
            c.model?.let { append(" --model '").append(q(it)).append("'") }
            c.effort?.let { append(" --effort '").append(q(it)).append("'") }
            c.name?.let { append(" --name '").append(q(it)).append("'") }
        }
        val rtOk = if (runtimeId.isBlank()) "" else "[ \"\$rt\" = '${q(runtimeId)}' ] && "
        return "rt=\$(tmux display-message -p -t '$n' '#{pid}:#{session_id}:#{session_created}' 2>/dev/null); " +
            "pn=\$(tmux display-message -p -t '$n' '#{pane_id}' 2>/dev/null); " +
            "$rtOk [ \"\$pn\" = '${q(c.paneId)}' ] || { echo \"$ID_TAG:identity\"; exit 0; }; " +
            "tmux send-keys -t '$n' -l -- '${q(keys)}' && " +
            "tmux send-keys -t '$n' Enter && echo \"$ID_TAG:sent\""
    }

    /**
     * 认 [relaunchCommand] 的输出。@return null = 已投递；否则失败代号：
     * `identity`（复核不过，一键未发）/ `noresult`（没看到锚，多半连接半断）。
     */
    fun parseRelaunch(out: String): String? {
        val line = out.lineSequence().map { it.trim() }
            .lastOrNull { it.startsWith("$ID_TAG:") } ?: return "noresult"
        return when (val code = line.removePrefix("$ID_TAG:")) {
            "sent" -> null
            else -> code
        }
    }

    /** 预检结果 TAG（跟 [TAG] 分开，两段 exec 各认各的锚）。 */
    const val CHECK_TAG = "__YXI_REWIND_CHK__"

    /**
     * 执行前的**结构化核对**（老板令：精确 source/anchor/target UUID，不能只靠文本）。
     *
     * 三件事全部对转录文件做**字段级**匹配，一个字正文都不看：
     *
     * 1. **source**：`~/.claude/projects/<cwd 编码>/<sessionId>.jsonl` 存在
     *    （编码 = 路径非字母数字全换 `-`，跟 [Dirs] 拉起路径同一个 sed）；
     * 2. **anchor**：文件里有**自己 uuid 字段**恰好等于 anchor 的行，且行号在 target 之前；
     * 3. **target**（[Plan.targetUuid]）：有自己 uuid 恰好等于 target 的行，且那一行
     *    `parentUuid` **恰好等于 anchor**（父子关系，不是文本相似）、
     *    `type == "user"`、不是 `isMeta`（工具结果/注入消息不算真 prompt）。
     *
     * 多轮回退（drops 未声明）时丢弃范围的正确性**全靠这一步**把守 ——
     * 它核对的是父子关系与类型，跟目标在第几轮无关。
     *
     * ⚠️ 匹配键写成 `"uuid":"<值>"` 整串 —— JSON 里 `parentUuid` 是大写 U 开头，
     * `"uuid"` 这个带引号的键在它里面天然不出现，子串匹配不会误命中；
     * 这就是拿字段名当边界，不是 grep 正文。
     *
     * @param cwd 会话工作目录（[app.yxi.agent.SessionProbe.Session.cwd]，会话列表现成的）
     */
    fun verifyCommand(cwd: String, p: Plan): String {
        val c = q(cwd.trimEnd('/').ifBlank { "/" })
        val sid = q(p.sessionId)
        val a = q(p.anchorUuid)
        val d = q(p.targetUuid)
        return "enc=\$(printf %s '$c' | sed 's/[^A-Za-z0-9]/-/g'); " +
            "f=\"\$HOME/.claude/projects/\$enc/$sid.jsonl\"; " +
            "[ -f \"\$f\" ] || { echo \"$CHECK_TAG:missing-session\"; exit 0; }; " +
            "an=\$(grep -nF -m1 '\"uuid\":\"$a\"' \"\$f\" | cut -d: -f1); " +
            "[ -n \"\$an\" ] || { echo \"$CHECK_TAG:no-anchor\"; exit 0; }; " +
            "dn=\$(grep -nF -m1 '\"uuid\":\"$d\"' \"\$f\" | cut -d: -f1); " +
            "[ -n \"\$dn\" ] || { echo \"$CHECK_TAG:no-target\"; exit 0; }; " +
            "[ \"\$an\" -lt \"\$dn\" ] || { echo \"$CHECK_TAG:order\"; exit 0; }; " +
            "ln=\$(sed -n \"\${dn}p\" \"\$f\"); " +
            "case \"\$ln\" in *'\"parentUuid\":\"$a\"'*) ;; *) echo \"$CHECK_TAG:not-child\"; exit 0;; esac; " +
            "case \"\$ln\" in *'\"isMeta\":true'*) echo \"$CHECK_TAG:meta\"; exit 0;; esac; " +
            "case \"\$ln\" in *'\"type\":\"user\"'*) echo \"$CHECK_TAG:ok\";; *) echo \"$CHECK_TAG:not-user\";; esac"
    }

    /**
     * 认 [verifyCommand] 的输出。认不出来一律失败（fail-closed，同 [parse]）。
     * @return null = 核对通过；否则失败代号（原样来自服务器，给 UI 层翻话术）。
     */
    fun parseVerify(out: String): String? {
        val line = out.lineSequence().map { it.trim() }
            .lastOrNull { it.startsWith("$CHECK_TAG:") } ?: return "noresult"
        return when (val code = line.removePrefix("$CHECK_TAG:").substringBefore(':')) {
            "ok" -> null
            else -> code
        }
    }

    /** 丢掉 CR（JSONL/终端输出偶尔夹带），它进 shell 单引号会坏事。 */
    private fun clean(v: String) = v.replace("\r", "")

    /**
     * 认 [command] 的输出。**认不出来一律失败**（fail-closed，同 [Dirs.madeFrom]）——
     * 回溯失败最坏的结局是把用户晾在 shell 上，所以宁可报错也别假装成功。
     */
    fun parse(out: String): Outcome {
        val text = clean(out)
        val tagLine = text.lineSequence().lastOrNull { it.trim().startsWith("$TAG:rc=") }
            ?: return Outcome.Failed("notag", text.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(200))
        val raw = tagLine.trim().removePrefix("$TAG:rc=").trim()
        // 非数字 = 我们自己 echo 的代号（notimeout / cwd），原样上抛给 UI 翻话术
        val rc = raw.toIntOrNull()
            ?: return Outcome.Failed(raw.take(40), tagLine.take(120))
        if (rc == 0) {
            // 成功：stdout 最后一行是 `--output-format json` 的单对象（result / session_id …）
            val json = text.lineSequence().filter { it.isNotBlank() }.lastOrNull { it.trimStart().startsWith("{") }
            if (json != null) runCatching {
                val d = org.json.JSONObject(json)
                val sid = d.optString("session_id")
                if (d.optBoolean("is_error", false)) return Outcome.Failed("model-error")
                if (d.optString("type") == "result" && UUID.matches(sid)) return Outcome.Ok(sid, d.optString("result"))
            }
            return Outcome.Failed("empty", tagLine.take(120))
        }
        // 失败：按 CLI 的三句原话认（实测抓到的原文，别改措辞去匹配）
        val detail = text.lineSequence().filter { it.isNotBlank() }.joinToString("\n").take(400)
        return when {
            // `timeout` 命令到点收掉的统一退出码
            rc == 124 -> Outcome.Failed("timeout", "超过 ${PRINT_TIMEOUT_SEC}s 上限，远端 timeout 已收掉")
            "Resume rejected by --resume-drops-turn:" in text -> Outcome.Failed("guard-refused", detail)
            "No message found with message.uuid of:" in text -> Outcome.Failed("no-message", detail)
            "No conversation found with session ID:" in text -> Outcome.Failed("no-conversation", detail)
            else -> Outcome.Failed("rc", detail)
        }
    }

    sealed interface Outcome {
        /**
         * @param sessionId 回写回来的会话 id —— 不 fork 时跟原值相同，
         *   fork 时是**新 id**（调用方必须改用它继续 resume / 流转录，不能拿旧 id）。
         * @param result 模型这轮的答复文本（改写重发时就是新回复，UI 直接追加渲染）。
         */
        data class Ok(val sessionId: String, val result: String) : Outcome
        /** @param code notag / badtag / empty / timeout / notimeout / cwd / guard-refused / no-message / no-conversation / rc */
        data class Failed(val code: String, val detail: String = "") : Outcome
    }
}
