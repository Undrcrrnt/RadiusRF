package app.radiusrf.features.wifi.domain

import android.content.Context
import com.emptyset.detector.radio.RadioKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class WifiNicSource(val label: String, val kind: RadioKind?) {
    PHONE("Phone", null),
    T2U_PLUS("8821AU", RadioKind.T2U_PLUS),
    RTL8812AU("8812AU", RadioKind.RTL8812AU),
    RTL8814AU("8814AU", RadioKind.RTL8814AU),
    PAU0A("PAU0A", RadioKind.PAU0A),
    MT7612U("7612U", RadioKind.MT7612U)
}

@Singleton
class WifiNicPreference @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _source = MutableStateFlow(read())
    val source: StateFlow<WifiNicSource> = _source.asStateFlow()

    fun select(source: WifiNicSource) {
        prefs.edit().putString(KEY, source.name).apply()
        _source.value = source
    }

    private fun read(): WifiNicSource =
        WifiNicSource.entries.firstOrNull { it.name == prefs.getString(KEY, WifiNicSource.PHONE.name) }
            ?: WifiNicSource.PHONE

    companion object {
        private const val PREFS = "wifi_nic"
        private const val KEY = "source"
    }
}
