package app.radiusrf

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.radiusrf.core.di.NfcTagBus
import app.radiusrf.core.lifecycle.ScanBackground
import app.radiusrf.core.notify.DeepLinkBus
import app.radiusrf.features.surfaces.TileRoutes
import app.radiusrf.features.watch.data.WatchRuleStore
import app.radiusrf.features.watch.service.WatchService
import app.radiusrf.navigation.AppNavigation
import app.radiusrf.ui.theme.RadiusRfTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        setContent {
            RadiusRfTheme {
                AppNavigation()
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
