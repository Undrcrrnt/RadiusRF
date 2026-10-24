package app.radiusrf.features.maps

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.prefs.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OfflineMapViewModel @Inject constructor(
    val store: OfflineMapStore,
    val settings: AppSettings
) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    init {
        store.configureOsmdroid()
        store.refresh()
    }

    fun importArchive(uri: Uri, name: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _note.value = "Copying $name…"
            val error = store.importFrom(uri, name)
            _note.value = error ?: "Stored $name. Maps can use it without a network."
            _busy.value = false
        }
    }

    fun select(name: String) = store.select(name)

    fun delete(name: String) {
        store.delete(name)
        _note.value = "Removed $name"
    }

    fun setOfflineOnly(on: Boolean) = settings.setOfflineTilesOnly(on)
}
