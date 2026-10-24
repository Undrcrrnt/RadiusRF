package app.radiusrf.features.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.BuildConfig
import app.radiusrf.R
import app.radiusrf.core.database.AppDatabase
import app.radiusrf.core.lifecycle.ScanBackground
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.prefs.AppSettings
import app.radiusrf.core.sessions.SessionRepository
import app.radiusrf.features.wardriving.service.WardrivingScanService
import app.radiusrf.features.watch.service.WatchService
import app.radiusrf.features.maps.OfflineMapPanel
import app.radiusrf.features.wifi.domain.WifiNicPreference
import app.radiusrf.features.wifi.ui.WifiNicPicker
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdDialog
import app.radiusrf.core.ui.zd.ZdIconButton
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdSwitchRow
import app.radiusrf.core.ui.zd.ZdTextField
import app.radiusrf.ui.theme.AppPalette
import app.radiusrf.ui.theme.AppThemes
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: AppSettings,
    val nic: WifiNicPreference,
    private val database: AppDatabase,
    private val sessions: SessionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    fun addTrusted(ssid: String) = settings.setTrustedNetworks(settings.trustedNetworks.value + ssid.trim())
    fun removeTrusted(ssid: String) = settings.setTrustedNetworks(settings.trustedNetworks.value - ssid)

    /** Wipes every scan, session, alert and log on this phone; settings stay. */
    fun deleteAllData(onDone: () -> Unit) {
        ScanBackground.releaseAll(context)
        context.stopService(Intent(context, WardrivingScanService::class.java))
        context.stopService(Intent(context, WatchService::class.java))
        viewModelScope.launch(Dispatchers.IO) {
            sessions.withWritesPaused { database.clearAllTables() }
            launch(Dispatchers.Main) { onDone() }
        }
    }

    fun redoOnboarding() = settings.setOnboardingDone(false)
}

@Composable
fun SettingsScreen(
    onOpenRules: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenPermissions: () -> Unit,
    onDataDeleted: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s = viewModel.settings
    val nic by viewModel.nic.source.collectAsState()
    val redact by s.redactExports.collectAsState()
    val retention by s.retentionDays.collectAsState()
    val keepOn by s.keepScreenOnDuringSweeps.collectAsState()
    val trusted by s.trustedNetworks.collectAsState()
    val hideOnLock by s.hideOnLockScreen.collectAsState()
    val mine by s.myDevices.collectAsState()
    var newSsid by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showAgreement by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val selectedTheme = AppThemes.selected.value
        ZdSectionLabel("Theme")
        ZdListCard(listOf(selectedTheme)) { theme ->
            ZdListRow(
                title = theme.name,
                titleMono = false,
                leading = { ThemeSwatch(theme) },
                showChevron = true,
                onClick = onOpenThemes
            )
        }

        ZdSectionLabel("Permissions")
        ZdListCard(listOf(Unit)) {
            val granted = grantedPermissionCount()
            val total = PermissionUtils.toggleablePermissions().size
            ZdListRow(
                title = "Permissions",
                titleMono = false,
                subtitle = "$granted of $total on",
                showChevron = true,
                onClick = onOpenPermissions
            )
        }

        ZdSectionLabel("Wi-Fi radio")
        WifiNicPicker(
            source = nic,
            onSelect = viewModel.nic::select,
            horizontalPadding = 0.dp,
            showHeading = false
        )

        OfflineMapPanel()

        ZdSectionLabel("Privacy")
        ZdCard(verticalSpacing = 0.dp) {
            ZdSwitchRow(
                label = "Redact MAC addresses in exports",
                description = "The default for every export; you can still change it per export",
                checked = redact,
                onCheckedChange = s::setRedactExports
            )
        }
        ZdCard(verticalSpacing = 8.dp) {
            Text("Keep sessions for", style = ZdType.Label, color = ZdColors.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.RETENTION_CHOICES.forEach { days ->
                    ZdChip("$days days", selected = retention == days, onClick = { s.setRetentionDays(days) })
                }
            }
            Text("Older sessions are deleted automatically. Everything stays on this phone.", style = ZdType.Caption, color = ZdColors.Text3)
        }

        ZdCard(verticalSpacing = 0.dp) {
            ZdSwitchRow(
                label = "Hide alert details on the lock screen",
                description = "Shows “Possible tracker nearby” until you unlock",
                checked = hideOnLock,
                onCheckedChange = s::setHideOnLockScreen
            )
        }

        ZdSectionLabel("Background")
        ZdListCard(listOf(0)) {
            ZdListRow(
                title = "Watch rules",
                titleMono = false,
                subtitle = "Follow-me trackers, rogue APs, deauth and 2G downgrades",
                showChevron = true,
                onClick = onOpenRules
            )
        }
        if (mine.isNotEmpty()) {
            ZdSectionLabel("Marked as mine", trailingText = "${mine.size}")
            ZdListCard(mine.sorted()) { address ->
                ZdListRow(
                    title = address,
                    trailing = { ZdIconButton(ZdIcons.Close, contentDescription = "Forget $address", onClick = { s.forgetMine(address) }) }
                )
            }
        }

        ZdSectionLabel("Room sweep")
        ZdCard(verticalSpacing = 0.dp) {
            ZdSwitchRow(
                label = "Keep screen on during sweeps",
                description = "Android slows scanning when the screen turns off",
                checked = keepOn,
                onCheckedChange = s::setKeepScreenOn
            )
        }

        ZdSectionLabel("Trusted networks", trailingText = "${trusted.size}")
        Text(
            "Your own WiFi names. Rogue AP and sweeps warn you first when a look-alike appears.",
            style = ZdType.Caption,
            color = ZdColors.Text3
        )
        if (trusted.isNotEmpty()) {
            ZdListCard(trusted.sorted()) { ssid ->
                ZdListRow(
                    title = ssid,
                    leading = null,
                    trailing = { ZdIconButton(ZdIcons.Close, contentDescription = "Remove $ssid", onClick = { viewModel.removeTrusted(ssid) }) }
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val add = { if (newSsid.isNotBlank()) { viewModel.addTrusted(newSsid); newSsid = "" } }
            ZdTextField(
                value = newSsid,
                onValueChange = { newSsid = it },
                label = "ADD NETWORK",
                placeholder = "Home WiFi name",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f)
            )
            ZdButton("Add", onClick = add, enabled = newSsid.isNotBlank(), icon = ZdIcons.Plus, variant = ZdButtonVariant.Secondary)
        }

        ZdSectionLabel("About")
        ZdListCard(listOf(0, 1, 2)) { row ->
            when (row) {
                0 -> ZdListRow(title = "Pick my goals again", titleMono = false, subtitle = "Re-pins tools on Home", showChevron = true, onClick = viewModel::redoOnboarding)
                1 -> ZdListRow(title = "Responsible use agreement", titleMono = false, showChevron = true, onClick = { showAgreement = true })
                else -> ZdListRow(title = "RadiusRF ${BuildConfig.VERSION_NAME}", titleMono = false, subtitle = "Open source · for learning about the radios around you")
            }
        }

        ZdSectionLabel("Data")
        ZdButton("Delete all saved data", onClick = { confirmDelete = true }, variant = ZdButtonVariant.Danger, icon = ZdIcons.Trash)
    }

    if (confirmDelete) {
        ZdDialog(
            title = "Delete everything?",
            onDismiss = { confirmDelete = false },
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                viewModel.deleteAllData(onDataDeleted)
            }
        ) {
            Text(
                "Removes every session, alert, saved BLE device, NFC tag, QR scan and wardriving log from this phone. Settings are kept. This can’t be undone.",
                style = ZdType.BodySmall,
                color = ZdColors.Text2
            )
        }
    }
    if (showAgreement) {
        ZdDialog(
            title = stringResource(R.string.ethical_title),
            onDismiss = { showAgreement = false },
            confirmLabel = "Close",
            onConfirm = { showAgreement = false },
            dismissLabel = ""
        ) {
            Text(stringResource(R.string.ethical_message), style = ZdType.BodySmall, color = ZdColors.Text2)
        }
    }
}

@Composable
fun ThemePickerScreen() {
    val context = LocalContext.current
    val selectedId = AppThemes.selected.value.id
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
    ) {
        ZdListCard(AppThemes.all) { theme ->
            ZdListRow(
                title = theme.name,
                titleMono = false,
                leading = { ThemeSwatch(theme) },
                trailing = {
                    if (theme.id == selectedId) {
                        Icon(
                            ZdIcons.Check,
                            contentDescription = "Selected",
                            tint = ZdColors.Accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                onClick = { AppThemes.select(context, theme.id) }
            )
        }
    }
}

@Composable
private fun grantedPermissionCount(): Int {
    val context = LocalContext.current
    return PermissionUtils.toggleablePermissions().count {
        ContextCompat.checkSelfPermission(context, it.name) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun ThemeSwatch(theme: AppPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(theme.bg, theme.accent, theme.info, theme.critical).forEach { color ->
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}
