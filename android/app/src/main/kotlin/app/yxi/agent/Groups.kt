package app.yxi.agent

import app.yxi.ssh.SshSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话分组 —— 把几个会话编成一组，组里的 agent 能互相说话。
 *
 * ⚠️ **分组表存在服务器上（`~/.yxi/groups.json`），不存在手机里。**
 * 因为读它的不只有手机，还有**组里的 agent 自己**（`yxi-hub who` 要回答
 * 「我同组有谁」）。存进手机的 SharedPreferences 的话，agent 永远看不到，
 * 「打通」就只是个视觉分类。换手机、多台手机也都还在。
 *
 * ⚠️ **一个会话可以同时在好几个组里**（用户明确要的）。所以成员关系是
 * 多对多，[of] 返回的是「它所在的所有组」，不是一个。
 */
object Groups {

    /** 分组表。组名 → 成员会话名（带 `cc-` 前缀，跟 tmux 里一致）。 */
    data class Table(
        val groups: Map<String, List<String>> = emptyMap(),
        /**
         * 组规：组名 → 一段话。每个会话开始时（含 resume / compact）由服务器上的 `yxi-hub context`
         * 注入给本组每个会话 —— 用户要的「给分组注入指令」，不用建文件、不用改 CLAUDE.md，手机上编辑即生效。
         */
        val rules: Map<String, String> = emptyMap(),
    ) {

        /** 这个会话在哪几个组里。 */
        fun of(session: String): List<String> =
            groups.filter { session in it.value }.keys.sorted()

        /** 同组的其他人（跨它所在的全部组，去重）。 */
        fun matesOf(session: String): List<String> =
            groups.values.filter { session in it }.flatten().filter { it != session }.distinct()

        /** 一个会话都没编进去的组也留着 —— 用户建了组还没往里放人，别给它删了。 */
        fun withMember(group: String, session: String): Table =
            copy(groups = groups + (group to ((groups[group] ?: emptyList()) + session).distinct()))

        fun withoutMember(group: String, session: String): Table =
            copy(groups = groups + (group to (groups[group] ?: emptyList()).filter { it != session }))

        fun withoutGroup(group: String): Table = copy(groups = groups - group, rules = rules - group)

        /** 建一个空组。已经有了就原样返回。 */
        fun withGroup(group: String): Table =
            if (group in groups) this else copy(groups = groups + (group to emptyList()))

        /** 设组规；空串 = 删掉 */
        fun withRule(group: String, text: String): Table =
            copy(rules = if (text.isBlank()) rules - group else rules + (group to text.trim()))
    }

    private const val VERSION = 1

    /**
     * 读 `~/.yxi/groups.json`。
     *
     * ⚠️ **读不懂就当没有分组，绝不抛异常。** 这个文件是手机写的，但用户可能
     * 手改过、或者被别的版本写过。为了一个坏掉的分组表让整个看板打不开，
     * 是拿主功能给附加功能陪葬。
     */
    fun parse(raw: String): Table {
        val txt = raw.trim()
        if (txt.isEmpty()) return Table()
        return runCatching {
            val root = JSONObject(txt)
            val o = root.optJSONObject("groups") ?: return Table()
            val m = LinkedHashMap<String, List<String>>()
            o.keys().forEach { k ->
                val arr = o.optJSONArray(k) ?: JSONArray()
                m[k] = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            val r = LinkedHashMap<String, String>()
            root.optJSONObject("rules")?.let { ro -> ro.keys().forEach { k -> ro.optString(k).takeIf { it.isNotBlank() }?.let { r[k] = it } } }
            Table(m, r)
        }.getOrDefault(Table())
    }

    fun encode(t: Table): String =
        JSONObject().put("v", VERSION).put(
            "groups",
            JSONObject().also { o -> t.groups.forEach { (k, v) -> o.put(k, JSONArray(v)) } },
        ).put(
            "rules",
            JSONObject().also { o -> t.rules.forEach { (k, v) -> o.put(k, v) } },
        ).toString()

    /**
     * 写回服务器。
     *
     * ⚠️ **整段不可取消。** 半截写进去的 JSON 是坏的 —— [parse] 会当成「没有分组」，
     * 用户辛苦编的组静悄悄没了。（跟 [SessionProbe.send] 同样的理由。）
     * ⚠️ 走 `cat > 临时文件 && mv`，不直接覆盖：mv 在同一个文件系统上是原子的，
     * 半截文件永远不会被 agent 读到。
     */
    suspend fun save(session: SshSession, t: Table) = withContext(NonCancellable) {
        val json = encode(t).replace("'", "'\\''")
        session.exec(
            "mkdir -p \"\$HOME/.yxi\" && " +
                "printf '%s' '$json' > \"\$HOME/.yxi/groups.json.tmp\" && " +
                "mv \"\$HOME/.yxi/groups.json.tmp\" \"\$HOME/.yxi/groups.json\"",
        )
    }

    /**
     * 把「你被编进这个组了、同组有谁」告诉每个成员 —— 这是**「打通」发生的那一刻**。
     *
     * 不发这一句的话，分组对 agent 来说是不存在的：它不会主动去读 groups.json，
     * 也就不知道自己有队友、更不知道能 `yxi-hub say` 找他们。
     *
     * @return 真发到了几个
     */
    suspend fun announce(session: SshSession, t: Table, group: String): Int {
        val members = t.groups[group].orEmpty()
        if (members.size < 2) return 0
        var n = 0
        members.forEach { me ->
            val mates = members.filter { it != me }
            SessionProbe.send(
                session, me,
                "[Yxi 分组] 你被编进了「$group」组，同组还有：${mates.joinToString("、")}。" +
                    "给他们发消息用 `yxi-hub say <名字> \"内容\"`，" +
                    "`yxi-hub who` 看当前组员。只能发给同组的。",
            )
            n++
        }
        return n
    }
}

/** 组规改了，顺手发给组里正在跑的会话（下次会话开始也会自动注入，这是「现在就生效」那条路）。返回发了几个。 */
suspend fun tellRule(session: SshSession, t: Groups.Table, group: String): Int {
    val text = t.rules[group].orEmpty().trim()
    val members = t.groups[group].orEmpty()
    if (text.isEmpty() || members.isEmpty()) return 0
    members.forEach { SessionProbe.send(session, it, "[Yxi 组规更新 · $group] 从现在起本组的规矩：\n$text") }
    return members.size
}
