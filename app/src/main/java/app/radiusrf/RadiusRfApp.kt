package app.radiusrf

import android.app.Application
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.sessions.SessionRepository
import app.radiusrf.features.surfaces.Widgets
import app.radiusrf.features.watch.data.WatchRuleStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class RadiusRfApp : Application() {

    @Inject lateinit var alerts: AlertCenterRepository
    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var watchRules: WatchRuleStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // Keep home-screen widgets in step with alerts, sessions and watch rules while we're alive;
        // the widgets' own 30-minute refresh covers the rest.
        appScope.launch {
            combine(alerts.openAlerts, sessions.sessions, watchRules.rules, watchRules.paused) { a, s, r, p -> listOf(a.size, s.size, r, p) }
                .drop(1)
                .debounce(1_000)
                .collect { Widgets.refresh(this@RadiusRfApp) }
        }
    }
}
