package app.yxi.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 左栏：主机分组 → cc-* 会话行（占位实现：先把原来的两个面板叠在一起；真正的合并版由侧栏代理实现）。
 * 契约：宽度由外面定（288dp），这里只管内容；`state.newSessionRequest` 变了要弹「新建会话」。
 */
@Composable
fun Sidebar(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier) {
        HostsPane(state)
        state.conn?.let { SessionsPane(state, it) }
    }
}
