package app.radiusrf

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.radiusrf.core.di.NfcTagBus
import app.radiusrf.core.lifecycle.ScanBackground
import app.radiusrf.core.notify.DeepLinkBus
import app.radiusrf.features.surfaces.TileRoutes
import app.radiusrf.features.watch.data.WatchRuleStore
import app.radiusrf.features.watch.service.WatchService
import app.radiusrf.navigation.AppNavigation
import app.radiusrf.ui.StartupSplash
import app.radiusrf.ui.theme.AppThemes
import app.radiusrf.ui.theme.RadiusRfTheme
import kotlinx.coroutines.delay
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var nfcTagBus: NfcTagBus

    @Inject
    lateinit var deepLinks: DeepLinkBus

    @Inject
    lateinit var watchRules: WatchRuleStore

    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null

    private companion object {
        const val SPLASH_MS = 5_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setOnExitAnimationListener { it.remove() }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        AppThemes.load(this)
        val coldStart = savedInstanceState == null
        setContent {
            RadiusRfTheme {
                val openedAt = rememberSaveable { SystemClock.elapsedRealtime() }
                var splash by rememberSaveable { mutableStateOf(coldStart) }
                LaunchedEffect(splash) {
                    if (!splash) return@LaunchedEffect
                    val remaining = SPLASH_MS - (SystemClock.elapsedRealtime() - openedAt)
                    if (remaining > 0) delay(remaining)
                    splash = false
                }
                Box(Modifier.fillMaxSize()) {
                    AppNavigation()
                    if (splash) {
                        BackHandler { }
                        StartupSplash()
                    }
                }
            }
        }

        // Handle NFC tag from initial launch intent
        handleNfcIntent(intent)
        handleRouteIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, nfcPendingIntent, null, null)
        // Watch rules resume whenever the app is opened (Android won't start them from the background).
        WatchService.sync(this, watchRules)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) ScanBackground.releaseAll(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
        handleRouteIntent(intent)
    }

    /** Notifications, widgets and tiles open a screen by route; long-pressed tiles by component. */
    private fun handleRouteIntent(intent: Intent) {
        val route = intent.getStringExtra(DeepLinkBus.EXTRA_ROUTE)
            ?: if (intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
                val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
                }
                TileRoutes.forComponent(component?.className)
            } else null
        if (route != null) {
            deepLinks.open(route)
            intent.removeExtra(DeepLinkBus.EXTRA_ROUTE)
        }
    }

    private fun handleNfcIntent(intent: Intent) {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action
        ) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            tag?.let {
                nfcTagBus.emit(it)
            }
        }
    }
}
