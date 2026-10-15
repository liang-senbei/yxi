package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 右侧面板的淡阴影（规格表 §1.6：比浮层的短、比输入卡的深；没拟合成 box-shadow，取网页版 --cds-panel-shadow 的量级）。 */
internal val PanelShadow = listOf(
    ShadowLayer(1.dp, 2.dp, Color.Black.copy(alpha = 0.05f)),
    ShadowLayer(4.dp, 12.dp, Color.Black.copy(alpha = 0.04f)),
)

/** 右侧面板的宽度（M4a，规格表 §3.12，含外环）：改动 / 文件 / 预览 1000–2040，终端从 1461.6 起。 */
internal object CodePanelWidth {
    const val WIDE = 1040f
    const val TERMINAL = 578.4f
    /** 对话那一列至少留这么宽，不够就让面板铺满主区（同经典分栏的「窄时只看面板」）。 */
    const val MIN_MAIN = 360f
}

/**
 * 右侧面板的卡片（M4a，规格表 §3.12）：面板底（浅色白、深色浮层色）、外环 + 淡阴影、圆角 10；
 * 头部高 32、下方没有分隔线：标题次色 14 起于 +12.8，右端关闭按钮（同会话头的图标按钮）。
 * 参照端还有「弹出」「最大化」，Yxi 没有独立窗口，不放（PRD §14.1）。面板正文由调用方给，裁在圆角里。
 */
@Composable
internal fun CodePanelCard(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = Tokens.current
    val p = CodePalette.current
    val shape = RoundedCornerShape(10.dp)
    val ring = hairline()
    Column(modifier.codeShadow(10.dp, PanelShadow).background(p.popup, shape).border(ring, p.popupRing, shape).padding(ring).clip(shape)) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(start = 12.8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = t.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CodeIconButton(Icons.Filled.Close, "关闭$title", onClick = onClose)
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}
