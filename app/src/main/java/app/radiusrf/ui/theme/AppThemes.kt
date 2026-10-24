package app.radiusrf.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

enum class ThemeOrigin(val label: String) {
    RadiusRF("RadiusRF"),
    ZeroDroid("ZeroDroid"),
    NetSeer("NetSeer")
}

data class AppPalette(
    val id: String,
    val name: String,
    val note: String,
    val origin: ThemeOrigin,
    val light: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val scrim: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    val accentBg: Color,
    val accentBorder: Color,
    val critical: Color,
    val criticalBg: Color,
    val criticalBorder: Color,
    val onCritical: Color,
    val high: Color,
    val highBg: Color,
    val medium: Color,
    val mediumBg: Color,
    val mediumBorder: Color,
    val info: Color,
    val infoBg: Color,
    val infoBorder: Color,
    val violet: Color
)

/**
 * RadiusRF stays the default. The other entries are ZeroDroid's earlier terminal
 * palette and every NetSeer theme, including the three that NetSeer hides until found.
 */
object AppThemes {
    const val KEY = "theme_id"

    val radiusRf = literal(
        id = "radiusrf",
        name = "RadiusRF",
        note = "Default",
        origin = ThemeOrigin.RadiusRF,
        bg = 0xFF0B0D0C, surface = 0xFF121614, surface2 = 0xFF182019, surface3 = 0xFF1E2822,
        border = 0xFF25302A, borderStrong = 0xFF33413A,
        text = 0xFFE6ECE8, text2 = 0xFFA3B0A8, text3 = 0xFF7C8982,
        accent = 0xFF52E08A, accentHover = 0xFF8AF0B2, onAccent = 0xFF06120B,
        accentBg = 0xFF13301F, accentBorder = 0xFF1F4A30,
        critical = 0xFFFF6B61, criticalBg = 0xFF3D1714, criticalBorder = 0xFF5A2420, onCritical = 0xFF1A0604,
        high = 0xFFFF8A4C, highBg = 0xFF3A1F10,
        medium = 0xFFF2B53C, mediumBg = 0xFF3A2C10, mediumBorder = 0xFF5A4418,
        info = 0xFF5CC8FF, infoBg = 0xFF0F2C3B, infoBorder = 0xFF1B4458,
        violet = 0xFFC9A0FF
    )

    /** ZeroDroid before the refined palette (v1.2.1). */
    val zeroDroidTerminal = literal(
        id = "zerodroid-terminal",
        name = "Terminal",
        note = "ZeroDroid classic",
        origin = ThemeOrigin.ZeroDroid,
        bg = 0xFF0A0A0A, surface = 0xFF121212, surface2 = 0xFF1A1A1A, surface3 = 0xFF1E1E1E,
        border = 0xFF1B5E20, borderStrong = 0xFF00CC33,
        text = 0xFFE0E0E0, text2 = 0xFF9E9E9E, text3 = 0xFF616161,
        accent = 0xFF00FF41, accentHover = 0xFF00CC33, onAccent = 0xFF003B00,
        accentBg = 0xFF003B00, accentBorder = 0xFF1B5E20,
        critical = 0xFFFF1744, criticalBg = 0xFF3D0A14, criticalBorder = 0xFF5A1020, onCritical = 0xFF1A0604,
        high = 0xFFFF6D00, highBg = 0xFF3A1A08,
        medium = 0xFFFFB300, mediumBg = 0xFF3A2A08, mediumBorder = 0xFF5A4010,
        info = 0xFF00E5FF, infoBg = 0xFF083038, infoBorder = 0xFF104850,
        violet = 0xFF2979FF
    )

    val all: List<AppPalette> = listOf(
        radiusRf,
        zeroDroidTerminal,
        net("terrain", "Terrain", "Warm topographic", 0xFF15140F, 0xFF1C1A14, 0xFF25221A, 0xFF2D2A20, 0xFF36312A, 0xFF4A4436, 0xFFEFE8D8, 0xFFA89F88, 0xFF7B735F, 0xFFE0A84A, 0xFFEAB866, 0xFF2A1D05, 0xFFE57A6B, 0xFF7FB6C9, 0xFFE0A84A, 0xFFD98A6A, 0xFFB8A0C8),
        net("midnight", "Midnight", "Low-glare navy", 0xFF0B1220, 0xFF101A2B, 0xFF162238, 0xFF1C2A43, 0xFF22324C, 0xFF31476A, 0xFFE6EDF7, 0xFF8FA1BD, 0xFF66779A, 0xFF2DD4BF, 0xFF5EEAD4, 0xFF042420, 0xFFFB7185, 0xFF38BDF8, 0xFFFBBF24, 0xFFF472B6, 0xFFC4B5FD),
        net("daylight", "Daylight", "Light, for printing", 0xFFF4F5F7, 0xFFFFFFFF, 0xFFF7F8FA, 0xFFEEF1F5, 0xFFE1E5EB, 0xFFC9D0DA, 0xFF18212F, 0xFF5B6676, 0xFF8A94A3, 0xFF2563EB, 0xFF1D4FD8, 0xFFFFFFFF, 0xFFDC2626, 0xFF2563EB, 0xFFD97706, 0xFFDB2777, 0xFF7C3AED, light = true),
        net("blueprint", "Blueprint", "Engineering grid", 0xFF0D2644, 0xFF102D50, 0xFF14365F, 0xFF19406F, 0xFF24507F, 0xFF3567A0, 0xFFEAF2FF, 0xFFA3BDDF, 0xFF7896BD, 0xFFFFFFFF, 0xFFDFEAFF, 0xFF0D2644, 0xFFFF9B8F, 0xFF8FD3FF, 0xFFFFD479, 0xFFFFA3C2, 0xFFC6B8FF),
        net("retrowave", "Retrowave", "Soft synthwave", 0xFF1D1830, 0xFF241D3B, 0xFF2C2447, 0xFF352C54, 0xFF3D3360, 0xFF534578, 0xFFECE6F5, 0xFFB3A6CF, 0xFF8678A3, 0xFFE089C4, 0xFFEC9FD3, 0xFF2A0F24, 0xFFF08A8A, 0xFF8FB8FF, 0xFFF0C879, 0xFFEF9AC4, 0xFFB89AE0),
        net("matrix", "Matrix", "NetSeer secret", 0xFF000000, 0xFF020A04, 0xFF06140A, 0xFF0B2112, 0xFF0F2E18, 0xFF1B4A28, 0xFFB8FFCC, 0xFF4FD17A, 0xFF2F8A4C, 0xFF00FF41, 0xFF5CFF85, 0xFF001A07, 0xFFFF5F56, 0xFF7DFFB0, 0xFFC8FF00, 0xFF00E5A0, 0xFF4FFFD2),
        net("neon", "Neon", "NetSeer secret", 0xFF0D0221, 0xFF150530, 0xFF1F0A42, 0xFF2A1056, 0xFF351769, 0xFF4D2591, 0xFFF6EAFF, 0xFFB89BE0, 0xFF8467AD, 0xFFFF2FD6, 0xFFFF66E2, 0xFF1A0030, 0xFFFF5D73, 0xFF00F0FF, 0xFFFFE14D, 0xFF5CFFB5, 0xFF8F7BFF),
        net("darkside", "Dark Side", "NetSeer secret", 0xFF05060A, 0xFF0A0C13, 0xFF11141E, 0xFF181C29, 0xFF1D2231, 0xFF2C3347, 0xFFE6E9F2, 0xFF8A93A8, 0xFF5B6378, 0xFFFF2A2A, 0xFFFF5252, 0xFFFFFFFF, 0xFFFF6B4A, 0xFF3FA9FF, 0xFFFFD23F, 0xFF9FB4D8, 0xFFB06CFF)
    )

    val selected = mutableStateOf(radiusRf)

    fun load(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, radiusRf.id)
        selected.value = byId(id)
    }

    fun select(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id).apply()
        selected.value = byId(id)
    }

    fun byId(id: String?): AppPalette = all.firstOrNull { it.id == id } ?: radiusRf

    private const val PREFS = "RadiusRF_settings"
}

private fun literal(
    id: String,
    name: String,
    note: String,
    origin: ThemeOrigin,
    bg: Long, surface: Long, surface2: Long, surface3: Long,
    border: Long, borderStrong: Long,
    text: Long, text2: Long, text3: Long,
    accent: Long, accentHover: Long, onAccent: Long,
    accentBg: Long, accentBorder: Long,
    critical: Long, criticalBg: Long, criticalBorder: Long, onCritical: Long,
    high: Long, highBg: Long,
    medium: Long, mediumBg: Long, mediumBorder: Long,
    info: Long, infoBg: Long, infoBorder: Long,
    violet: Long,
    light: Boolean = false
) = AppPalette(
    id, name, note, origin, light,
    c(bg), c(surface), c(surface2), c(surface3),
    c(border), c(borderStrong), if (light) Color(0x6618212F) else Color(0x99000000),
    c(text), c(text2), c(text3),
    c(accent), c(accentHover), c(onAccent), c(accentBg), c(accentBorder),
    c(critical), c(criticalBg), c(criticalBorder), c(onCritical),
    c(high), c(highBg),
    c(medium), c(mediumBg), c(mediumBorder),
    c(info), c(infoBg), c(infoBorder),
    c(violet)
)

private fun net(
    id: String,
    name: String,
    note: String,
    bg: Long, panel: Long, raised: Long, hover: Long,
    line: Long, lineStrong: Long,
    text: Long, muted: Long, faint: Long,
    accent: Long, accentHover: Long, onAccent: Long,
    danger: Long, info: Long, medium: Long, high: Long, violet: Long,
    light: Boolean = false
): AppPalette {
    val bgC = c(bg)
    val accentC = c(accent)
    val dangerC = c(danger)
    val infoC = c(info)
    val mediumC = c(medium)
    val highC = c(high)
    return AppPalette(
        id, name, note, ThemeOrigin.NetSeer, light,
        bgC, c(panel), c(raised), c(hover),
        c(line), c(lineStrong), if (light) Color(0x6618212F) else Color(0x99000000),
        c(text), c(muted), c(faint),
        accentC, c(accentHover), c(onAccent), mix(bgC, accentC, 0.22f), mix(bgC, accentC, 0.45f),
        dangerC, mix(bgC, dangerC, 0.24f), mix(bgC, dangerC, 0.42f), if (light) Color.White else Color(0xFF1A0604),
        highC, mix(bgC, highC, 0.24f),
        mediumC, mix(bgC, mediumC, 0.24f), mix(bgC, mediumC, 0.42f),
        infoC, mix(bgC, infoC, 0.22f), mix(bgC, infoC, 0.42f),
        c(violet)
    )
}

private fun c(hex: Long) = Color(hex)

private fun mix(base: Color, tint: Color, amount: Float) = Color(
    red = base.red + (tint.red - base.red) * amount,
    green = base.green + (tint.green - base.green) * amount,
    blue = base.blue + (tint.blue - base.blue) * amount,
    alpha = 1f
)
