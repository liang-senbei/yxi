package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 模型/强度切换意图的状态：
 *  Pending 待发 → Delivering 已落盘待发送结果 → （发送成功）
 *    · SentAwaitEvidence 已发送、等本会话转录**新**回执 —— 不堵普通消息，新配置下下一条照发；
 *    · AwaitConfirm 终端弹了确认框，等用户自己回车 —— 堵，绝不代按；
 *    · Unknown 发送不明/被拒/新证据不符 —— 堵，不自动重发。
 *  → Applied（发送基线之后的新转录回执确认生效）。 */
internal enum class ModelChangeStatus { Pending, Delivering, SentAwaitEvidence, AwaitConfirm, Unknown, Applied, Dismissed }

internal data class ModelSwitchRequest(
    /** 稳定键 = taskNavigationKey（host+session），与指令队列同源：仅用 runtimeId 会在跨主机时撞。 */
    val taskKey: String,
    /** tmux 身份复核用（send 脚本里的 '#{pid}:#{session_id}:#{session_created}'），不是存储键。 */
    val runtimeId: String,
    val model: String?, val effort: String?,
    val status: ModelChangeStatus = ModelChangeStatus.Pending,
    val detail: String = "", val revision: Long = 0,
    /** 发送基线：只认此后新增的转录证据。 */
    val baselineFile: String = "", val baselineOffset: Long = -1L,
    val evidenceModel: String = "", val evidenceEffort: String = "",
    val screenHadRejection: Boolean = false, val screenHadDialog: Boolean = false,
) {
    val active get() = status in setOf(ModelChangeStatus.Pending, ModelChangeStatus.Delivering,
        ModelChangeStatus.SentAwaitEvidence, ModelChangeStatus.AwaitConfirm)
}

/** 发送时点采下的证据基线：Ctx 旧值与请求相同也**绝不**判成功（那是历史缓存不是新回执）。 */
internal data class ModelEvidenceBaseline(
    val file: String, val offset: Long,
    val model: String, val effort: String,
    val screenHadRejection: Boolean, val screenHadDialog: Boolean,
)

/** 证据读取面：转录文件 + 已解析字节偏移 + 会话 Ctx 的 model/effort。
 *  与 [DesktopTranscriptMemory] 同一条链（Entry.file / view.offset / view.context）。 */
internal data class ModelEvidenceView(val file: String, val offset: Long, val model: String, val effort: String)

/** 切换协议的纯判定，全部可单测。[app.yxi.agent.Model.canonical] 只用于显示；
 * 这里发送与比对一律原样。⚠️ 比对只做 trim+小写：**保留 `[1m]` 这类上下文后缀** ——
 * 剥掉它会把「1M 上下文变体」误认成普通变体的成功（不同选择，不是同一次切换）。 */
internal object ModelSwitchProtocol {
    val CONFIRM_TITLES = listOf("Switch model?", "Change effort level?")
    fun confirmDialog(screen: String) = CONFIRM_TITLES.any { it in screen }

    /** 屏上出现这些 = 运行器明确拒绝：名字不认或不在白名单（同 core Model.FAILED 语义）。 */
    val REJECTED = Regex("""Model '[^']*' not found|unrecognized_model|Kept model as [\w .()\[\]-]+""")
    fun rejected(screen: String) = REJECTED.containsMatchIn(screen)

    fun command(model: String?, effort: String?): String =
        if (model != null) "/model ${model.trim()}" else "/effort ${requireNotNull(effort).trim()}"

    fun sameModel(requested: String, effective: String) =
        effective.isNotBlank() && requested.trim().lowercase() == effective.trim().lowercase()
    fun sameEffort(requested: String, effective: String) =
        effective.isNotBlank() && requested.trim().lowercase() == effective.trim().lowercase()
}

/** 持久切换意图（每 taskKey 一条）。提交前先落盘 Pending，发送前连基线一起落盘 Delivering，
 * 重启时在途一律转 Unknown —— 与 [InstructionQueue] 同一套「不明即保守」语义。 */
internal class ModelChangeStore(file: File) {
    private val disk = DurableFile(file) { decode(it) }
    var entries by mutableStateOf<List<ModelSwitchRequest>>(emptyList())
        private set
    var error by mutableStateOf("")
        private set
    private var readable = true

    init {
        try {
            val loaded = disk.read()?.let(::decode).orEmpty()
            val fromBackup = disk.recovered
            val recovered = loaded.map {
                if (it.status in setOf(ModelChangeStatus.Delivering, ModelChangeStatus.SentAwaitEvidence, ModelChangeStatus.AwaitConfirm) || (fromBackup && it.status == ModelChangeStatus.Pending))
                    it.copy(status = ModelChangeStatus.Unknown, revision = it.revision + 1,
                        detail = "应用退出时切换结果未确认，请在终端核对后重新应用")
                else it
            }
            if (recovered != loaded) disk.write(encode(recovered))
            entries = recovered
            if (fromBackup) error = "模型切换记录已从备份恢复，请核对"
        } catch (e: Exception) { readable = false; error = "无法读取模型切换记录：${e.message}" }
    }

    fun active(taskKey: String) = entries.firstOrNull { it.taskKey == taskKey && it.active }
    fun latest(taskKey: String) = entries.firstOrNull { it.taskKey == taskKey }
    fun blocksQueue(taskKey: String) = latest(taskKey)?.status in setOf(
        ModelChangeStatus.Pending, ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm, ModelChangeStatus.Unknown)
    @Synchronized fun dismiss(taskKey: String, revision: Long) = edit(taskKey, revision,
        setOf(ModelChangeStatus.Pending, ModelChangeStatus.Unknown, ModelChangeStatus.SentAwaitEvidence)) {
        it.copy(status = ModelChangeStatus.Dismissed, detail = "已停止跟踪此次切换，不重新发送命令")
    }

    /** 菜单意图入库。发送中/等确认框时不接收（返回 false，调用方保留意图下轮再试）；
     * SentAwaitEvidence 是「已发送待证据」，新意图可以直接顶掉。 */
    @Synchronized fun propose(taskKey: String, runtimeId: String, model: String?, effort: String?): Boolean {
        if (model.isNullOrBlank() && effort.isNullOrBlank()) return false
        val existing = entries.firstOrNull { it.taskKey == taskKey }
        if (existing != null && existing.status in setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) return false
        commit(entries.filter { it.taskKey != taskKey } + ModelSwitchRequest(
            taskKey, runtimeId, model?.trim(), effort?.trim(), revision = (existing?.revision ?: 0L) + 1))
        return true
    }

    /** Pending → Delivering，发送基线一并落盘（先落盘再 IO）。 */
    @Synchronized fun beginSend(taskKey: String, baseline: ModelEvidenceBaseline): ModelSwitchRequest {
        val current = entries.single { it.taskKey == taskKey && it.status == ModelChangeStatus.Pending }
        return commitOne(current) {
            it.copy(status = ModelChangeStatus.Delivering, baselineFile = baseline.file,
                baselineOffset = baseline.offset, evidenceModel = baseline.model,
                evidenceEffort = baseline.effort, screenHadRejection = baseline.screenHadRejection,
                screenHadDialog = baseline.screenHadDialog)
        }
    }

    /** 发送成功且发送后屏面无新弹框/拒绝：转入「已发送待证据」。 */
    @Synchronized fun markAwaitEvidence(taskKey: String, revision: Long) = edit(taskKey, revision,
        setOf(ModelChangeStatus.Delivering)) { it.copy(status = ModelChangeStatus.SentAwaitEvidence) }

    @Synchronized fun markAwaitConfirm(taskKey: String, revision: Long) = edit(taskKey, revision,
        setOf(ModelChangeStatus.Delivering)) { it.copy(status = ModelChangeStatus.AwaitConfirm) }

    /** 当前段新证据确认：model 段过了还有 effort 段就回 Pending 等下一条命令，否则 Applied。 */
    @Synchronized fun phaseDone(taskKey: String, revision: Long, modelPhase: Boolean) = edit(taskKey, revision,
        setOf(ModelChangeStatus.Delivering, ModelChangeStatus.SentAwaitEvidence, ModelChangeStatus.AwaitConfirm)) {
        when {
            modelPhase && it.effort != null -> it.copy(model = null, status = ModelChangeStatus.Pending)
            else -> it.copy(model = null, effort = null, status = ModelChangeStatus.Applied,
                detail = "已生效（发送后本会话转录新回执确认）")
        }
    }

    @Synchronized fun markUnknown(taskKey: String, revision: Long, detail: String) = edit(taskKey, revision,
        setOf(ModelChangeStatus.Delivering, ModelChangeStatus.SentAwaitEvidence, ModelChangeStatus.AwaitConfirm)) {
        it.copy(status = ModelChangeStatus.Unknown, detail = detail) }

    private fun edit(taskKey: String, revision: Long, allowed: Set<ModelChangeStatus>,
                     transform: (ModelSwitchRequest) -> ModelSwitchRequest): ModelSwitchRequest {
        val current = entries.single { it.taskKey == taskKey && it.revision == revision }
        check(current.status in allowed) { "切换状态已变化，请刷新后重试" }
        return commitOne(current, transform)
    }

    private fun commitOne(current: ModelSwitchRequest, transform: (ModelSwitchRequest) -> ModelSwitchRequest): ModelSwitchRequest {
        val next = transform(current).copy(revision = current.revision + 1)
        commit(entries.map { if (it.taskKey == current.taskKey) next else it })
        return next
    }

    @Synchronized private fun commit(next: List<ModelSwitchRequest>) {
        check(readable) { error }
        try { disk.write(encode(next)); entries = next; error = "" }
        catch (e: Exception) { error = "切换记录未保存：${e.message}"; throw e }
    }

    companion object {
        private fun encode(items: List<ModelSwitchRequest>) = JSONObject().put("version", 1).put("items",
            JSONArray(items.map { JSONObject().put("taskKey", it.taskKey).put("runtimeId", it.runtimeId)
                .put("model", it.model ?: "").put("effort", it.effort ?: "").put("status", it.status.name)
                .put("detail", it.detail).put("revision", it.revision)
                .put("baselineFile", it.baselineFile).put("baselineOffset", it.baselineOffset)
                .put("evidenceModel", it.evidenceModel).put("evidenceEffort", it.evidenceEffort)
                .put("screenHadRejection", it.screenHadRejection).put("screenHadDialog", it.screenHadDialog) })).toString(2)
        private fun decode(raw: String): List<ModelSwitchRequest> {
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val items = root.getJSONArray("items")
            return (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                ModelSwitchRequest(o.getString("taskKey"), o.getString("runtimeId"),
                    o.optString("model").ifBlank { null }, o.optString("effort").ifBlank { null },
                    ModelChangeStatus.valueOf(o.getString("status")), o.optString("detail"),
                    o.getLong("revision"), o.optString("baselineFile"), o.optLong("baselineOffset", -1L),
                    o.optString("evidenceModel"), o.optString("evidenceEffort"),
                    o.optBoolean("screenHadRejection"), o.optBoolean("screenHadDialog"))
            }.also { list ->
                require(list.all { it.taskKey.isNotBlank() && it.runtimeId.isNotBlank() })
                require(list.map { it.taskKey }.distinct().size == list.size)
            }
        }
    }
}

/** 切换协议执行体：无自有线程与锁 —— 调用方（TerminalQueueRunner）持有 [Conn.instructionDeliveryMutex]，
 * 每个 tick 至多一步；门、转录视图以默认参数注入，测试可替换
 * （Model.borrowable 需真 Claude Code 输入框，伪不出 —— DeferredRouteRunnerTest 同款结论）。 */
internal class ModelSwitchController(
    private val store: ModelChangeStore,
    private val borrowable: (String) -> Boolean = app.yxi.agent.Model::borrowable,
    private val pendingPrompt: (String) -> Any? = { app.yxi.agent.Prompt.parse(it) },
    /** 该 task 的转录证据（文件、已解析字节偏移、会话 Ctx 的 model/effort）—— 与 ChatPane/DesktopTranscriptMemory
     *  同一条链（Entry.file / view.offset / view.context），仅用于**发送基线**与基线之后的新证据；
     *  旧缓存值本身不是回执。 */
    private val sessionEvidence: (String) -> ModelEvidenceView? = { taskKey ->
        DesktopTranscriptMemory.get(taskKey)?.let {
            ModelEvidenceView(it.file, it.view.offset, it.view.context?.model.orEmpty(), it.view.context?.effort.orEmpty())
        }
    },
) {
    /** @return 意图是否已入库（true 时调用方可从 conn.modelChanges 移除）。 */
    suspend fun step(exec: suspend (String) -> String, taskKey: String, runtimeId: String, sessionName: String,
                     intent: ConversationModelChange?): Boolean {
        val accepted = intent != null && store.propose(taskKey, runtimeId,
            intent.model?.trim()?.ifBlank { null }, intent.effort?.trim()?.ifBlank { null })
        val entry = store.active(taskKey) ?: return accepted
        when (entry.status) {
            ModelChangeStatus.SentAwaitEvidence -> { checkEvidence(taskKey); return accepted } // 纯内存，无键无 IO
            ModelChangeStatus.Unknown, ModelChangeStatus.Applied -> return accepted
            else -> Unit
        }
        val q = app.yxi.ssh.Shell::q
        val capture = "tmux capture-pane -p -t ${q("=" + sessionName + ":")} 2>/dev/null"
        if (entry.status == ModelChangeStatus.Pending) {
            val screen = exec(capture)
            // 空闲 + 无审批框 + 无残留确认框才发；不满足就原样等下一 tick，绝不补键
            if (!borrowable(screen) || pendingPrompt(screen) != null || ModelSwitchProtocol.confirmDialog(screen)) return accepted
            val evidence = sessionEvidence(taskKey)
            val started = store.beginSend(taskKey, ModelEvidenceBaseline(
                file = evidence?.file.orEmpty(), offset = evidence?.offset ?: -1L,
                model = evidence?.model.orEmpty(), effort = evidence?.effort.orEmpty(),
                screenHadRejection = ModelSwitchProtocol.rejected(screen),
                screenHadDialog = ModelSwitchProtocol.confirmDialog(screen)))
            val command = ModelSwitchProtocol.command(started.model, started.effort)
            require(command.none { it < ' ' }) { "切换命令含控制字符" }
            // ⚠️ beginSend 之后任何异常（含发送 exec 本身）都按「投递不明」收口 Unknown，
            //    绝不能把 Delivering 留给下一轮、让外面以为还能重发。取消不算异常，照常上抛。
            try {
                val result = exec("pane=\$(tmux display-message -p -t ${q("=" + sessionName + ":")} '#{pane_id}') && test \"\$(tmux display-message -p -t \"\$pane\" '#{pid}:#{session_id}:#{session_created}')\" = ${q(runtimeId)} && test \"\$(tmux capture-pane -p -t \"\$pane\")\" = ${q(screen.trimEnd('\n'))} && tmux send-keys -t \"\$pane\" -l ${q(command)} && sleep 0.3 && tmux send-keys -t \"\$pane\" Enter && printf '__YXI_MODEL_REQUEST__'")
                if (!result.contains("__YXI_MODEL_REQUEST__")) {
                    store.markUnknown(taskKey, started.revision, "切换请求送达结果无法确认；不会自动重发，请在终端核对后重新应用")
                    return accepted
                }
                val after = exec(capture)
                when {
                    // ⚠️ 只认发送后新增变化：发送前就在屏上的旧拒绝/旧弹框不算这次的结果
                    ModelSwitchProtocol.rejected(after) && !started.screenHadRejection ->
                        store.markUnknown(taskKey, started.revision, "运行器未接受该模型/强度（屏上新增拒绝提示）；不会自动重发")
                    ModelSwitchProtocol.confirmDialog(after) && !started.screenHadDialog ->
                        store.markAwaitConfirm(taskKey, started.revision) // 等用户在终端自己确认
                    else -> store.markAwaitEvidence(taskKey, started.revision)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { store.markUnknown(taskKey, started.revision,
                "发送过程异常（${e.message}），按键是否送达不明；不会自动重发") }
            return accepted
        }
        // Delivering（上次发送被取消的残留）/ AwaitConfirm（等用户确认）：观察屏面，绝不再发键
        val screen = exec(capture)
        when {
            entry.status == ModelChangeStatus.Delivering && ModelSwitchProtocol.rejected(screen) && !entry.screenHadRejection ->
                store.markUnknown(taskKey, entry.revision, "运行器未接受该模型/强度（屏上新增拒绝提示）；不会自动重发")
            ModelSwitchProtocol.confirmDialog(screen) && !entry.screenHadDialog -> if (entry.status == ModelChangeStatus.Delivering)
                store.markAwaitConfirm(taskKey, entry.revision)
            else -> checkEvidence(taskKey) // 框已被用户处理/无新屏面变化 → 看转录新证据
        }
        return accepted
    }

    /** 只认发送基线之后的新证据：转录文件相同、字节偏移前进、且 Ctx 值相对发送时有变化。
     *  · 无新证据（含旧缓存同值）→ 保持 SentAwaitEvidence 等待，不判 Applied 也不判失败；
     *  · 新回执与请求不符 → 立即 Unknown（不自动重发）；
     *  · 转录文件轮换（file 变了）→ 旧基线不可比，保持等待。 */
    fun checkEvidence(taskKey: String) {
        val entry = store.active(taskKey) ?: return
        if (entry.status != ModelChangeStatus.SentAwaitEvidence && entry.status != ModelChangeStatus.AwaitConfirm) return
        val evidence = sessionEvidence(entry.taskKey)
        if (evidence == null || evidence.file != entry.baselineFile || evidence.offset <= entry.baselineOffset) return
        if (entry.model != null) {
            val now = evidence.model
            if (now == entry.evidenceModel) return // 发送后没有新的模型回执
            if (ModelSwitchProtocol.sameModel(entry.model, now)) store.phaseDone(entry.taskKey, entry.revision, modelPhase = true)
            else store.markUnknown(entry.taskKey, entry.revision, "转录新回执 model=$now 与请求 ${entry.model} 不符；不会自动重发")
        } else {
            val now = evidence.effort
            if (now == entry.evidenceEffort) return // 发送后没有新的 effort 回执（要等下一条 assistant 消息落转录）
            if (ModelSwitchProtocol.sameEffort(requireNotNull(entry.effort), now)) store.phaseDone(entry.taskKey, entry.revision, modelPhase = false)
            else store.markUnknown(entry.taskKey, entry.revision, "转录新回执 effort=$now 与请求 ${entry.effort} 不符；不会自动重发")
        }
    }
}
