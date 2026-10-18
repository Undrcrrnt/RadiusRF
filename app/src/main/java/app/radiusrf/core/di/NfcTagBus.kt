package app.radiusrf.core.di

import android.nfc.Tag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges NFC tag discovery intents (delivered to MainActivity) to NfcViewModel.
 * Replaces the SharedFlow that used to live directly on AppContainer.
 */
@Singleton
class NfcTagBus @Inject constructor() {
    private val _tagFlow = MutableSharedFlow<Tag>(extraBufferCapacity = 1)
    val tagFlow: SharedFlow<Tag> = _tagFlow

    fun emit(tag: Tag) {
        _tagFlow.tryEmit(tag)
    }
}
