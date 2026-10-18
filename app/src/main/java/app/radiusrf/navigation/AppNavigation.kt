package app.radiusrf.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import app.radiusrf.core.ui.zd.ZdStatePanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.radiusrf.core.debug.DemoDataAction
import app.radiusrf.core.ui.FeatureHelpSheet
import app.radiusrf.core.ui.HelpContent
import app.radiusrf.core.ui.rememberEthicalAgreement
import app.radiusrf.core.ui.zd.ZdHeader
import app.radiusrf.core.ui.zd.ZdIconButton
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdSnackbar
import app.radiusrf.features.alert_center.ui.AlertCenterScreen
import app.radiusrf.features.ble.ui.BleScreen
import app.radiusrf.features.ble.ui.GattExplorerScreen
import app.radiusrf.features.bluetooth_classic.ui.BluetoothClassicScreen
import app.radiusrf.features.bluetooth_tracker.ui.BluetoothTrackerScreen
import app.radiusrf.features.camera.ui.QrScannerScreen
import app.radiusrf.features.celltower.ui.CellTowerScreen
import app.radiusrf.features.dashboard.DashboardScreen
import app.radiusrf.features.deauth_detector.ui.DeauthDetectorScreen
import app.radiusrf.features.emf_mapper.ui.EmfMapperScreen
import app.radiusrf.features.gps.ui.GpsScreen
import app.radiusrf.features.gps_spoof_detector.ui.GpsSpoofScreen
import app.radiusrf.features.hidden_camera.ui.HiddenCameraScreen
import app.radiusrf.features.ir.ui.IrScreen
import app.radiusrf.features.network_scanner.ui.NetworkScannerScreen
import app.radiusrf.features.nfc.ui.NfcScreen
import app.radiusrf.features.privacy_score.ui.PrivacyScoreScreen
import app.radiusrf.features.proximity_radar.ui.ProximityRadarScreen
import app.radiusrf.features.locate.ui.LocateScreen
import app.radiusrf.features.onboarding.OnboardingScreen
import app.radiusrf.features.rf_bug_sweeper.ui.RfBugSweeperScreen
import app.radiusrf.features.search.SearchScreen
import app.radiusrf.features.settings.SettingsScreen
import app.radiusrf.features.watch.ui.RulesScreen
import app.radiusrf.features.sweep.domain.SweepPreset
import app.radiusrf.features.sweep.ui.SweepPresetsScreen
import app.radiusrf.features.sweep.ui.SweepRunScreen
import app.radiusrf.features.rogue_ap_detector.ui.RogueApScreen
import app.radiusrf.features.field.ui.FieldMapScreen
import app.radiusrf.features.field.ui.FoxHuntScreen
import app.radiusrf.features.sdr.ui.SdrScreen
import app.radiusrf.features.sensors.ui.SensorScreen
import app.radiusrf.features.signal_logger.ui.SignalLoggerScreen
import app.radiusrf.features.tools.ToolsScreen
import app.radiusrf.features.sessions.ui.CompareScreen
import app.radiusrf.features.sessions.ui.SessionDetailScreen
import app.radiusrf.features.sessions.ui.SessionsScreen
import app.radiusrf.features.devices.DeviceDetailScreen
import app.radiusrf.features.ultrasonic.ui.UltrasonicScreen
import app.radiusrf.features.usb.ui.UsbScreen
import app.radiusrf.features.usbcamera.ui.UsbCameraScreen
import app.radiusrf.features.uwb.ui.UwbScreen
import app.radiusrf.features.wardriving.domain.SurveyMode
import app.radiusrf.features.wardriving.ui.WardrivingScreen
import app.radiusrf.features.wifi.ui.WifiScreen
import app.radiusrf.features.wifi_direct.ui.WifiDirectScreen
import app.radiusrf.features.wifiaware.ui.WifiAwareScreen
import app.radiusrf.ui.theme.ZdColors
import kotlinx.coroutines.launch

private const val ANIM_DURATION = 300

private val enterTransition: EnterTransition =
    fadeIn(animationSpec = tween(ANIM_DURATION)) +
            slideInHorizontally(
                initialOffsetX = { it / 6 },
                animationSpec = tween(ANIM_DURATION)
            )

private val exitTransition: ExitTransition =
    fadeOut(animationSpec = tween(ANIM_DURATION)) +
            slideOutHorizontally(
                targetOffsetX = { -it / 6 },
                animationSpec = tween(ANIM_DURATION)
            )

private val popEnterTransition: EnterTransition =
    fadeIn(animationSpec = tween(ANIM_DURATION)) +
            slideInHorizontally(
                initialOffsetX = { -it / 6 },
                animationSpec = tween(ANIM_DURATION)
            )

private val popExitTransition: ExitTransition =
    fadeOut(animationSpec = tween(ANIM_DURATION)) +
            slideOutHorizontally(
                targetOffsetX = { it / 6 },
                animationSpec = tween(ANIM_DURATION)
            )

/** Tools reachable from the bottom bar, in bar order. */
internal val bottomTabs: List<BottomTab> = listOf(
    BottomTab(RadiusRfScreen.Dashboard.route, "Home", ZdIcons.Home),
    BottomTab(RadiusRfScreen.Tools.route, "Tools", ZdIcons.Grid),
    BottomTab(RadiusRfScreen.Sweep.route, "Sweep", ZdIcons.Sweep, emphasized = true),
    BottomTab(RadiusRfScreen.AlertCenter.route, "Alerts", ZdIcons.Bell, showsAlertBadge = true),
    BottomTab(RadiusRfScreen.Sessions.route, "Sessions", ZdIcons.Clock)
)

/** Routes that show the bottom bar. */
private val tabRoutes = setOf(
    RadiusRfScreen.Dashboard.route,
    RadiusRfScreen.Tools.route,
    RadiusRfScreen.Sweep.route,
    RadiusRfScreen.AlertCenter.route,
    RadiusRfScreen.Sessions.route
)

/** Opens a destination from a tab or a card, keeping Home as the single root. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { inclusive = false }
        launchSingleTop = true
    }
}

@Composable
fun AppNavigation(shell: AppShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val alertCount by shell.alertCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val agreed = rememberEthicalAgreement()
    val onboarded by shell.onboardingDone.collectAsState()
    val pendingRoute by shell.deepLinks.pending.collectAsState()

    // Notifications, tiles and widgets open screens by route.
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        shell.deepLinks.consume()
        runCatching { navController.navigate(route) { launchSingleTop = true } }
    }

    // Tablets and unfolded phones: a rail instead of the bottom bar, and Tools as list + detail.
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= WIDE_DP.dp
    val baseRoute = currentRoute?.substringBefore('?')
    val onTool = baseRoute != null && ToolCatalog.forRoute(baseRoute) != null
    val showListPane = wide && (baseRoute == RadiusRfScreen.Tools.route || onTool)
    val railRoute = if (onTool) RadiusRfScreen.Tools.route else baseRoute

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = ZdColors.Bg,
            snackbarHost = { SnackbarHost(snackbarHostState) { ZdSnackbar(it) } },
            bottomBar = {
                if (!wide && currentRoute in tabRoutes) {
                    ZdBottomBar(
                        tabs = bottomTabs,
                        currentRoute = currentRoute,
                        alertCount = alertCount,
                        onSelect = { navController.navigateTopLevel(it) }
                    )
                }
            }
        ) { paddingValues ->
            Row(Modifier.padding(paddingValues)) {
            if (wide) {
                ZdNavRail(
                    tabs = bottomTabs,
                    selectedRoute = railRoute,
                    alertCount = alertCount,
                    onSelect = { navController.navigateTopLevel(it) }
                )
            }
            if (showListPane) {
                Box(Modifier.width(LIST_PANE_DP.dp).fillMaxHeight()) {
                    ToolsScreen(
                        selectedRoute = baseRoute,
                        onOpenTool = { tool ->
                            // Swap the open tool instead of stacking one per tap.
                            val current = navController.currentBackStackEntry?.destination
                            navController.navigate(tool.route) {
                                if (onTool && current != null) popUpTo(current.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onSearch = { navController.navigate(RadiusRfScreen.Search.route) },
                        onSettings = { navController.navigate(RadiusRfScreen.Settings.route) },
                        onPinChanged = { tool, pinned ->
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(if (pinned) "${tool.name} pinned to Home" else "${tool.name} removed from Home")
                            }
                        }
                    )
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(ZdColors.Border))
            }
            NavHost(
                navController = navController,
                startDestination = RadiusRfScreen.Dashboard.route,
                modifier = Modifier.weight(1f),
                enterTransition = { enterTransition },
                exitTransition = { exitTransition },
                popEnterTransition = { popEnterTransition },
                popExitTransition = { popExitTransition }
            ) {
                composable(RadiusRfScreen.Dashboard.route) {
                    DashboardScreen(
                        onNavigate = { navController.navigateTopLevel(it) },
                        onSearch = { navController.navigate(RadiusRfScreen.Search.route) },
                        onSettings = { navController.navigate(RadiusRfScreen.Settings.route) }
                    )
                }
                composable(RadiusRfScreen.Tools.route) {
                    if (wide) {
                        ZdStatePanel(
                            kicker = "Tools",
                            icon = ZdIcons.Grid,
                            iconTint = ZdColors.Accent,
                            iconBackground = ZdColors.AccentBg,
                            title = "Pick a tool",
                            body = "Choose one from the list; it opens here and the list stays put so you can switch quickly."
                        )
                        return@composable
                    }
                    ToolsScreen(
                        onOpenTool = { navController.navigate(it.route) { launchSingleTop = true } },
                        onSearch = { navController.navigate(RadiusRfScreen.Search.route) },
                        onSettings = { navController.navigate(RadiusRfScreen.Settings.route) },
                        onPinChanged = { tool, pinned ->
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    if (pinned) "${tool.name} pinned to Home" else "${tool.name} removed from Home"
                                )
                            }
                        }
                    )
                }
                composable(RadiusRfScreen.AlertCenter.route) {
                    Column(Modifier.fillMaxSize()) {
                        ZdHeader(path = "/alerts", title = "Alerts") {
                            DemoDataAction(route = RadiusRfScreen.AlertCenter.route)
                        }
                        Box(Modifier.weight(1f)) {
                            AlertCenterScreen(onOpenTool = { navController.navigate(it) { launchSingleTop = true } })
                        }
                    }
                }

                composable(RadiusRfScreen.Sweep.route) {
                    SweepPresetsScreen(onStart = { preset, place -> navController.navigate(sweepRunRoute(preset, place)) })
                }
                composable(
                    "sweep/run/{preset}/{place}",
                    arguments = listOf(navArgument("preset") { type = NavType.StringType }, navArgument("place") { type = NavType.StringType })
                ) { entry ->
                    val preset = runCatching { SweepPreset.valueOf(entry.arguments?.getString("preset").orEmpty()) }.getOrDefault(SweepPreset.FULL)
                    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    Column(Modifier.fillMaxSize()) {
                        // Back goes through the dispatcher so a running sweep stops and reports first.
                        ZdHeader(path = "/sweep/run", title = preset.title, onBack = { backDispatcher?.onBackPressed() })
                        Box(Modifier.weight(1f)) {
                            SweepRunScreen(
                                onLocate = { f ->
                                    navController.navigate(
                                        locateRoute(f.key.orEmpty(), f.title, if (f.wifi) "wifi" else "ble")
                                    )
                                },
                                onCompare = { a, b -> navController.navigate("compare/$a/$b") },
                                onOpenSession = { navController.navigate("session/$it") },
                                onDone = { navController.popBackStack() }
                            )
                        }
                    }
                }
                composable(
                    route = "locate/{kind}/{address}/{label}?ch={ch}",
                    arguments = listOf(
                        navArgument("kind") { type = NavType.StringType; defaultValue = "ble" },
                        navArgument("address") { type = NavType.StringType },
                        navArgument("label") { type = NavType.StringType },
                        navArgument("ch") { type = NavType.IntType; defaultValue = 0 }
                    )
                ) {
                    Column(Modifier.fillMaxSize()) {
                        ZdHeader(path = "/locate", title = "Locate", onBack = { navController.popBackStack() })
                        Box(Modifier.weight(1f)) { LocateScreen() }
                    }
                }
                composable(RadiusRfScreen.Search.route) {
                    SearchScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTool = { navController.navigate(it.route) },
                        onOpenDevice = { navController.navigate(deviceRoute(it.itemKey, it.label, it.kind)) },
                        onOpenRoute = { navController.navigate(it) },
                        onLearn = { navController.navigate("${it.route}?help=true") }
                    )
                }
                pushed(RadiusRfScreen.Settings.route, "/settings", "Settings", navController, emptyList()) {
                    SettingsScreen(onOpenRules = { navController.navigate(RULES_ROUTE) }, onDataDeleted = {
                        scope.launch { snackbarHostState.showSnackbar("All saved data deleted") }
                    })
                }

                composable(RULES_ROUTE) {
                Column(Modifier.fillMaxSize()) {
                    ZdHeader(path = "/rules", title = "Watch rules", onBack = { if (!navController.popBackStack()) navController.navigateTopLevel(RadiusRfScreen.Dashboard.route) })
                    Box(Modifier.weight(1f)) {
                        RulesScreen(
                            onOpenSettings = { navController.navigate(RadiusRfScreen.Settings.route) }
                        )
                    }
                }
            }

            tool(RadiusRfScreen.Sensors, navController) { SensorScreen() }
                tool(RadiusRfScreen.Wifi, navController) {
                    WifiScreen(onLocate = { ap ->
                        navController.navigate(locateRoute(ap.bssid, ap.ssid.ifBlank { "<Hidden>" }, "wifi", ap.channel))
                    })
                }
                tool(RadiusRfScreen.Ble, navController) {
                    BleScreen(onOpenDevice = { address, name, kind ->
                        navController.navigate(deviceRoute(address, name ?: "", kind))
                    })
                }
                tool(RadiusRfScreen.Nfc, navController) { NfcScreen() }
                tool(RadiusRfScreen.Ir, navController) { IrScreen() }
                tool(RadiusRfScreen.Uwb, navController) { UwbScreen() }
                tool(RadiusRfScreen.Usb, navController) { UsbScreen() }
                tool(RadiusRfScreen.Sdr, navController) { SdrScreen() }
                tool(RadiusRfScreen.FoxHunt, navController) { FoxHuntScreen() }
                tool(RadiusRfScreen.FieldMap, navController) { FieldMapScreen() }
                tool(RadiusRfScreen.Camera, navController) { QrScannerScreen() }
                tool(RadiusRfScreen.Ultrasonic, navController) { UltrasonicScreen() }
                tool(RadiusRfScreen.Wardriving, navController) { WardrivingScreen(SurveyMode.WIFI) }
                tool(RadiusRfScreen.WardrivingPlus, navController) { WardrivingScreen(SurveyMode.PLUS) }
                tool(RadiusRfScreen.WardrivingPlusPlus, navController) { WardrivingScreen(SurveyMode.PLUS_PLUS) }
                tool(RadiusRfScreen.WifiAware, navController) { WifiAwareScreen() }
                tool(RadiusRfScreen.CellTower, navController) { CellTowerScreen() }
                tool(RadiusRfScreen.UsbCamera, navController) { UsbCameraScreen() }
                tool(RadiusRfScreen.Gps, navController) { GpsScreen() }
                tool(RadiusRfScreen.BluetoothClassic, navController) { BluetoothClassicScreen() }
                tool(RadiusRfScreen.WifiDirect, navController) { WifiDirectScreen() }
                tool(RadiusRfScreen.HiddenCamera, navController) { HiddenCameraScreen() }
                tool(RadiusRfScreen.GpsSpoofDetector, navController) { GpsSpoofScreen() }
                tool(RadiusRfScreen.BluetoothTracker, navController) {
                    BluetoothTrackerScreen(onOpenDevice = { address, name -> navController.navigate(deviceRoute(address, name, "TRACKER")) })
                }
                tool(RadiusRfScreen.RogueAp, navController) { RogueApScreen() }
                tool(RadiusRfScreen.NetworkScanner, navController) { NetworkScannerScreen() }
                tool(RadiusRfScreen.RfBugSweeper, navController) { RfBugSweeperScreen() }
                tool(RadiusRfScreen.ProximityRadar, navController) { ProximityRadarScreen() }
                tool(RadiusRfScreen.PrivacyScore, navController) { PrivacyScoreScreen() }
                tool(RadiusRfScreen.DeauthDetector, navController) { DeauthDetectorScreen() }
                tool(RadiusRfScreen.EmfMapper, navController) { EmfMapperScreen() }
                tool(RadiusRfScreen.SignalLogger, navController) { SignalLoggerScreen() }

                composable(RadiusRfScreen.Sessions.route) {
                    SessionsScreen(
                        onOpenSession = { navController.navigate("session/$it") },
                        onCompare = { a, b -> navController.navigate("compare/$a/$b") }
                    )
                }
                pushed("session/{id}", "/sessions/detail", "Session", navController, listOf("id")) {
                    SessionDetailScreen(
                        onDeleted = { navController.popBackStack() },
                        onOpenDevice = { navController.navigate(deviceRoute(it.key, it.label, it.kind.name)) }
                    )
                }
                pushed("compare/{a}/{b}", "/sessions/compare", "Compare sessions", navController, listOf("a", "b")) {
                    CompareScreen(onOpenDevice = { navController.navigate(deviceRoute(it.key, it.label, it.kind.name)) })
                }
                pushed("device/{key}/{label}/{kind}", "/devices", "Device detail", navController, listOf("key", "label", "kind")) {
                    DeviceDetailScreen(
                        onLocate = { address, label, kind -> navController.navigate(locateRoute(address, label, kind)) },
                        onOpenGatt = { address, name ->
                            navController.navigate("gatt_explorer/${Uri.encode(address)}/${Uri.encode(name ?: "")}")
                        },
                        onOpenSession = { navController.navigate("session/$it") }
                    )
                }

                composable(
                    route = "gatt_explorer/{address}/{name}",
                    arguments = listOf(
                        navArgument("address") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val address = backStackEntry.arguments?.getString("address") ?: return@composable
                    val name = backStackEntry.arguments?.getString("name")?.takeIf { it.isNotBlank() }
                    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    Column(Modifier.fillMaxSize()) {
                        ZdHeader(
                            path = "/tools/ble/${(name ?: address).lowercase().replace(' ', '-')}",
                            title = "GATT Explorer",
                            // Routed through the dispatcher so an open characteristic closes first.
                            onBack = { backDispatcher?.onBackPressed() }
                        )
                        Box(Modifier.weight(1f)) {
                            GattExplorerScreen(deviceAddress = address, deviceName = name)
                        }
                    }
                }
            }
            }
        }
        if (agreed && !onboarded) {
            // Covers the app until goals are picked; back does nothing so it can't be bypassed by accident.
            BackHandler {}
            OnboardingScreen()
        }
    }
}

/** Route to a device's cross-session history; every argument is URL-encoded. */
internal fun deviceRoute(key: String, label: String, kind: String): String =
    "device/${Uri.encode(key)}/${Uri.encode(label.ifBlank { " " })}/$kind"

internal const val RULES_ROUTE = "rules"

/** Width where the layout switches to rail + panes (Material's "expanded" class). */
private const val WIDE_DP = 840
private const val LIST_PANE_DP = 380

internal fun locateRoute(address: String, label: String, kind: String = "ble", channel: Int = 0): String =
    "locate/$kind/${Uri.encode(address)}/${Uri.encode(label.ifBlank { " " })}?ch=$channel"

/** A pushed (non-tab) screen with the standard header and a back button. */
private fun NavGraphBuilder.pushed(
    route: String,
    path: String,
    title: String,
    navController: NavHostController,
    args: List<String>,
    content: @Composable () -> Unit
) {
    composable(route, arguments = args.map { navArgument(it) { type = NavType.StringType } }) {
        Column(Modifier.fillMaxSize()) {
            ZdHeader(path = path, title = title, onBack = { navController.popBackStack() })
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/**
 * Registers a tool destination wrapped in the shared tool frame: terminal-path header with
 * back, help and (debug) demo-data actions above the tool's own content.
 */
private fun NavGraphBuilder.tool(
    screen: RadiusRfScreen,
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    composable(
        "${screen.route}?help={help}",
        arguments = listOf(navArgument("help") { type = NavType.BoolType; defaultValue = false })
    ) { entry ->
        val info = ToolCatalog.forRoute(screen.route)
        // Search's Learn results open a tool with its help sheet already up.
        var showHelp by rememberSaveable { mutableStateOf(entry.arguments?.getBoolean("help") == true) }
        val hasHelp = HelpContent.features.containsKey(screen.route)

        Column(Modifier.fillMaxSize()) {
            ZdHeader(
                path = info?.path ?: "/tools/${screen.route}",
                title = info?.name ?: screen.title,
                onBack = { if (!navController.popBackStack()) navController.navigateTopLevel(RadiusRfScreen.Dashboard.route) }
            ) {
                DemoDataAction(route = screen.route)
                if (hasHelp) {
                    ZdIconButton(ZdIcons.Help, contentDescription = "How this tool works", onClick = { showHelp = true })
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }

        if (showHelp) {
            FeatureHelpSheet(
                featureKey = screen.route,
                icon = info?.icon ?: ZdIcons.Help,
                onDismiss = { showHelp = false }
            )
        }
    }
}
