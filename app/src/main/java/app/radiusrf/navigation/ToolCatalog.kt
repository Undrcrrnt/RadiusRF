package app.radiusrf.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import app.radiusrf.core.hardware.HardwareChecker
import app.radiusrf.core.ui.zd.ZdIcons

/** How the Tools screen groups tools: by the job people want done, not by radio. */
enum class ToolGroup(val label: String, val chip: String) {
    DETECT("Detect threats", "Detect threats"),
    SCAN("Scan & discover", "Scan"),
    ANALYZE("Analyze signals", "Analyze"),
    INTERACT("Read, write & transmit", "Read / write"),
    LOG("Log & map", "Log")
}

/** The hardware a tool needs, shown as the tag on its row and used to dim unsupported tools. */
enum class HardwareRequirement(val tag: String, val missingLabel: String?) {
    WIFI("WIFI", "no WiFi"),
    BLE("BLE", "no Bluetooth LE"),
    BT("BT", "no Bluetooth"),
    LAN("LAN", "no WiFi"),
    NAN("NAN", "no Wi-Fi Aware"),
    P2P("P2P", "no Wi-Fi Direct"),
    CELL("CELL", "no cellular radio"),
    GPS("GPS", "no GPS"),
    OTG("OTG", "no USB host"),
    CAM("CAM", "no camera"),
    MIC("MIC", null),
    MAG("MAG", "no magnetometer"),
    IMU("IMU", "no accelerometer"),
    UWB("UWB", "no UWB"),
    NFC("NFC", "no NFC"),
    IR("IR", "no IR blaster"),
    MULTI("MULTI", null),
    NONE("—", null);

    fun isAvailable(hw: HardwareChecker): Boolean = when (this) {
        WIFI, LAN -> hw.hasWifi()
        BLE -> hw.hasBluetoothLe()
        BT -> hw.hasBluetooth()
        NAN -> hw.hasWifiAware()
        P2P -> hw.hasWifiDirect()
        CELL -> hw.hasTelephony()
        GPS -> hw.hasGps()
        OTG -> hw.hasUsbHost()
        CAM -> hw.hasCamera()
        MAG -> hw.hasMagnetometer()
        IMU -> hw.hasAccelerometer()
        UWB -> hw.hasUwb()
        NFC -> hw.hasNfc()
        IR -> hw.hasIr()
        MIC, MULTI, NONE -> true
    }
}

data class ToolInfo(
    val screen: RadiusRfScreen,
    val name: String,
    /** One line on the job the tool does, in plain words. */
    val job: String,
    /** Terminal path shown in the header, e.g. `/tools/wifi`. */
    val path: String,
    val group: ToolGroup,
    val requirement: HardwareRequirement,
    val icon: ImageVector
) {
    val route: String get() = screen.route
}

object ToolCatalog {

    val tools: List<ToolInfo> by lazy {
        listOf(
            // Detect threats
            ToolInfo(RadiusRfScreen.BluetoothTracker, "Tracker Scanner", "Find AirTags, Tiles and SmartTags near you", "/tools/trackers", ToolGroup.DETECT, HardwareRequirement.BLE, ZdIcons.Tracker),
            ToolInfo(RadiusRfScreen.HiddenCamera, "Hidden Camera", "Check a room: WiFi OUI, BLE, magnetic, ports", "/tools/hidden-camera", ToolGroup.DETECT, HardwareRequirement.MULTI, ZdIcons.Camera),
            ToolInfo(RadiusRfScreen.RogueAp, "Rogue AP", "Spot evil twins and karma hotspots", "/tools/rogue-ap", ToolGroup.DETECT, HardwareRequirement.WIFI, ZdIcons.Wifi),
            ToolInfo(RadiusRfScreen.DeauthDetector, "Deauth Detector", "Notice floods kicking you off WiFi", "/tools/deauth", ToolGroup.DETECT, HardwareRequirement.WIFI, ZdIcons.Warning),
            ToolInfo(RadiusRfScreen.CellTower, "Cell Tower", "Watch for IMSI-catcher indicators", "/tools/cell", ToolGroup.DETECT, HardwareRequirement.CELL, ZdIcons.CellTower),
            ToolInfo(RadiusRfScreen.GpsSpoofDetector, "GPS Spoof", "Cross-check GPS against cell, WiFi, sensors", "/tools/gps-spoof", ToolGroup.DETECT, HardwareRequirement.GPS, ZdIcons.Crosshair),
            ToolInfo(RadiusRfScreen.RfBugSweeper, "RF Bug Sweeper", "BLE + ultrasonic + magnetic sweep", "/tools/rf-bug-sweeper", ToolGroup.DETECT, HardwareRequirement.MULTI, ZdIcons.Sweep),
            ToolInfo(RadiusRfScreen.Usb, "USB Devices", "Inspect USB, flag BadUSB combos", "/tools/usb", ToolGroup.DETECT, HardwareRequirement.OTG, ZdIcons.Usb),
            ToolInfo(RadiusRfScreen.Camera, "QR Scanner", "Check codes for phishing before opening", "/tools/qr", ToolGroup.DETECT, HardwareRequirement.CAM, ZdIcons.Qr),
            ToolInfo(RadiusRfScreen.PrivacyScore, "Privacy Score", "16+ checks on your phone’s exposure", "/tools/privacy", ToolGroup.DETECT, HardwareRequirement.NONE, ZdIcons.ShieldCheck),

            // Scan & discover
            ToolInfo(RadiusRfScreen.Wifi, "WiFi Analyzer", "Networks, channels and weak security", "/tools/wifi", ToolGroup.SCAN, HardwareRequirement.WIFI, ZdIcons.Wifi),
            ToolInfo(RadiusRfScreen.Ble, "BLE Scanner", "Devices, GATT explorer, JSON dumps", "/tools/ble", ToolGroup.SCAN, HardwareRequirement.BLE, ZdIcons.Bluetooth),
            ToolInfo(RadiusRfScreen.BluetoothClassic, "Bluetooth Classic", "Discovery, SDP services, SPP serial", "/tools/bt-classic", ToolGroup.SCAN, HardwareRequirement.BT, ZdIcons.Bluetooth),
            ToolInfo(RadiusRfScreen.NetworkScanner, "Network Scanner", "Hosts, open ports, banners on your LAN", "/tools/network", ToolGroup.SCAN, HardwareRequirement.LAN, ZdIcons.Lan),
            ToolInfo(RadiusRfScreen.ProximityRadar, "Proximity Radar", "Plot nearby devices by distance", "/tools/radar", ToolGroup.SCAN, HardwareRequirement.BLE, ZdIcons.Sweep),
            ToolInfo(RadiusRfScreen.WifiAware, "Wi-Fi Aware", "Router-less NAN peer discovery", "/tools/wifi-aware", ToolGroup.SCAN, HardwareRequirement.NAN, ZdIcons.Wifi),
            ToolInfo(RadiusRfScreen.Sdr, "SDR Radio", "RTL-SDR and HackRF spectrum, waterfall, and audio", "/tools/sdr", ToolGroup.SCAN, HardwareRequirement.OTG, ZdIcons.Radio),
            ToolInfo(RadiusRfScreen.FoxHunt, "Fox Hunt", "Bearing from the strongest heading", "/tools/foxhunt", ToolGroup.ANALYZE, HardwareRequirement.MULTI, ZdIcons.Crosshair),
            ToolInfo(RadiusRfScreen.FieldMap, "Field Map", "Offline pins for surveys, Remote ID, and bearings", "/tools/field-map", ToolGroup.LOG, HardwareRequirement.GPS, ZdIcons.Map),
            ToolInfo(RadiusRfScreen.UsbCamera, "USB Camera", "UVC camera modes and preview", "/tools/usb-camera", ToolGroup.SCAN, HardwareRequirement.OTG, ZdIcons.Camera),

            // Analyze signals
            ToolInfo(RadiusRfScreen.Ultrasonic, "Ultrasonic", "18–24 kHz spectrum for beacons", "/tools/ultrasonic", ToolGroup.ANALYZE, HardwareRequirement.MIC, ZdIcons.Waveform),
            ToolInfo(RadiusRfScreen.EmfMapper, "EMF Mapper", "Magnetic field map and hotspots", "/tools/emf", ToolGroup.ANALYZE, HardwareRequirement.MAG, ZdIcons.Magnet),
            ToolInfo(RadiusRfScreen.Sensors, "Sensor Dashboard", "Motion, compass, level, metal detector", "/tools/sensors", ToolGroup.ANALYZE, HardwareRequirement.IMU, ZdIcons.Sensors),
            ToolInfo(RadiusRfScreen.Gps, "GPS Tracker", "Position, satellites, raw NMEA", "/tools/gps", ToolGroup.ANALYZE, HardwareRequirement.GPS, ZdIcons.Crosshair),
            ToolInfo(RadiusRfScreen.Uwb, "UWB Radar", "FiRa ranging and AoA capability", "/tools/uwb", ToolGroup.ANALYZE, HardwareRequirement.UWB, ZdIcons.Sweep),

            // Read, write & transmit
            ToolInfo(RadiusRfScreen.Nfc, "NFC Tools", "Read, write, dump MIFARE, emulate", "/tools/nfc", ToolGroup.INTERACT, HardwareRequirement.NFC, ZdIcons.Nfc),
            ToolInfo(RadiusRfScreen.Ir, "IR Remote", "TV remotes, Flipper .ir import", "/tools/ir", ToolGroup.INTERACT, HardwareRequirement.IR, ZdIcons.Remote),
            ToolInfo(RadiusRfScreen.WifiDirect, "Wi-Fi Direct", "P2P groups and file transfer", "/tools/wifi-direct", ToolGroup.INTERACT, HardwareRequirement.P2P, ZdIcons.Peers),

            // Log & map
            ToolInfo(RadiusRfScreen.SignalLogger, "Signal Logger", "WiFi + BLE arrival/departure timeline", "/tools/signal-logger", ToolGroup.LOG, HardwareRequirement.MULTI, ZdIcons.Timeline),
            ToolInfo(RadiusRfScreen.Wardriving, "Wardriving", "GPS + Wi-Fi log, export for Netseer and Wireshark", "/tools/wardriving", ToolGroup.LOG, HardwareRequirement.GPS, ZdIcons.Map),
            ToolInfo(RadiusRfScreen.WardrivingPlus, "Wardriving +", "GPS + Wi-Fi + Bluetooth log and export", "/tools/wardriving-plus", ToolGroup.LOG, HardwareRequirement.GPS, ZdIcons.Map),
            ToolInfo(RadiusRfScreen.WardrivingPlusPlus, "Wardriving ++", "GPS + Wi-Fi + Bluetooth + cellular log and export", "/tools/wardriving-plus-plus", ToolGroup.LOG, HardwareRequirement.GPS, ZdIcons.Map)
        )
    }

    private val byRoute: Map<String, ToolInfo> by lazy { tools.associateBy { it.route } }

    fun forRoute(route: String?): ToolInfo? = route?.let { byRoute[it] }

    /** Pinned to Home until the user picks their own (onboarding goals set these later). */
    val defaultPinnedRoutes: List<String> = listOf(
        RadiusRfScreen.Wifi.route,
        RadiusRfScreen.BluetoothTracker.route,
        RadiusRfScreen.Ble.route,
        RadiusRfScreen.Nfc.route
    )
}

/** Filters the catalog the way the Tools screen does: by group chip, supported-only, and text. */
fun filterTools(
    tools: List<ToolInfo>,
    group: ToolGroup?,
    query: String,
    onlySupported: Boolean,
    isAvailable: (ToolInfo) -> Boolean
): List<ToolInfo> {
    val q = query.trim().lowercase()
    return tools.filter { tool ->
        (group == null || tool.group == group) &&
            (!onlySupported || isAvailable(tool)) &&
            (q.isEmpty() || "${tool.name} ${tool.job} ${tool.requirement.tag}".lowercase().contains(q))
    }
}
