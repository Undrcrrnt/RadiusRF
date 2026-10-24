package app.radiusrf.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Active palette. Screens keep calling [ZdColors]; the values follow [AppThemes.selected].
 * RadiusRF is the default until Settings picks another theme.
 */
object ZdColors {
    private val p: AppPalette get() = AppThemes.selected.value

    val Bg get() = p.bg
    val Surface get() = p.surface
    val Surface2 get() = p.surface2
    val Surface3 get() = p.surface3
    val Border get() = p.border
    val BorderStrong get() = p.borderStrong
    val Scrim get() = p.scrim

    val Text get() = p.text
    val Text2 get() = p.text2
    val Text3 get() = p.text3

    val Accent get() = p.accent
    val AccentHover get() = p.accentHover
    val OnAccent get() = p.onAccent
    val AccentBg get() = p.accentBg
    val AccentBorder get() = p.accentBorder

    val Critical get() = p.critical
    val CriticalBg get() = p.criticalBg
    val CriticalBorder get() = p.criticalBorder
    val OnCritical get() = p.onCritical
    val High get() = p.high
    val HighBg get() = p.highBg
    val Medium get() = p.medium
    val MediumBg get() = p.mediumBg
    val MediumBorder get() = p.mediumBorder
    val Info get() = p.info
    val InfoBg get() = p.infoBg
    val InfoBorder get() = p.infoBorder
    val Violet get() = p.violet
}

val TerminalGreen get() = ZdColors.Accent
val TerminalGreenDim get() = ZdColors.AccentHover
val TerminalGreenDark get() = ZdColors.AccentBg
val TerminalGreenGlow get() = ZdColors.Accent.copy(alpha = 0.25f)

val TerminalAmber get() = ZdColors.Medium
val TerminalAmberGlow get() = ZdColors.Medium.copy(alpha = 0.25f)
val TerminalRed get() = ZdColors.Critical
val TerminalRedGlow get() = ZdColors.Critical.copy(alpha = 0.25f)
val TerminalCyan get() = ZdColors.Info
val TerminalCyanGlow get() = ZdColors.Info.copy(alpha = 0.25f)
val TerminalBlue get() = ZdColors.Info

val SeverityCritical get() = ZdColors.Critical
val SeverityHigh get() = ZdColors.High
val SeverityMedium get() = ZdColors.Medium
val SeverityLow get() = ZdColors.Info
val SeverityInfo get() = ZdColors.Accent

val BackgroundDark get() = ZdColors.Bg
val SurfaceDark get() = ZdColors.Surface
val SurfaceVariantDark get() = ZdColors.Surface2
val SurfaceElevated get() = ZdColors.Surface3
val CardBorderGreen get() = ZdColors.Border
val CardBorderGlow get() = ZdColors.Accent

val TextPrimary get() = ZdColors.Text
val TextSecondary get() = ZdColors.Text2
val TextGreen get() = ZdColors.Accent
val TextDim get() = ZdColors.Text3

val GradientGreenStart get() = ZdColors.Accent
val GradientGreenEnd get() = ZdColors.Info
val GradientAmberStart get() = ZdColors.Medium
val GradientAmberEnd get() = ZdColors.High
