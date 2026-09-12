package app.yxi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Swing/native browser surfaces otherwise paint above Compose dialogs and menus. */
class NativeOverlayRegistry {
    private val leases = mutableStateMapOf<Any, Unit>()
    val active get() = leases.isNotEmpty()
    fun enter(token: Any) { leases[token] = Unit }
    fun leave(token: Any) { leases.remove(token) }
}
val NativeOverlays = NativeOverlayRegistry()

@Composable
fun NativeOverlay(active: Boolean = true) {
    val token = remember { Any() }
    DisposableEffect(active) {
        if (active) NativeOverlays.enter(token)
        onDispose { NativeOverlays.leave(token) }
    }
}

@Composable
fun WorkbenchDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    NativeOverlay()
    androidx.compose.material3.AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text)
}
