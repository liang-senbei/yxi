package app.yxi.desktop

import app.yxi.agent.Lines
import app.yxi.agent.RemoteAtomicJson
import app.yxi.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * **Agent 绑定** —— 每个 Agent（tmux 会话）各自用哪个运行器（engine）+ 哪份已保存的供应商配置（profileId）。
 *
 * **只存引用**：profileId 指向该 engine 自己那份配置清单里的条目（claude/codex 的线路 id、
 * OpenCode 的 provider id……），密钥 / baseUrl / 模型等实际配置内容**一律不进这个文件** ——
 * 复制一份内容就有了第二个真相源，改了清单这里就是假话。
 *
 * 文件在服务器上 `~/.yxi/agent-bindings.json`（⚠️ 必须 `$HOME` 解出绝对路径再走 SFTP，SFTP 不认 `~`；
 * 落盘 0600、父目录 0700，由 RemoteAtomicJson 的临时文件 + 原子改名保证）：
 *
 * ```
 * {"version":1,"bindings":{"<tmux 会话名>":{
 *     "desired":{"engine":"codex","profileId":"p2"},
 *     "applied":{"engine":"codex","profileId":"p1","runtimeId":"4049:$3:1790029828","verifiedAt":1790050600}}}}
 * ```
 *
 * ⚠️⚠️ **desired / applied 是两层，绝不能合并**：
 * - `desired` = 用户此刻的选择。一改立刻落盘，`applied` **不跟着动** → [Binding.pending] 为 true，
 *   界面照实说「待应用」。
 * - `applied` = **核对过**确实在那个进程上生效的选择。**只有 [markApplied] 能写它**，而且必须带上
 *   调用者动手时看到的 desired —— 若回执迟到、用户已改选，写入被拒绝。没有这道门，
 *   迟到的异步回执会把「待应用」谎报成「已应用」（#254 那类「静默说谎」）。
 *
 * ⚠️ **读不懂就拒绝覆盖**：坏 JSON、version 不是 1、顶层/绑定/字段结构不认识、engine 不在名单里 ——
 * 一律返回错误、文件一个字节不动。绝不「当成空表重建」：那会把手改的或新版 App 写的文件覆盖掉。
 *
 * ⚠️ 写入走 [RemoteAtomicJson] 的 CAS：读快照 → 内存改 → 带 revision 提交。并发冲突**明确报错**、
 * 不自动重试 —— 自动重试会把别人刚写进去的一批绑定顶掉。改一个 Agent 是读全文、改一条、写回，
 * 其他 Agent 的条目原样保留；选择没变时干脆不写（空转的 CAS 也会把并发写者顶掉）。
 *
 * **这个组件只管存取**：不重启进程、不改任何运行器的全局配置文件、不解析 profileId 指向的内容。
 * 把 desired 落到进程上并验证，是运行器接入层（root）的事；它做完核对后调 [markApplied]。
 * modelFetch / 图标 / ProjectTree 一概不在本组件范围。
 */
object AgentBindingStore {

    /** 支持的运行器，顺序与配置页 [configurationEngines] 一致。 */
    val ENGINES: List<String> = listOf(Lines.CLAUDE, Lines.CODEX, "opencode", "gemini", "grok", "hermes")

    private const val VERSION = 1

    /**
     * 用户的选择。[profileId] 为空串 = 沿用运行器现有配置，不保证使用官方订阅；不指向已存线路。
     * 两个值都只是**引用**，这里没有密钥的位置。
     */
    data class Desired(val engine: String, val profileId: String)

    /**
     * 核对过已在某次进程 incarnation 上生效的记录。[runtimeId] 是 tmux 的
     * `#{pid}:#{session_id}:#{session_created}`（和 SessionProbe 同一身份源，会话一重建就是新的）；
     * [verifiedAt] 为 epoch 秒。
     */
    data class Applied(val engine: String, val profileId: String, val runtimeId: String, val verifiedAt: Double)

    /** 一个 Agent 的完整绑定。[applied] 为 null = 从来没应用过。 */
    data class Binding(val desired: Desired, val applied: Applied?) {
        /** true = desired 变了但还没核对生效，界面必须显示「待应用」，不能拿旧的 applied 糊弄。 */
        val pending: Boolean
            get() = applied == null || applied.engine != desired.engine || applied.profileId != desired.profileId
    }

    /**
     * 读全部绑定。会话名 → [Binding]。
     * @return null = 拿不到（没连上 / 文件损坏不认识 —— 跟「一条绑定都没有」是两回事，界面要分开说）。
     */
    suspend fun list(ssh: SshSession?): Map<String, Binding>? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext null
        val h = home(s) ?: return@withContext null
        list(RemoteStorage(s, path(h)))
    }

    /**
     * 写入/更新一个 Agent 的 desired。**只动这一条**，其他 Agent 与它已有的 applied 原样保留
     * （改选择后 applied 留着旧值，[Binding.pending] 自动变 true —— 历史记录不该被选择的改动抹掉）。
     *
     * @param expected 调用者最近一次 [list] 看到的全文；这期间别人改过就拒绝（防「按旧清单覆盖」）。
     * @return null = 已写入；非空 = 出错原因（含 CAS 冲突、文件损坏拒绝覆盖）。
     */
    suspend fun setDesired(ssh: SshSession?, sessionName: String, desired: Desired,
                           expected: Map<String, Binding>? = null): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val h = home(s) ?: return@withContext "取不到家目录"
        setDesired(RemoteStorage(s, path(h)), sessionName, desired, expected)
    }

    /**
     * 运行器接入层**核对完成后**才准调用：把「[expectedDesired] 这份选择确实在 [runtimeId] 这个进程上
     * 生效了（核对时刻 [verifiedAt]）」记录成 applied。applied 的 engine/profileId 恒等于核对过的 desired ——
     * 「核对的是 A、记成 B」没有存在的理由。
     *
     * 三道门，任一不过都不写：
     * 1. 该 Agent 的 desired 现在必须仍等于 [expectedDesired] —— 迟到的回执撞上用户改选，拒绝；
     * 2. applied 已与 desired 一致且 [verifiedAt] 更旧 —— 重复/乱序回执，拒绝（相同 verifiedAt 幂等放行）；
     * 3. CAS：这期间文件被别人写过，冲突明确报错，不重试。
     *
     * @return null = 已记录；非空 = 拒绝原因。
     */
    suspend fun markApplied(ssh: SshSession?, sessionName: String, expectedDesired: Desired,
                            runtimeId: String, verifiedAt: Double): String? = withContext(Dispatchers.IO) {
        val s = ssh ?: return@withContext "没连上"
        val h = home(s) ?: return@withContext "取不到家目录"
        markApplied(RemoteStorage(s, path(h)), sessionName, expectedDesired, runtimeId, verifiedAt)
    }

    // ── 存储缝：生产包 RemoteAtomicJson，测试用内存假实现 ─────────────────────

    internal interface BindingStorage {
        suspend fun read(): RemoteAtomicJson.Snapshot
        suspend fun write(text: String, expected: String): String?
    }

    private class RemoteStorage(private val s: SshSession, private val file: String) : BindingStorage {
        override suspend fun read(): RemoteAtomicJson.Snapshot = RemoteAtomicJson.read(s, file)
        override suspend fun write(text: String, expected: String): String? = RemoteAtomicJson.write(s, file, text, expected)
    }

    internal suspend fun list(storage: BindingStorage): Map<String, Binding>? {
        val snap = try { storage.read() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return null }
        return parseDoc(snap.text)
    }

    internal suspend fun setDesired(storage: BindingStorage, sessionName: String, desired: Desired,
                                    expected: Map<String, Binding>? = null): String? {
        if (!validName(sessionName)) return "会话名不合法，未写入"
        if (desired.engine !in ENGINES) return "不认识的运行器：${desired.engine}（可选：${ENGINES.joinToString("、")}）"
        if (!validRef(desired.profileId)) return "profileId 不合法，未写入"
        return update(storage, expected) { m ->
            m[sessionName] = Binding(desired, m[sessionName]?.applied)
            null
        }
    }

    internal suspend fun markApplied(storage: BindingStorage, sessionName: String, expectedDesired: Desired,
                                     runtimeId: String, verifiedAt: Double): String? {
        if (!validName(sessionName)) return "会话名不合法，确认结果不写入"
        if (expectedDesired.engine !in ENGINES) return "不认识的运行器：${expectedDesired.engine}"
        if (!validRef(runtimeId) || runtimeId.isBlank()) return "runtimeId 不合法，确认结果不写入"
        if (!verifiedAt.isFinite() || verifiedAt < 0) return "verifiedAt 不合法，确认结果不写入"
        return update(storage, null) { m ->
            val cur = m[sessionName]
            if (cur?.desired != expectedDesired)
                return@update "该 Agent 的选择已变化（现在：${cur?.desired ?: "未选择"}），确认结果不写入"
            val old = cur.applied
            if (old != null && old.engine == expectedDesired.engine && old.profileId == expectedDesired.profileId && old.verifiedAt > verifiedAt)
                return@update "已有更新的确认记录（${old.verifiedAt}），旧回执（$verifiedAt）不写入"
            m[sessionName] = Binding(expectedDesired, Applied(expectedDesired.engine, expectedDesired.profileId, runtimeId, verifiedAt))
            null
        }
    }

    // ── 文档：parse 拒绝一切不认识的结构；encode 键序固定（expected 比对即字符串全等）──

    internal fun parseDoc(text: String?): Map<String, Binding>? {
        if (text == null) return emptyMap()
        return runCatching {
            val root = JSONObject(text)
            require(root.keys().asSequence().toSet() == setOf("version", "bindings")) { "顶层结构不认识" }
            require(root.opt("version") == VERSION) { "version 只认 $VERSION" }
            val b = root.opt("bindings") as? JSONObject ?: throw IllegalArgumentException("bindings 不是对象")
            val out = LinkedHashMap<String, Binding>()
            b.keys().asSequence().forEach { name ->
                require(validName(name)) { "会话名不合法" }
                val e = b.opt(name) as? JSONObject ?: throw IllegalArgumentException("绑定项不是对象")
                val keys = e.keys().asSequence().toSet()
                require(keys == setOf("desired") || keys == setOf("desired", "applied")) { "绑定项字段不认识" }
                val d = parseDesired(e.opt("desired") as? JSONObject ?: throw IllegalArgumentException("desired 缺失或不是对象"))
                val rawApplied = e.opt("applied")
                require(rawApplied == null || rawApplied === JSONObject.NULL || rawApplied is JSONObject)
                val a = (rawApplied as? JSONObject)?.let(::parseApplied)
                out[name] = Binding(d, a)
            }
            out
        }.getOrNull()
    }

    private fun parseDesired(o: JSONObject): Desired {
        require(o.keys().asSequence().toSet() == setOf("engine", "profileId")) { "desired 字段不认识" }
        val engine = o.opt("engine") as? String ?: throw IllegalArgumentException("engine 不是字符串")
        require(engine in ENGINES) { "engine 不认识：$engine" }
        val profileId = o.opt("profileId") as? String ?: throw IllegalArgumentException("profileId 不是字符串")
        require(validRef(profileId)) { "profileId 不合法" }
        return Desired(engine, profileId)
    }

    private fun parseApplied(o: JSONObject): Applied {
        require(o.keys().asSequence().toSet() == setOf("engine", "profileId", "runtimeId", "verifiedAt")) { "applied 字段不认识" }
        val engine = o.opt("engine") as? String ?: throw IllegalArgumentException("engine 不是字符串")
        require(engine in ENGINES) { "engine 不认识：$engine" }
        val profileId = o.opt("profileId") as? String ?: throw IllegalArgumentException("profileId 不是字符串")
        require(validRef(profileId)) { "profileId 不合法" }
        val runtimeId = o.opt("runtimeId") as? String ?: throw IllegalArgumentException("runtimeId 不是字符串")
        require(runtimeId.isNotBlank() && validRef(runtimeId)) { "runtimeId 无效" }
        val verifiedAt = (o.opt("verifiedAt") as? Number)?.toDouble() ?: throw IllegalArgumentException("verifiedAt 不是数字")
        require(verifiedAt.isFinite() && verifiedAt >= 0) { "verifiedAt 不合法" }
        return Applied(engine, profileId, runtimeId, verifiedAt)
    }

    /** 键序固定（绑定按会话名排序）→ 同一文档编码恒等，expected 比对就是字符串全等。 */
    internal fun encode(bindings: Map<String, Binding>): String {
        val b = JSONObject()
        bindings.keys.sorted().forEach { name ->
            val binding = bindings[name] ?: return@forEach
            val d = binding.desired
            val e = JSONObject().put("desired", JSONObject().put("engine", d.engine).put("profileId", d.profileId))
            binding.applied?.let { a ->
                e.put("applied", JSONObject().put("engine", a.engine).put("profileId", a.profileId)
                    .put("runtimeId", a.runtimeId).put("verifiedAt", a.verifiedAt))
            }
            b.put(name, e)
        }
        return JSONObject().put("version", VERSION).put("bindings", b).toString(2)
    }

    /** 一次「读 → 核对 → 改 → CAS 提交」。冲突/损坏/拒绝都如实返回错误，绝不重试、绝不回退重写。 */
    private suspend fun update(storage: BindingStorage, expected: Map<String, Binding>?,
                               mutate: (MutableMap<String, Binding>) -> String?): String? {
        val snap = try { storage.read() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return "绑定文件无法读取，未覆盖：${e.message?.take(60) ?: "未知错误"}" }
        val doc = parseDoc(snap.text)
            ?: return "绑定文件无法识别或已损坏（只认 version $VERSION 的 agent-bindings 结构），未覆盖"
        if (expected != null && encode(expected) != encode(doc)) return "绑定清单已被其他人修改，本次未覆盖，请刷新后重试"
        val next = doc.toMutableMap()
        mutate(next)?.let { return it }
        val text = encode(next)
        if (text == encode(doc)) return null   // 没变化就不写：空转的 CAS 会把并发写者顶掉
        return storage.write(text, snap.revision)
    }

    // ── 杂 ──────────────────────────────────────────────────────────────────

    /** 会话名来自 tmux（信任边界外），落键前先过一道：非空、无控制字符、别超长。 */
    private fun validName(name: String) = name.isNotBlank() && name.length <= 200 && name.none { it < ' ' || it == '\u007f' }

    /** profileId 空串合法（= 默认配置）；一旦有值，同样只收干净短文本。runtimeId 复用同一道。 */
    private fun validRef(ref: String) = ref.length <= 200 && ref.none { it < ' ' || it == '\u007f' }

    private suspend fun home(s: SshSession): String? =
        s.exec("printf %s \"\$HOME\"")?.trim()?.takeIf { it.isNotBlank() }

    private fun path(home: String) = "$home/.yxi/agent-bindings.json"
}
