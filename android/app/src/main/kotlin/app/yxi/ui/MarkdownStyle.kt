package app.yxi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography

/**
 * 聊天里 markdown 的排版。**只为一件事存在：把标题压回正常大小。**
 *
 * ⚠️ 库的 M3 默认把标题映射到 `display*`：
 *
 * | markdown | 默认映射 | 字号 |
 * |---|---|---|
 * | `#`   | displayLarge  | **57sp** |
 * | `##`  | displayMedium | **45sp** |
 * | `###` | displaySmall  | 36sp |
 * | 正文  | bodyLarge     | 16sp |
 *
 * 那套字号是给**落地页大标题**用的，一屏就放一个词。聊天气泡里一个 `##`
 * 就占掉半屏，正文反而像注脚 —— 用户的原话是「这几个字为什么要特别大」。
 *
 * 手机上标题的职责只是**分段**，比正文大一点、粗一点就够了。
 * 这里 h1 20sp / h2 18sp，正文 16sp —— 层级看得出来，但不喧宾夺主。
 */
@Composable
fun yxiMarkdown(): MarkdownTypography {
    val t = MaterialTheme.typography
    return markdownTypography(
        h1 = t.bodyLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        h2 = t.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        h3 = t.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        // h4 以下在聊天里基本用不到，统一成「粗一点的正文」，别再往下缩
        h4 = t.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        h5 = t.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        h6 = t.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}
