package app.radiusrf.navigation

/**
 * Every navigation destination. Tool metadata (job, icon, hardware, group) lives in
 * [ToolCatalog]; this only names routes and titles.
 */
sealed class RadiusRfScreen(
    val route: String,
    val title: String
) {
    // Top-level tabs
    data object Dashboard : RadiusRfScreen("dashboard", "Home")
    data object Tools : RadiusRfScreen("tools", "Tools")
    data object AlertCenter : RadiusRfScreen("alert_center", "Alerts")
    data object Sessions : RadiusRfScreen("sessions", "Sessions")
    data object Sweep : RadiusRfScreen("sweep", "Sweep")

    // Pushed screens
    data object Search : RadiusRfScreen("search", "Search")
    data object Settings : RadiusRfScreen("settings", "Settings")

    // Tools
    data object Sensors : RadiusRfScreen("sensors", "Sensor Dashboard")
    data object Wifi : RadiusRfScreen("wifi", "WiFi Analyzer")
    data object Ble : RadiusRfScreen("ble", "BLE Scanner")
    data object Nfc : RadiusRfScreen("nfc", "NFC Tools")
    data object Ir : RadiusRfScreen("ir", "IR Remote")
    data object Uwb : RadiusRfScreen("uwb", "UWB Radar")
    data object Usb : RadiusRfScreen("usb", "USB Devices")
    data object Sdr : RadiusRfScreen("sdr", "SDR Radio")
    data object FoxHunt : RadiusRfScreen("foxhunt", "Fox Hunt")
    data object FieldMap : RadiusRfScreen("field_map", "Field Map")
    data object Camera : RadiusRfScreen("camera", "QR Scanner")
    data object Ultrasonic : RadiusRfScreen("ultrasonic", "Ultrasonic")
    data object Wardriving : RadiusRfScreen("wardriving", "Wardriving")
    data object WardrivingPlus : RadiusRfScreen("wardriving_plus", "Wardriving +")
    data object WardrivingPlusPlus : RadiusRfScreen("wardriving_plus_plus", "Wardriving ++")
    data object WifiAware : RadiusRfScreen("wifi_aware", "Wi-Fi Aware")
    data object CellTower : RadiusRfScreen("cell_tower", "Cell Tower")
    data object UsbCamera : RadiusRfScreen("usb_camera", "USB Camera")
    data object Gps : RadiusRfScreen("gps", "GPS Tracker")
    data object BluetoothClassic : RadiusRfScreen("bluetooth_classic", "Bluetooth Classic")
    data object WifiDirect : RadiusRfScreen("wifi_direct", "Wi-Fi Direct")
    data object HiddenCamera : RadiusRfScreen("hidden_camera", "Camera Detector")
    data object GpsSpoofDetector : RadiusRfScreen("gps_spoof_detector", "GPS Spoof Detector")
    data object BluetoothTracker : RadiusRfScreen("bluetooth_tracker", "Tracker Scanner")
    data object RogueAp : RadiusRfScreen("rogue_ap", "Rogue AP Detector")
    data object NetworkScanner : RadiusRfScreen("network_scanner", "Network Scanner")
    data object RfBugSweeper : RadiusRfScreen("rf_bug_sweeper", "RF Bug Sweeper")
    data object ProximityRadar : RadiusRfScreen("proximity_radar", "Proximity Radar")
    data object PrivacyScore : RadiusRfScreen("privacy_score", "Privacy Score")
    data object DeauthDetector : RadiusRfScreen("deauth_detector", "Deauth Detector")
    data object EmfMapper : RadiusRfScreen("emf_mapper", "EMF Mapper")
    data object SignalLogger : RadiusRfScreen("signal_logger", "Signal Logger")

    companion object {
        // Lazy: an eager list here can capture nulls when a subclass object initializes first.
        val all: List<RadiusRfScreen> by lazy {
            listOf(
                Dashboard, Tools, AlertCenter, Sessions, Sweep, Search, Settings,
                Sensors, Wifi, Ble, Nfc, Ir, Uwb,
                Usb, Sdr, FoxHunt, FieldMap, Camera, Ultrasonic, Wardriving, WardrivingPlus, WardrivingPlusPlus, WifiAware,
                CellTower, UsbCamera, Gps, BluetoothClassic, WifiDirect, HiddenCamera,
                GpsSpoofDetector, BluetoothTracker, RogueAp, NetworkScanner, RfBugSweeper, ProximityRadar,
                PrivacyScore, DeauthDetector, EmfMapper, SignalLogger
            )
        }
    }
}

/** Route to run a sweep; a blank place is sent as a space because path segments can't be empty. */
fun sweepRunRoute(preset: app.radiusrf.features.sweep.domain.SweepPreset, place: String): String =
    "sweep/run/${preset.name}/${place.trim().let { if (it.isEmpty()) "%20" else android.net.Uri.encode(it) }}"
