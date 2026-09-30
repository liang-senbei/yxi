@file:Suppress("DEPRECATION")   // SecurityManager：同 ClassicThemeShotsTest，只拿来拦 exec / 外连

package app.yxi.desktop

import app.yxi.agent.Groups
import app.yxi.agent.Session
import app.yxi.agent.SessionState
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import java.io.File
import kotlin.test.*

/**
 * [hostTree]（经典 ProjectTree 和 Code 侧栏共用的筛选 / 分组）：命名分组和空组、服务器上已删掉的组回落「未分组」、
 * 组内按置顶排序、目录去重、折叠、搜索时跳组并展开、归档模式、分组没读到时平铺。只算数据，不画界面。
 *
 * AppState 读写的是夹具 profile（和截图用例同一个目录、同一个 Store 单例），所以只在 YXI_THEME_SHOT_MODE=tree 时跑。
 */
@EnabledIfEnvironmentVariables(
    EnabledIfEnvironmentVariable(named = "YXI_THEME_SHOT_FIXTURE", matches = ".+"),
    EnabledIfEnvironmentVariable(named = "YXI_THEME_SHOT_MODE", matches = "tree"),
)
class HostTreeTest {
    private fun session(name: String, cwd: String) =
        Session(name, 1, false, cwd, 0L, SessionState.Idle, "", 0.0, cmd = "claude", runtimeId = "rt-$name")

    @Test
    fun `host tree groups filters and orders tasks`() {
        val fixture = File(System.getenv("YXI_THEME_SHOT_FIXTURE")).also { require(it.isAbsolute) }.canonicalFile
        val profile = prepareShotProfile(fixture)
        val previous = System.getSecurityManager()
        val guard = NoExec()
        System.setSecurityManager(guard)
        try {
            assertTrue(Store.dir.canonicalFile.startsWith(profile), "Store.dir 没落在夹具 profile 里：${Store.dir}")
            verify(guard)
        } finally {
            System.setSecurityManager(previous)
        }
    }

    private fun verify(guard: NoExec) {
        val state = AppState()
        val nav = state.navigation
        val host = Host("tree-host", "示例服务器", "tree-01.example.invalid", username = "dev")
        val web = session("cc-web", "/srv/web"); val docs = session("cc-docs", "/srv/docs")
        val api = session("cc-api", "/srv/api"); val old = session("cc-old", "/srv/old")
        val sessions = listOf(web, docs, api, old)
        fun key(s: Session) = taskNavigationKey(host, s)
        nav.setArchived(key(old), true)
        nav.togglePin(key(docs)); nav.togglePin(key(web))   // docs 先置顶，排在 web 前面
        val conn = Conn(host, NoHostKeys).apply {
            forceState("projectGroups", Groups.Table(mapOf("前端" to listOf("cc-web", "cc-docs"), "空组" to emptyList<String>())))
            forceState("groupsLoaded", true)
        }
        val hostKey = projectKey(host, "/")
        val login = CodexTaskRecord(hostKey, "t-login", "/srv/web", "登录页", 2L)
        val stale = CodexTaskRecord(hostKey, "t-stale", "/srv/api", "接口文档", 1L)
        state.codexWorkspace.registry.forceState("records", listOf(stale, login))
        nav.setGroup(login.key, "前端"); nav.setGroup(stale.key, "已删掉的组")
        val lint = LocalCodexTaskRecord("t-lint", "dev", "linux", "/home/dev", "/srv/web", "修 lint", "m", 3L, engine = "opencode", hostKey = hostKey)
        state.remoteOpenCodeTasks.registry.records += lint
        nav.setGroup(lint.key, "前端")

        val all = hostTree(state, conn, sessions, searching = false, query = "")
        assertEquals(listOf(web, docs, api), all.sessions, "归档的会话不在「全部」里")
        assertEquals(listOf(key(docs), key(web)), all.pinKeys)
        assertEquals(listOf("前端", "空组", ""), all.groups.map { it.name }, "不搜索时空的命名分组照列，「未分组」为空才跳过")
        val front = all.groups[0]
        assertEquals(listOf(docs, web), front.sessions, "组内按置顶顺序")
        assertEquals(listOf(login), front.codex); assertEquals(listOf(lint), front.openCode)
        assertEquals(listOf("/srv/web", "/srv/docs"), front.directories, "目录按成员原顺序去重")
        assertEquals(4, front.count)
        assertEquals("server-group:$hostKey:前端", front.key); assertFalse(front.closed)
        val rest = all.groups[2]
        assertEquals(listOf(api), rest.sessions); assertEquals(listOf(stale), rest.codex, "服务器上已删掉的组回落「未分组」")

        nav.setCollapsed(front.key, true)
        assertTrue(hostTree(state, conn, sessions, searching = false, query = "").groups[0].closed)
        // 会话的搜索由调用方先筛好（侧栏只传匹配的会话进来）；托管任务在这里按标题 / 目录 / 分组等筛
        val found = hostTree(state, conn, listOf(web), searching = true, query = "登录")
        assertEquals(listOf("前端"), found.groups.map { it.name }, "搜索时跳过没有匹配项的分组")
        assertFalse(found.groups[0].closed, "搜索时分组一律展开")
        assertEquals(listOf(login), found.groups[0].codex); assertTrue(found.groups[0].openCode.isEmpty())

        nav.setMode("归档")
        val archived = hostTree(state, conn, sessions, searching = false, query = "")
        assertEquals(listOf(old), archived.sessions); assertTrue(archived.pinKeys.isEmpty(), "归档模式不列置顶")
        nav.setMode("全部")

        val flat = hostTree(state, Conn(host, NoHostKeys), sessions, searching = false, query = "")
        assertTrue(flat.groups.isEmpty(), "分组没读到时不分组")
        assertEquals(listOf(web, docs, api), flat.sessions); assertEquals(listOf(login, stale), flat.codex)
        assertEquals(emptyList<String>(), guard.drain(), "不该起进程或外连")
    }
}
