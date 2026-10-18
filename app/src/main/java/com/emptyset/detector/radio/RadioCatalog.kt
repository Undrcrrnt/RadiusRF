package com.emptyset.detector.radio

import android.content.Context
import android.hardware.usb.UsbDevice

data class RadioOption(
    val id: String,
    val kind: RadioKind,
    val title: String,
    val detail: String,
    val backendReady: Boolean
)

object RadioCatalog {
    const val T2U_PLUS = "t2u_plus"
    const val PAU0A = "pau0a"
    const val PAU0B = "pau0b"
    const val RTL8812AU = "rtl8812au"
    const val RTL8814AU = "rtl8814au"
    const val MT7612U = "mt7612u"

    val options: List<RadioOption> = listOf(
        RadioOption(
            id = T2U_PLUS,
            kind = RadioKind.T2U_PLUS,
            title = "RTL8821AU",
            detail = "Archer T2U Plus or 0bda:0811  ·  receive-only",
            backendReady = true
        ),
        RadioOption(
            id = RTL8812AU,
            kind = RadioKind.RTL8812AU,
            title = "RTL8812AU",
            detail = "0bda:8812 / 881a  ·  receive-only",
            backendReady = true
        ),
        RadioOption(
            id = RTL8814AU,
            kind = RadioKind.RTL8814AU,
            title = "RTL8814AU",
            detail = "0bda:8813  ·  receive-only",
            backendReady = true
        ),
        RadioOption(
            id = PAU0A,
            kind = RadioKind.PAU0A,
            title = "Panda PAU0A",
            detail = "MT7610U  ·  receive-only  ·  0e8d:7610",
            backendReady = true
        ),
        RadioOption(
            id = MT7612U,
            kind = RadioKind.MT7612U,
            title = "MT7612U",
            detail = "0e8d:7612 and the MT7662 OEM ids  ·  receive-only",
            backendReady = true
        )
    )

    fun option(id: String): RadioOption =
        options.firstOrNull { it.id == id } ?: options.first()

    fun optionFor(kind: RadioKind): RadioOption? =
        options.firstOrNull { it.kind == kind }
}

class RadioSelection(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)

    var selectedId: String
        get() = prefs.getString(KEY_ID, RadioCatalog.T2U_PLUS) ?: RadioCatalog.T2U_PLUS
        set(value) {
            prefs.edit().putString(KEY_ID, value).apply()
        }

    val selected: RadioOption
        get() = RadioCatalog.option(selectedId)

    var band24: Boolean
        get() = prefs.getBoolean(KEY_24, true)
        set(value) = setBand(KEY_24, value)

    var band5: Boolean
        get() = prefs.getBoolean(KEY_5, true)
        set(value) = setBand(KEY_5, value)

    var band6: Boolean
        get() = prefs.getBoolean(KEY_6, false)
        set(value) = setBand(KEY_6, value)

    private fun setBand(key: String, value: Boolean) {
        if (!value) {
            val othersOn = listOf(
                KEY_24 to band24,
                KEY_5 to band5,
                KEY_6 to band6
            ).any { (k, on) -> k != key && on }
            if (!othersOn) return
        }
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        private const val KEY_ID = "selected_id"
        private const val KEY_24 = "band_24"
        private const val KEY_5 = "band_5"
        private const val KEY_6 = "band_6"
    }
}

object RadioFactory {
    fun create(context: Context, option: RadioOption, device: UsbDevice): RadioBackend? {
        val bands = RadioSelection(context)
        return when (option.kind) {
            RadioKind.T2U_PLUS, RadioKind.RTL8812AU, RadioKind.RTL8814AU, RadioKind.MT7612U ->
                TplinkT2uBackend(context, device, option, bands.band24, bands.band5, bands.band6)
            RadioKind.PAU0A -> PandaPau0aBackend(
                context, device, option, bands.band24, bands.band5, bands.band6
            )
            RadioKind.PAU0B, RadioKind.UNKNOWN -> null
        }
    }
}
