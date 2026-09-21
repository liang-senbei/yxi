package app.yxi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 模型/强度切换意图的状态：Pending 待发 → Delivering 已发待核实 → AwaitConfirm 等用户在终端确认
 * （「Switch model?」弹框绝不能替用户回车）→ Applied（回读确认生效）/ Unknown（结果不明或被拒，
 * 不自动重发，等用户重新应用）。 */
internal enum class ModelChangeStatus { Pending, Delivering, AwaitConfirm, Unknown, Applied }

internal data class ModelSwitchRequest(
    val runtimeId: String, val model: String?, val effort: String?,
    val status: ModelChangeStatus = ModelChangeStatus.Pending,
    val detail: String = "", val revision: Long = 0, val readbackTries: Int = 0,
) {
    val active get() = status in setOf(ModelChangeStatus.Pending, ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)
}

/** 切换协议的纯判定，全部可单测。[app.yxi.agent.Model.canonical] 只用于显示；
 * 这里发送与比对一律原样，未知原生模型不得被加 claude- 前缀。 */
internal object ModelSwitchProtocol {
    val CONFIRM_TITLES = listOf("Switch model?", "Change effort level?")
    fun confirmDialog(screen: String) = CONFIRM_TITLES.any { it in screen }

    /** 屏上出现这些 = 运行器明确拒绝：名字不认或不在白名单（同 core Model.FAILED 语义）。 */
    val REJECTED = Regex("""Model '[^']*' not found|unrecognized_model|Kept model as [\w .()\[\]-]+""")
    fun rejected(screen: String) = REJECTED.containsMatchIn(screen)

    fun command(model: String?, effort: String?): String =
        if (model != null) "/model ${model.trim()}" else "/effort ${requireNotNull(effort).trim()}"

    private val normalize = { s: String -> s.trim().removePrefix("claude-").removeSuffix("[1m]").lowercase() }
    fun sameModel(requested: String, effective: String) = effective.isNotBlank() && normalize(requested) == normalize(effective)
    fun sameEffort(requested: String, effective: String) = effective.isNotBlank() && requested.trim() == effective.trim()

    fun parseSettings(raw: String): Pair<String, String> {
        val o = JSONObject(raw)
        return o.optString("model") to o.optString("effortLevel")
    }
}

/** 持久切换意图（每 runtimeId 一条）。提交前先落盘 Pending，发送前落盘 Delivering，
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

    fun active(runtimeId: String) = entries.firstOrNull { it.runtimeId == runtimeId && it.active }

    /** 菜单意图入库。在途（Delivering/AwaitConfirm）时不接收 —— 返回 false，调用方保留意图下轮再试。 */
    @Synchronized fun propose(runtimeId: String, model: String?, effort: String?): Boolean {
        if (model.isNullOrBlank() && effort.isNullOrBlank()) return false
        val existing = entries.firstOrNull { it.runtimeId == runtimeId }
        if (existing != null && existing.status in setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) return false
        commit(entries.filter { it.runtimeId != runtimeId } + ModelSwitchRequest(
            runtimeId, model?.trim(), effort?.trim(), revision = (existing?.revision ?: 0L) + 1))
        return true
    }

    /** Pending → Delivering（先落盘再发键），返回落盘后的条目供发送使用。 */
    @Synchronized fun beginSend(runtimeId: String): ModelSwitchRequest {
        val current = entries.single { it.runtimeId == runtimeId && it.status == ModelChangeStatus.Pending }
        return commitOne(current) { it.copy(status = ModelChangeStatus.Delivering) }
    }

    @Synchronized fun markAwaitConfirm(runtimeId: String, revision: Long) = edit(runtimeId, revision,
        setOf(ModelChangeStatus.Delivering)) { it.copy(status = ModelChangeStatus.AwaitConfirm) }

    /** model 段回读通过：还有 effort 段就回到 Pending 等下一条命令，否则收口 Applied；
     * effort 段通过即 Applied（相位由「model 字段是否还在」唯一决定，不靠第二条路径）。 */
    @Synchronized fun phaseDone(runtimeId: String, revision: Long, modelPhase: Boolean) = edit(runtimeId, revision,
        setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) {
        when {
            modelPhase && it.effort != null -> it.copy(model = null, status = ModelChangeStatus.Pending)
            else -> it.copy(model = null, effort = null, status = ModelChangeStatus.Applied,
                detail = "已生效（回读 settings.json 确认）")
        }
    }

    /** 回读未确认计一次；满 3 次转 Unknown（detail 带回读实况），返回是否已转 Unknown。 */
    @Synchronized fun readbackRetry(runtimeId: String, revision: Long, actual: String): Boolean {
        val current = entries.single { it.runtimeId == runtimeId && it.revision == revision }
        val tries = current.readbackTries + 1
        if (tries < 3) {
            edit(runtimeId, revision, setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) {
                it.copy(readbackTries = tries) }
            return false
        }
        edit(runtimeId, revision, setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) {
            it.copy(status = ModelChangeStatus.Unknown,
                detail = "回读 settings.json 未看到生效（$actual）；不会自动重发，请在终端核对后重新应用") }
        return true
    }

    @Synchronized fun markUnknown(runtimeId: String, revision: Long, detail: String) = edit(runtimeId, revision,
        setOf(ModelChangeStatus.Delivering, ModelChangeStatus.AwaitConfirm)) {
        it.copy(status = ModelChangeStatus.Unknown, detail = detail) }

    private fun edit(runtimeId: String, revision: Long, allowed: Set<ModelChangeStatus>,
                     transform: (ModelSwitchRequest) -> ModelSwitchRequest): ModelSwitchRequest {
        val current = entries.single { it.runtimeId == runtimeId && it.revision == revision }
        check(current.status in allowed) { "切换状态已变化，请刷新后重试" }
        return commitOne(current, transform)
    }

    private fun commitOne(current: ModelSwitchRequest, transform: (ModelSwitchRequest) -> ModelSwitchRequest): ModelSwitchRequest {
        val next = transform(current).copy(revision = current.revision + 1)
        commit(entries.map { if (it.runtimeId == current.runtimeId) next else it })
        return next
    }

    @Synchronized private fun commit(next: List<ModelSwitchRequest>) {
        check(readable) { error }
        try { disk.write(encode(next)); entries = next; error = "" }
        catch (e: Exception) { error = "切换记录未保存：${e.message}"; throw e }
    }

    companion object {
        private fun encode(items: List<ModelSwitchRequest>) = JSONObject().put("version", 1).put("items",
            JSONArray(items.map { JSONObject().put("runtimeId", it.runtimeId).put("model", it.model ?: "")
                .put("effort", it.effort ?: "").put("status", it.status.name).put("detail", it.detail)
                .put("revision", it.revision).put("readbackTries", it.readbackTries) })).toString(2)
        private fun decode(raw: String): List<ModelSwitchRequest> {
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val items = root.getJSONArray("items")
            return (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                ModelSwitchRequest(o.getString("runtimeId"), o.optString("model").ifBlank { null },
                    o.optString("effort").ifBlank { null }, ModelChangeStatus.valueOf(o.getString("status")),
                    o.optString("detail"), o.getLong("revision"), o.optInt("readbackTries", 0))
            }.also { list -> require(list.map { it.runtimeId }.distinct().size == list.size) }
        }
    }
}

/** 切换协议执行体：无自有线程与锁 —— 调用方（TerminalQueueRunner）持有 [Conn.instructionDeliveryMutex]，
 * 每个 tick 至多一步；门判定（borrowable/审批框）以默认参数注入，测试可替换
 * （Model.borrowable 需真 Claude Code 输入框，伪不出 —— DeferredRouteRunnerTest 同款结论）。
 * 注意：普通 /model 若是会话级不落 settings.json，回读对不上会转 Unknown 等人工核对 ——
 * 这是「成功须回读」的诚实代价，不猜屏幕文案补确认。 */
internal class ModelSwitchController(
    private val store: ModelChangeStore,
    private val borrowable: (String) -> Boolean = app.yxi.agent.Model::borrowable,
    private val pendingPrompt: (String) -> Any? = { app.yxi.agent.Prompt.parse(it) },
) {
    /** @return 意图是否已入库（true 时调用方可从 conn.modelChanges 移除）。 */
    suspend fun step(exec: suspend (String) -> String, runtimeId: String, sessionName: String,
                     intent: ConversationModelChange?): Boolean {
        val accepted = intent != null && store.propose(runtimeId,
            intent.model?.trim()?.ifBlank { null }, intent.effort?.trim()?.ifBlank { null })
        val entry = store.active(runtimeId) ?: return accepted
        if (!entry.active) return accepted
        val q = app.yxi.ssh.Shell::q
        val capture = "tmux capture-pane -p -t ${q("=" + sessionName + ":")} 2>/dev/null"
        if (entry.status == ModelChangeStatus.Pending) {
            val screen = exec(capture)
            // 空闲 + 无审批框 + 无残留确认框才发；不满足就原样等下一 tick，绝不补键
            if (!borrowable(screen) || pendingPrompt(screen) != null || ModelSwitchProtocol.confirmDialog(screen)) return accepted
            val started = store.beginSend(runtimeId) // 先落盘再 IO
            val command = ModelSwitchProtocol.command(started.model, started.effort)
            require(command.none { it < ' ' }) { "切换命令含控制字符" }
            val result = exec("pane=\$(tmux display-message -p -t ${q("=" + sessionName + ":")} '#{pane_id}') && test \"\$(tmux display-message -p -t \"\$pane\" '#{pid}:#{session_id}:#{session_created}')\" = ${q(runtimeId)} && test \"\$(tmux capture-pane -p -t \"\$pane\")\" = ${q(screen.trimEnd('\n'))} && tmux send-keys -t \"\$pane\" -l ${q(command)} && sleep 0.3 && tmux send-keys -t \"\$pane\" Enter && printf '__YXI_MODEL_REQUEST__'")
            if (!result.contains("__YXI_MODEL_REQUEST__")) {
                store.markUnknown(runtimeId, started.revision, "切换请求送达结果无法确认；不会自动重发，请在终端核对后重新应用")
                return accepted
            }
            val after = exec(capture)
            when {
                ModelSwitchProtocol.rejected(after) -> store.markUnknown(runtimeId, started.revision,
                    "运行器未接受该模型/强度（屏上出现拒绝提示）；不会自动重发")
                ModelSwitchProtocol.confirmDialog(after) -> store.markAwaitConfirm(runtimeId, started.revision)
                else -> Unit // 发送当步不回读：CLI 落盘需要时间，下一 tick 由观察分支核实
            }
            return accepted
        }
        // Delivering / AwaitConfirm：只观察，绝不再发键
        observe(entry, exec(capture), exec)
        return accepted
    }

    private suspend fun observe(entry: ModelSwitchRequest, screen: String, exec: suspend (String) -> String) {
        when {
            entry.status == ModelChangeStatus.Delivering && ModelSwitchProtocol.rejected(screen) ->
                store.markUnknown(entry.runtimeId, entry.revision, "运行器未接受该模型/强度（屏上出现拒绝提示）；不会自动重发")
            ModelSwitchProtocol.confirmDialog(screen) -> if (entry.status == ModelChangeStatus.Delivering)
                store.markAwaitConfirm(entry.runtimeId, entry.revision) // 等用户在终端自己确认
            else -> verify(entry, exec)
        }
    }

    /** 回读 ~/.claude/settings.json：只认当前段（model 字段还在即 model 段），过了交给 store 推进相位。 */
    private suspend fun verify(entry: ModelSwitchRequest, exec: suspend (String) -> String) {
        val parsed = runCatching {
            ModelSwitchProtocol.parseSettings(exec("cat \"\$HOME/.claude/settings.json\" 2>/dev/null"))
        }.getOrDefault("" to "")
        if (entry.model != null) {
            if (ModelSwitchProtocol.sameModel(entry.model, parsed.first)) store.phaseDone(entry.runtimeId, entry.revision, modelPhase = true)
            else store.readbackRetry(entry.runtimeId, entry.revision, "model=${parsed.first}")
        } else {
            if (ModelSwitchProtocol.sameEffort(requireNotNull(entry.effort), parsed.second)) store.phaseDone(entry.runtimeId, entry.revision, modelPhase = false)
            else store.readbackRetry(entry.runtimeId, entry.revision, "effortLevel=${parsed.second}")
        }
    }
}
