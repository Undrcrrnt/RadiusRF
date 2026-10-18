package app.radiusrf.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.notify.DeepLinkBus
import app.radiusrf.core.prefs.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** State the app shell needs across every tab: the Alerts badge and first-run onboarding. */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    alertCenterRepository: AlertCenterRepository,
    settings: AppSettings,
    val deepLinks: DeepLinkBus
) : ViewModel() {

    val onboardingDone: StateFlow<Boolean> = settings.onboardingDone

    /** Only alerts still waiting for triage count towards the badge. */
    val alertCount: StateFlow<Int> = alertCenterRepository.openAlerts
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
