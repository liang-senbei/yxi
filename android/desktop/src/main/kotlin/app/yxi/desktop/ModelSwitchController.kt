package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 模型/强度切换意图的状态：Pending 待发 → Delivering 已发待核实 → AwaitConfirm 等用户在终端确认
 * （「Switch model?」弹框绝不能替用户回车）→ Applied（会话转录证据确认生效）/ Unknown（结果不明或被拒，
 * 不自动重发，等用户重新应用）。 */
internal enum class ModelChangeStatus { Pending, Delivering, AwaitConfirm, Unknown, Applied }

internal data class ModelSwitchRequest(
    /** 稳定键 = taskNavigationKey（host+session），与指令队列同源：仅用 runtimeId 会在跨主机时撞。 */
    val taskKey: String,
    /** tmux 身份复核用（send 脚本里的 '#{pid}:#{session_id}:#{session_created}'），不是存储键。 */
    val runtimeId: String,
    val model: String?, val effort: String?,
    val status: ModelChangeStatus = ModelChangeStatus.Pending,
    val detail: String = "", val revision: Long = 0, val readbackTries: Int = 0,
) {
    val active get() = status in setOf(ModelChangeStatus.Pending, ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)
}

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

/** 持久切换意图（每 taskKey 一条）。提交前先落盘 Pending，发送前落盘 Delivering，
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
                if (it.status in setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm) || (fromBackup && it.status == ModelChangeStatus.Pending))
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

    /** 菜单意图入库。在途（Delivering/AwaitConfirm）时不接收 —— 返回 false，调用方保留意图下轮再试。 */
    @Synchronized fun propose(taskKey: String, runtimeId: String, model: String?, effort: String?): Boolean {
        if (model.isNullOrBlank() && effort.isNullOrBlank()) return false
        val existing = entries.firstOrNull { it.taskKey == taskKey }
        if (existing != null && existing.status in setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) return false
        commit(entries.filter { it.taskKey != taskKey } + ModelSwitchRequest(
            taskKey, runtimeId, model?.trim(), effort?.trim(), revision = (existing?.revision ?: 0L) + 1))
        return true
    }

    /** Pending → Delivering（先落盘再发键），返回落盘后的条目供发送使用。 */
    @Synchronized fun beginSend(taskKey: String): ModelSwitchRequest {
        val current = entries.single { it.taskKey == taskKey && it.status == ModelChangeStatus.Pending }
        return commitOne(current) { it.copy(status = ModelChangeStatus.Delivering) }
    }

    @Synchronized fun markAwaitConfirm(taskKey: String, revision: Long) = edit(taskKey, revision,
        setOf(ModelChangeStatus.Delivering)) { it.copy(status = ModelChangeStatus.AwaitConfirm) }

    /** model 段证据确认：还有 effort 段就回到 Pending 等下一条命令，否则收口 Applied；
     * effort 段确认即 Applied（相位由「model 字段是否还在」唯一决定）。 */
    @Synchronized fun phaseDone(taskKey: String, revision: Long, modelPhase: Boolean) = edit(taskKey, revision,
        setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) {
        when {
            modelPhase && it.effort != null -> it.copy(model = null, status = ModelChangeStatus.Pending)
            else -> it.copy(model = null, effort = null, status = ModelChangeStatus.Applied,
                detail = "已生效（本会话转录回执确认）")
        }
    }

    /** 证据未确认计一次；满 3 次转 Unknown（detail 带实况），返回是否已转 Unknown。 */
    @Synchronized fun readbackRetry(taskKey: String, revision: Long, actual: String): Boolean {
        val current = entries.single { it.taskKey == taskKey && it.revision == revision }
        val tries = current.readbackTries + 1
        if (tries < 3) {
            edit(taskKey, revision, setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) {
                it.copy(readbackTries = tries) }
            return false
        }
        edit(taskKey, revision, setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) {
            it.copy(status = ModelChangeStatus.Unknown,
                detail = "本会话转录未见生效回执（$actual）；不会自动重发，请在终端核对后重新应用") }
        return true
    }

    @Synchronized fun markUnknown(taskKey: String, revision: Long, detail: String) = edit(taskKey, revision,
        setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) {
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
                .put("detail", it.detail).put("revision", it.revision).put("readbackTries", it.readbackTries) })).toString(2)
        private fun decode(raw: String): List<ModelSwitchRequest> {
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val items = root.getJSONArray("items")
            return (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                ModelSwitchRequest(o.getString("taskKey"), o.getString("runtimeId"),
                    o.optString("model").ifBlank { null }, o.optString("effort").ifBlank { null },
                    ModelChangeStatus.valueOf(o.getString("status")), o.optString("detail"),
                    o.getLong("revision"), o.optInt("readbackTries", 0))
            }.also { list ->
                require(list.all { it.taskKey.isNotBlank() && it.runtimeId.isNotBlank() })
                require(list.map { it.taskKey }.distinct().size == list.size)
            }
        }
    }
}

/** 切换协议执行体：无自有线程与锁 —— 调用方（TerminalQueueRunner）持有 [Conn.instructionDeliveryMutex]，
 * 每个 tick 至多一步；门判定与证据源以默认参数注入，测试可替换
 * （Model.borrowable 需真 Claude Code 输入框，伪不出 —— DeferredRouteRunnerTest 同款结论）。 */
internal class ModelSwitchController(
    private val store: ModelChangeStore,
    private val borrowable: (String) -> Boolean = app.yxi.agent.Model::borrowable,
    private val pendingPrompt: (String) -> Any? = { app.yxi.agent.Prompt.parse(it) },
    /** 会话级生效证据：本会话转录的 Ctx —— model 来自「Set model to …」回执/assistant `message.model`，
     *  effort 来自转录行顶层 `effort`。与菜单展示的 currentModel/currentEffort 同源
     *  （DesktopTranscriptMemory，ChatPane 同一条链）。⚠️ 全局 `~/.claude/settings.json`
     *  **不是**本会话有效模型的证据：同主机多会话并存、`/model` 是会话级。 */
    private val sessionCtx: (String) -> app.yxi.agent.Transcript.Ctx? = { DesktopTranscriptMemory.get(it)?.view?.context },
) {
    /** @return 意图是否已入库（true 时调用方可从 conn.modelChanges 移除）。 */
    suspend fun step(exec: suspend (String) -> String, taskKey: String, runtimeId: String, sessionName: String,
                     intent: ConversationModelChange?): Boolean {
        val accepted = intent != null && store.propose(taskKey, runtimeId,
            intent.model?.trim()?.ifBlank { null }, intent.effort?.trim()?.ifBlank { null })
        val entry = store.active(taskKey) ?: return accepted
        if (!entry.active) return accepted
        val q = app.yxi.ssh.Shell::q
        val capture = "tmux capture-pane -p -t ${q("=" + sessionName + ":")} 2>/dev/null"
        if (entry.status == ModelChangeStatus.Pending) {
            val screen = exec(capture)
            // 空闲 + 无审批框 + 无残留确认框才发；不满足就原样等下一 tick，绝不补键
            if (!borrowable(screen) || pendingPrompt(screen) != null || ModelSwitchProtocol.confirmDialog(screen)) return accepted
            val started = store.beginSend(taskKey) // 先落盘再 IO
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
                    ModelSwitchProtocol.rejected(after) -> store.markUnknown(taskKey, started.revision,
                        "运行器未接受该模型/强度（屏上出现拒绝提示）；不会自动重发")
                    ModelSwitchProtocol.confirmDialog(after) -> store.markAwaitConfirm(taskKey, started.revision)
                    else -> Unit // 发送当步不判定：回执要等本会话转录，下一 tick 由观察分支核实
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { store.markUnknown(taskKey, started.revision,
                "发送过程异常（${e.message}），按键是否送达不明；不会自动重发") }
            return accepted
        }
        // Delivering / AwaitConfirm：只观察（无 IO 状态推进），绝不再发键；exec 异常照常上抛，
        // 状态留在 Delivering/AwaitConfirm，下一 tick 继续观察 —— 这里没有按键要收口。
        observe(entry, exec(capture))
        return accepted
    }

    private fun observe(entry: ModelSwitchRequest, screen: String) {
        when {
            entry.status == ModelChangeStatus.Delivering && ModelSwitchProtocol.rejected(screen) ->
                store.markUnknown(entry.taskKey, entry.revision, "运行器未接受该模型/强度（屏上出现拒绝提示）；不会自动重发")
            ModelSwitchProtocol.confirmDialog(screen) -> if (entry.status == ModelChangeStatus.Delivering)
                store.markAwaitConfirm(entry.taskKey, entry.revision) // 等用户在终端自己确认
            else -> verify(entry)
        }
    }

    /** 回执判定只认本会话转录证据；证据未到 ≠ 已生效 —— 计入重试，满了转 Unknown（未知诚实）。
     *  ⚠️ effort 的证据要等下一条 assistant 消息落转录（顶层 effort 字段），此前不硬判。 */
    private fun verify(entry: ModelSwitchRequest) {
        val ctx = sessionCtx(entry.taskKey)
        if (entry.model != null) {
            val actual = ctx?.model.orEmpty()
            if (actual.isNotBlank() && ModelSwitchProtocol.sameModel(entry.model, actual))
                store.phaseDone(entry.taskKey, entry.revision, modelPhase = true)
            else store.readbackRetry(entry.taskKey, entry.revision, "model=${actual.ifBlank { "尚无回执" }}")
        } else {
            val actual = ctx?.effort.orEmpty()
            if (actual.isNotBlank() && ModelSwitchProtocol.sameEffort(requireNotNull(entry.effort), actual))
                store.phaseDone(entry.taskKey, entry.revision, modelPhase = false)
            else store.readbackRetry(entry.taskKey, entry.revision, "effort=${actual.ifBlank { "尚无回执" }}")
        }
    }
}
