package app.yxi.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yxi.agent.Session
import app.yxi.agent.SessionState

/**
 * 侧栏各行 ⋯ 菜单的条目。经典（[ClassicMenuEntries]，Material 下拉）和 Code（CodePopup）按同一份列表画，
 * 条目、文案和可用条件只在这里写一次（PRD §4.2：行为不按风格分叉，Code 不少入口）。
 */
internal sealed interface MenuEntry {
    /** 点了先关菜单再执行 [action]。[path]：文案是目录路径，Code 下不折行、放不下时从前面省略，留住末段（经典不看这个标记）。 */
    class Item(val label: String, val enabled: Boolean = true, val path: Boolean = false, val action: () -> Unit) : MenuEntry
    /** 段落小标题（「移到项目分组」）。 */
    class Header(val label: String) : MenuEntry
    /** 菜单顶上的标题（「主机 · 分组」）。 */
    class Title(val label: String) : MenuEntry
    data object Divider : MenuEntry
}

/** 经典样式：DropdownMenuItem / HorizontalDivider；小标题 labelMedium、padding 12，标题 titleSmall、padding(16, 8)。 */
@Composable
internal fun ClassicMenuEntries(entries: List<MenuEntry>, close: () -> Unit) {
    entries.forEach { e ->
        when (e) {
            is MenuEntry.Item -> DropdownMenuItem(text = { Text(e.label) }, enabled = e.enabled, onClick = { close(); e.action() })
            is MenuEntry.Header -> Text(e.label, Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
            is MenuEntry.Title -> Text(e.label, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
            MenuEntry.Divider -> HorizontalDivider()
        }
    }
}

/** 主机分组头的菜单（经典 HostHeader 的 ⋯ 和右键都是它）。 */
internal fun hostMenu(h: Host, c: Conn?, actions: HostActions): List<MenuEntry> {
    val st = c?.status ?: Conn.Status.Idle
    return listOf(
        if (c == null || st == Conn.Status.Failed) MenuEntry.Item("连接") { actions.connect(h) } else MenuEntry.Item("断开") { actions.disconnect(h) },
        MenuEntry.Item("新建会话", st == Conn.Status.Connected) { actions.creatingOn = c },
        MenuEntry.Item("装公钥免密…") { actions.installingKey = h },
        MenuEntry.Item("编辑") { actions.editing = h },
        MenuEntry.Item("连接颜色…") { actions.coloring = h },
        MenuEntry.Item("删除") { actions.deleting = h },
    )
}

/** 服务器会话（tmux）的菜单。[stable] = 有 runtimeId；没有时整理类的项都不可用，顶上多一行说明。[pinKeys] 是当前置顶顺序。 */
internal fun sessionMenu(nav: WorkspaceNavigation, conn: Conn, s: Session, key: String, stable: Boolean, pinKeys: List<String>,
    onConfigure: () -> Unit, onRename: () -> Unit): List<MenuEntry> = buildList {
    if (!stable) add(MenuEntry.Item("会话标识不可用，请刷新后整理", false) {})
    add(MenuEntry.Item("配置", stable, action = onConfigure))
    add(MenuEntry.Item(if (nav.pinned(key)) "取消置顶" else "置顶", stable) { nav.togglePin(key) })
    add(MenuEntry.Item(if (nav.muted(key)) "恢复任务通知" else "静音此任务", stable) { nav.setMuted(key, !nav.muted(key)) })
    add(MenuEntry.Item(if (nav.favorite(key)) "取消收藏启动入口" else "收藏为启动入口", stable && s.cwd.startsWith('/')) { nav.setFavorite(key, conn.host, s, !nav.favorite(key)) })
    if (nav.pinned(key)) {
        add(MenuEntry.Item("置顶上移", pinKeys.indexOf(key) > 0) { nav.movePin(key, -1, pinKeys) })
        add(MenuEntry.Item("置顶下移", pinKeys.indexOf(key) in 0 until pinKeys.lastIndex) { nav.movePin(key, 1, pinKeys) })
    }
    add(MenuEntry.Item("修改显示名称", stable, action = onRename))
    val canArchive = s.state != SessionState.Working && s.state != SessionState.NeedsYou
    add(MenuEntry.Item(if (nav.archived(key)) "恢复到项目列表" else if (canArchive) "归档" else "运行中或待处理任务不能归档", stable && (nav.archived(key) || canArchive)) {
        nav.setArchived(key, !nav.archived(key))
    })
    add(MenuEntry.Item("复制项目路径") { copyText(s.cwd) })
}

/** Yxi 托管的 Codex 任务的菜单：运行中或待审批时不能归档。 */
internal fun codexMenu(state: AppState, conn: Conn, record: CodexTaskRecord, onConfigure: () -> Unit, onRename: () -> Unit): List<MenuEntry> {
    val nav = state.navigation
    val controller = state.codexWorkspace.controllers[record.key]
    return listOf(
        MenuEntry.Item("配置", action = onConfigure),
        MenuEntry.Item(if (nav.pinned(record.key)) "取消置顶" else "置顶") { nav.togglePin(record.key) },
        MenuEntry.Item(if (nav.favorite(record.key)) "取消收藏" else "收藏") { nav.setCodexFavorite(record, !nav.favorite(record.key)) },
        MenuEntry.Item("修改显示名称", action = onRename),
        MenuEntry.Item(if (nav.muted(record.key)) "恢复通知" else "静音") { nav.setMuted(record.key, !nav.muted(record.key)) },
        MenuEntry.Item(if (nav.archived(record.key)) "恢复到项目列表" else "归档", controller?.activeTurnId == null && controller?.pendingRequests.isNullOrEmpty()) {
            nav.setArchived(record.key, !nav.archived(record.key))
        },
    ) + moveToGroup(nav, conn, record.key)
}

/** 服务器上的 OpenCode / ACP 任务的菜单：只有空闲（[status]）时能归档。 */
internal fun nativeTaskMenu(nav: WorkspaceNavigation, conn: Conn, task: LocalCodexTaskRecord, status: SessionState, onRename: () -> Unit): List<MenuEntry> = listOf(
    MenuEntry.Item(if (nav.pinned(task.key)) "取消置顶" else "置顶") { nav.togglePin(task.key) },
    MenuEntry.Item(if (nav.favorite(task.key)) "取消收藏" else "收藏") { nav.setNativeFavorite(task.key, !nav.favorite(task.key)) },
    MenuEntry.Item("修改显示名称", action = onRename),
    MenuEntry.Item(if (nav.muted(task.key)) "恢复任务通知" else "静音此任务") { nav.setMuted(task.key, !nav.muted(task.key)) },
    MenuEntry.Item(if (nav.archived(task.key)) "恢复到项目列表" else "归档", status == SessionState.Idle) { nav.setArchived(task.key, !nav.archived(task.key)) },
) + moveToGroup(nav, conn, task.key)

/** 项目分组头的菜单：标题「主机 · 分组」，点目录复制路径，末尾「编辑分组与组规」。 */
internal fun projectGroupMenu(conn: Conn, name: String, directories: List<String>, onEdit: () -> Unit): List<MenuEntry> =
    listOf(MenuEntry.Title(conn.host.label + " · " + name.ifEmpty { "未分组" })) +
        directories.map { dir -> MenuEntry.Item(dir, path = true) { copyText(dir) } } +
        MenuEntry.Item("编辑分组与组规", action = onEdit)

/** 分隔线 +「移到项目分组」+ 各组（末尾「未分组」）。 */
private fun moveToGroup(nav: WorkspaceNavigation, conn: Conn, key: String): List<MenuEntry> =
    listOf(MenuEntry.Divider, MenuEntry.Header("移到项目分组")) +
        (conn.projectGroups.groups.keys.toList() + "").map { group -> MenuEntry.Item(group.ifBlank { "未分组" }) { nav.setGroup(key, group) } }

private fun copyText(text: String) {
    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
}
