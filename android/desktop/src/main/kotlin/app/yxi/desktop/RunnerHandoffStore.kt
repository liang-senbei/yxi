package app.yxi.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class RunnerHandoffStage { Draft, Creating, Created, Delivering, Completed, Unknown }
internal data class RunnerHandoffRecord(val id: String, val sourceTaskKey: String, val targetHost: String,
    val targetEngine: String, val targetProvider: String, val summary: String, val deliveryId: String,
    val stage: RunnerHandoffStage = RunnerHandoffStage.Draft, val targetTaskKey: String? = null,
    val revision: Long = 0, val detail: String = "")

/** Durable intent only. This store never launches a runner, reads source history, or submits messages. */
internal class RunnerHandoffStore(file: File) {
    private val disk = DurableFile(file) { decode(it) }
    private val review = File(file.parentFile, file.name + ".needs-review")
    var records: List<RunnerHandoffRecord> = emptyList(); private set
    var problem: String = ""; private set
    init {
        try {
            disk.read()?.let { records = decode(it) }
            if (disk.recovered) DurableFile.replace(review, "Handoff journal recovered; reconcile native sessions before writes")
            check(!review.exists()) { "交接记录从备份恢复，请核对原生会话后修复记录" }
            val recovered = records.map { if (it.stage in setOf(RunnerHandoffStage.Creating, RunnerHandoffStage.Delivering))
                it.copy(stage = RunnerHandoffStage.Unknown, revision = it.revision + 1, detail = "上次操作缺少确认回执；不会自动重建或重发") else it }
            if (recovered != records) save(recovered)
        } catch (e: Exception) { problem = e.message ?: "交接记录无法读取" }
    }
    @Synchronized fun draft(sourceTaskKey: String, targetHost: String, targetEngine: String, targetProvider: String, summary: String): RunnerHandoffRecord {
        check(records.none { it.sourceTaskKey == sourceTaskKey && it.stage != RunnerHandoffStage.Completed }) { "原会话已有未结束交接，请先核对" }
        val record = RunnerHandoffRecord(UUID.randomUUID().toString(), sourceTaskKey, targetHost, targetEngine, targetProvider,
            summary, UUID.randomUUID().toString())
        save(records + record); return record
    }
    fun edit(id: String, revision: Long, summary: String) = change(id, revision, setOf(RunnerHandoffStage.Draft)) { it.copy(summary = summary) }
    fun beginCreation(id: String, revision: Long) = change(id, revision, setOf(RunnerHandoffStage.Draft)) { it.copy(stage = RunnerHandoffStage.Creating) }
    fun created(id: String, revision: Long, targetTaskKey: String) = change(id, revision, setOf(RunnerHandoffStage.Creating)) {
        it.copy(stage = RunnerHandoffStage.Created, targetTaskKey = targetTaskKey)
    }
    fun beginDelivery(id: String, revision: Long, targetTaskKey: String) = change(id, revision, setOf(RunnerHandoffStage.Created)) {
        check(it.targetTaskKey == targetTaskKey) { "只能继续原交接目标" }
        it.copy(stage = RunnerHandoffStage.Delivering)
    }
    fun completed(id: String, revision: Long, deliveryId: String, receipt: String) = change(id, revision, setOf(RunnerHandoffStage.Delivering)) {
        require(receipt.isNotBlank() && receipt.length <= 4000)
        check(it.deliveryId == deliveryId) { "交接回执身份不匹配" }
        it.copy(stage = RunnerHandoffStage.Completed, detail = receipt)
    }
    fun unknown(id: String, revision: Long, detail: String) = change(id, revision, setOf(RunnerHandoffStage.Creating, RunnerHandoffStage.Delivering)) {
        require(detail.isNotBlank() && detail.length <= 4000)
        it.copy(stage = RunnerHandoffStage.Unknown, detail = detail)
    }
    @Synchronized private fun change(id: String, revision: Long, allowed: Set<RunnerHandoffStage>, update: (RunnerHandoffRecord) -> RunnerHandoffRecord): RunnerHandoffRecord {
        check(problem.isBlank()) { problem }
        val current = records.single { it.id == id }
        check(current.revision == revision && current.stage in allowed) { "交接状态已变化，请刷新核对；不会自动重试" }
        val next = update(current).copy(revision = current.revision + 1)
        save(records.map { if (it.id == id) next else it }); return next
    }
    private fun save(next: List<RunnerHandoffRecord>) {
        check(problem.isBlank()) { problem }
        try { disk.write(encode(next)); records = next }
        catch (e: Exception) { problem = "交接记录未保存，操作已停止：${e.message}"; throw e }
    }
    companion object {
        private fun encode(records: List<RunnerHandoffRecord>) = JSONObject().put("version", 1).put("records", JSONArray(records.map {
            JSONObject().put("id", it.id).put("sourceTaskKey", it.sourceTaskKey).put("targetHost", it.targetHost)
                .put("targetEngine", it.targetEngine).put("targetProvider", it.targetProvider).put("summary", it.summary)
                .put("deliveryId", it.deliveryId).put("stage", it.stage.name).put("targetTaskKey", it.targetTaskKey ?: JSONObject.NULL)
                .put("revision", it.revision).put("detail", it.detail)
        })).toString()
        private fun decode(raw: String): List<RunnerHandoffRecord> {
            require(raw.length <= 8 * 1024 * 1024)
            val data = JSONObject(raw)
            require(data.keySet() == setOf("version", "records") && data.getInt("version") == 1)
            val rows = data.getJSONArray("records"); require(rows.length() <= 1000)
            return (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                require(row.keySet() == setOf("id", "sourceTaskKey", "targetHost", "targetEngine", "targetProvider", "summary", "deliveryId", "stage", "targetTaskKey", "revision", "detail"))
                RunnerHandoffRecord(row.getString("id"), row.getString("sourceTaskKey"), row.getString("targetHost"), row.getString("targetEngine"),
                    row.getString("targetProvider"), row.getString("summary"), row.getString("deliveryId"), RunnerHandoffStage.valueOf(row.getString("stage")),
                    if (row.isNull("targetTaskKey")) null else row.getString("targetTaskKey"), row.getLong("revision"), row.getString("detail")).also {
                    require(UUID.fromString(it.id).toString() == it.id && UUID.fromString(it.deliveryId).toString() == it.deliveryId)
                    require(it.targetEngine in setOf("claude", "codex", "opencode", "gemini", "grok", "hermes"))
                    require(listOf(it.sourceTaskKey, it.targetHost, it.targetProvider).all { value -> value.isNotBlank() && value.length <= 2000 && value.none { c -> c < ' ' } })
                    require(it.summary.isNotBlank() && it.summary.length <= 100_000 && it.revision >= 0 && it.detail.length <= 4000)
                    require(it.targetTaskKey == null || it.targetTaskKey.isNotBlank() && it.targetTaskKey.length <= 2000 && it.targetTaskKey != it.sourceTaskKey && it.targetTaskKey.none { c -> c < ' ' })
                    require(it.stage !in setOf(RunnerHandoffStage.Draft, RunnerHandoffStage.Creating) || it.targetTaskKey == null)
                    require(it.stage !in setOf(RunnerHandoffStage.Created, RunnerHandoffStage.Delivering, RunnerHandoffStage.Completed) || it.targetTaskKey != null)
                }
            }.also { records ->
                require(records.map { it.id }.distinct().size == records.size && records.map { it.deliveryId }.distinct().size == records.size)
                val active = records.filter { it.stage != RunnerHandoffStage.Completed }
                require(active.map { it.sourceTaskKey }.distinct().size == active.size)
            }
        }
    }
}
