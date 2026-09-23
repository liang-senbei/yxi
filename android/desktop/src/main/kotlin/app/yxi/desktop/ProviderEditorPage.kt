package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ProviderEditorPage(onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Tokens.current.textPrimary) {
    Column(Modifier.fillMaxSize().background(Tokens.current.surface0)) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onDismissRequest) { Text("← 返回") }
            Box(Modifier.weight(1f).padding(start = 12.dp)) { title() }
            dismissButton(); confirmButton()
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 1100.dp).fillMaxWidth()) { text() }
        }
    }
    }
}
