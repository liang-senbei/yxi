package app.yxi.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/** Official publisher assets. Provenance and hashes are packaged in brands/SOURCES.md. */
@Composable
internal fun RunnerBrandIcon(engine: String, modifier: Modifier = Modifier) {
    val asset = when (engine) {
        "claude" -> "claude-code.png"
        "codex" -> "codex-openai.svg"
        "opencode" -> "opencode.png"
        "gemini" -> "gemini.png"
        "grok" -> "grok-xai.svg"
        "hermes" -> "hermes.png"
        else -> { Spacer(modifier); return }
    }
    Image(painterResource("app/yxi/desktop/brands/$asset"), contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Fit,
        colorFilter = if (engine == "codex") ColorFilter.tint(Tokens.current.textPrimary) else null)
}
