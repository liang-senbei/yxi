package app.yxi.desktop

import androidx.compose.runtime.Composable
import app.yxi.agent.Session
import app.yxi.agent.SessionState

/**
 * 一台主机在侧栏里的内容：按导航模式（全部 / 待处理 / 归档）和搜索词筛过，再按项目分组。
 * 经典的 [ProjectTree] 和 Code 侧栏都从这里取，筛选、分组和排序只写一次（PRD §4.2）。
 * [sessions] / [codex] / [openCode] / [acp] 是筛过、未排序的全集（分组没读到时直接平铺它们）；
 * [groups] 在分组读到前（`conn.groupsLoaded == false`）是空的。
 */
internal class HostTree(
    val sessions: List<Session>,
    val codex: List<CodexTaskRecord>,
    val openCode: List<LocalCodexTaskRecord>,
    val acp: List<LocalCodexTaskRecord>,
    /** 当前置顶顺序（归档模式下为空），给会话菜单的「置顶上移 / 下移」。 */
    val pinKeys: List<String>,
    val groups: List<HostTreeGroup>,
)

/** 一个项目分组；[name] 为空是「未分组」。各列表已按置顶顺序排好；[directories] 按成员原顺序去重，首个是组内新建的默认目录。 */
internal class HostTreeGroup(
    val name: String,
    /** 折叠状态在导航里的键。 */
    val key: String,
    /** 搜索时一律展开。 */
    val closed: Boolean,
    val sessions: List<Session>,
    val codex: List<CodexTaskRecord>,
    val openCode: List<LocalCodexTaskRecord>,
    val acp: List<LocalCodexTaskRecord>,
    val directories: List<String>,
) {
    val count get() = sessions.size + codex.size + openCode.size + acp.size
}

/**
 * 项目树里的一行（服务器会话 / Codex / OpenCode / ACP）交给外观去画时的数据。选中、点击和菜单条目都由 [ProjectTree] 算好，
 * 两种风格共用同一份行为（PRD §4.2）；[menu] 在菜单打开时才调，条目同经典的 ⋯ 菜单。[runner] 是运行器名（Claude Code / Codex …）。
 */
internal class TreeRow(
    val key: String,
    val title: String,
    val runner: String,
    val state: SessionState,
    val selected: Boolean,
    val menu: () -> List<MenuEntry>,
    val onClick: () -> Unit,
)

/**
 * [ProjectTree] 的外观槽：不传（null）时画经典样式，原样不动；Code 侧栏传自己的一套。
 * [note] 是说明 / 出错行（warning 为真用警示色）；[group] 是分组标题（名字为空是「未分组」；onCreate 是组内新建，
 * 仍弹经典的新建对话框，它能带上分组；menu 同经典分组菜单）；[row] 画一行。
 */
internal class TreeLook(
    val note: @Composable (text: String, warning: Boolean) -> Unit,
    val group: @Composable (name: String, closed: Boolean, onToggle: () -> Unit, onCreate: () -> Unit, menu: () -> List<MenuEntry>) -> Unit,
    val row: @Composable (TreeRow) -> Unit,
)

/** Yxi 托管的 Codex 任务的状态：有待审批 = 等你，有进行中的回合 = 干活中，否则闲置。 */
internal fun codexTaskState(state: AppState, record: CodexTaskRecord): SessionState {
    val controller = state.codexWorkspace.controllers[record.key]
    return if (controller?.pendingRequests?.isNotEmpty() == true) SessionState.NeedsYou
        else if (controller?.activeTurnId != null) SessionState.Working else SessionState.Idle
}

/**
 * [sessions] 是这台主机的服务器会话（tmux）；搜索词为空时匹配一切。
 * [searching] 为真时跳过没有匹配项的分组、分组一律展开；「未分组」没有成员时总是跳过。
 */
internal fun hostTree(state: AppState, conn: Conn, sessions: List<Session>, searching: Boolean, query: String): HostTree {
    val nav = state.navigation
    fun matches(vararg fields: String) = fields.any { it.contains(query, true) }
    val acp = state.remoteAcpTasks.tasks(conn).filter { record ->
        nav.visible(record.key, acpTaskState(state, record)) &&
            matches(nav.title(record.key).orEmpty(), record.title, record.directory, conn.host.label, conn.host.region, nav.group(record.key), LocalRuntimeDiscovery.title(record.engine))
    }
    val openCode = state.remoteOpenCodeTasks.tasks(conn.host).filter { record ->
        nav.visible(record.key, openCodeTaskState(state, record)) &&
            matches(nav.title(record.key).orEmpty(), record.title, record.directory, conn.host.label, nav.group(record.key))
    }
    val codex = state.codexWorkspace.tasks(conn.host).filter { record ->
        nav.visible(record.key, codexTaskState(state, record)) &&
            matches(nav.title(record.key).orEmpty(), record.title, record.directory, conn.host.label, conn.host.region, nav.group(record.key))
    }
    val visible = sessions.filter { nav.visible(taskNavigationKey(conn.host, it), it.state) }
    val pinKeys = if (nav.mode == "归档") emptyList()
        else visible.map { taskNavigationKey(conn.host, it) }.filter { nav.pinned(it) }.sortedBy { nav.pinOrder(it) }
    if (!conn.groupsLoaded) return HostTree(visible, codex, openCode, acp, pinKeys, emptyList())
    val declared = conn.projectGroups.groups
    val grouped = declared.values.flatten().toSet()
    // 托管任务记在导航里的组已不在服务器上时，算「未分组」。
    fun groupOf(key: String) = nav.group(key).takeIf { it in declared.keys }.orEmpty()
    val rows = declared.entries.map { it.key to visible.filter { s -> s.name in it.value } } + listOf("" to visible.filter { it.name !in grouped })
    val groups = rows.mapNotNull { (name, members) ->
        val codexMembers = codex.filter { groupOf(it.key) == name }
        val openCodeMembers = openCode.filter { groupOf(it.key) == name }
        val acpMembers = acp.filter { groupOf(it.key) == name }
        if ((searching || name.isEmpty()) && members.isEmpty() && codexMembers.isEmpty() && openCodeMembers.isEmpty() && acpMembers.isEmpty()) return@mapNotNull null
        val key = "server-group:" + projectKey(conn.host, "/") + ":" + name
        HostTreeGroup(name, key, closed = !searching && nav.collapsed(key, false),
            sessions = members.sortedBy { s -> val k = taskNavigationKey(conn.host, s); if (nav.pinned(k)) nav.pinOrder(k) else Int.MAX_VALUE },
            codex = codexMembers.sortedBy { nav.pinOrder(it.key) },
            openCode = openCodeMembers.sortedBy { nav.pinOrder(it.key) },
            acp = acpMembers.sortedBy { nav.pinOrder(it.key) },
            directories = (members.map { it.cwd } + codexMembers.map { it.directory } + openCodeMembers.map { it.directory } + acpMembers.map { it.directory })
                .filter { it.isNotBlank() }.distinct())
    }
    return HostTree(visible, codex, openCode, acp, pinKeys, groups)
}
