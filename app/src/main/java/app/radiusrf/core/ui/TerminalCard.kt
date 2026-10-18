package app.radiusrf.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.radiusrf.core.ui.zd.ZdCardShape
import app.radiusrf.ui.theme.CardBorderGreen
import app.radiusrf.ui.theme.TerminalGreen
import app.radiusrf.ui.theme.TerminalGreenGlow
import app.radiusrf.ui.theme.ZdColors

/**
 * Legacy card API kept for screens not yet moved to [app.radiusrf.core.ui.zd.ZdCard].
 * Draws the redesign's flat card; a custom [borderColor] becomes a muted tinted hairline, and
 * [animated] (formerly a glow) marks the card as active with a stronger accent border.
 */
@Composable
fun TerminalCard(
    modifier: Modifier = Modifier,
    glowColor: Color = TerminalGreen,
    @Suppress("UNUSED_PARAMETER") glowAlpha: Color = TerminalGreenGlow,
    borderColor: Color = CardBorderGreen,
    animated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = when {
        animated -> glowColor.copy(alpha = 0.6f)
        borderColor != CardBorderGreen -> borderColor.copy(alpha = borderColor.alpha * 0.45f)
        else -> ZdColors.Border
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZdCardShape)
            .background(ZdColors.Surface)
            .border(1.dp, border, ZdCardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content
    )
}
