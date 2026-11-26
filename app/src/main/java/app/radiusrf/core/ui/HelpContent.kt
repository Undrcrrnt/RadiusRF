package app.radiusrf.core.ui

/** How a result looks on screen, for the "Reading the results" legend. */
enum class LegendMark { SIGNAL_STRONG, SIGNAL_WEAK, TAG_GOOD, TAG_WARN, TAG_BAD, TAG_INFO }

data class HelpLegend(val mark: LegendMark, val label: String, val meaning: String)

/** A short fact with a one-line explanation: limits, permissions, false positives. */
data class HelpFact(val title: String, val detail: String)

/**
 * In-context help for one tool, keyed by its route: what to do ([steps]), the idea behind it
 * ([howItWorks]), how to read the screen ([legend]) and the limits worth knowing ([facts]).
 */
data class FeatureHelp(
    val title: String,
    val description: String,
    val steps: List<String> = emptyList(),
    val howItWorks: String? = null,
    val legend: List<HelpLegend> = emptyList(),
    val facts: List<HelpFact> = emptyList()
)

private val strongSignal = HelpLegend(LegendMark.SIGNAL_STRONG, "", "Strong, about -55 dBm or better")
private val weakSignal = HelpLegend(LegendMark.SIGNAL_WEAK, "", "Weak, about -85 dBm; far away or behind walls")
private val passMark = HelpLegend(LegendMark.TAG_GOOD, "PASS", "Check found nothing unusual")
private val warnMark = HelpLegend(LegendMark.TAG_WARN, "WARN", "Worth a look; often has an innocent cause")
private val failMark = HelpLegend(LegendMark.TAG_BAD, "FAIL", "Matches the pattern of an attack or risk")

object HelpContent {
    val features: Map<String, FeatureHelp> = mapOf(

        // ── Detect threats ───────────────────────────────────────────────────

        "bluetooth_tracker" to FeatureHelp(
            title = "Tracker Scanner",
            description = "Find My, Find Hub, SmartTag, Tile, and Chipolo",
            steps = listOf(
                "Tap Start and keep the phone with you as you move.",
                "Watch for trackers that stay near you across places.",
                "Tap Watch in the background to keep checking after you leave this screen.",
                "Tap a tracker to see why it was flagged, or mark it as yours."
            ),
            howItWorks = "Trackers advertise over Bluetooth Low Energy. RadiusRF reads Find My (AirTag and other Find My tags), Find Hub’s location advertisement, Samsung SmartTag services, Tile, Chipolo, and the shared unwanted-tracker advertisement used by Apple, Google, Samsung, and Amazon. A product on one of those networks is listed as that network. The address rotates, so the advertisement key is what keeps one tag together. A tag whose advertisement says the owner is nearby stays quiet. Background watching uses the follow-me rule and keeps the places on this phone.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_BAD, "HIGH", "Seen many times over a long period"),
                HelpLegend(LegendMark.TAG_WARN, "MEDIUM", "Seen a few times; keep watching"),
                HelpLegend(LegendMark.TAG_INFO, "LOW", "Just appeared, not persistent yet")
            ),
            facts = listOf(
                HelpFact("Your own tags show up too", "Mark them as yours so they stop alerting"),
                HelpFact("Separated AirTags", "An AirTag away from its owner sets a status bit. Tags still with their owner stay at low risk and do not alert"),
                HelpFact("Saved path", "Places at least 250 m apart are kept on this phone and listed under Where it was heard"),
                HelpFact("Distance is a guess", "Estimated from signal strength; can be off by 2× indoors"),
                HelpFact("Find Hub", "The Find Hub location advertisement is service FEAA. Ordinary Fast Pair headphones are not trackers"),
                HelpFact("SmartTag", "Samsung SmartTag services FD5A and FD59. A Samsung phone or earbuds advertisement is not a SmartTag"),
                HelpFact("Shared advertisement", "Service FCB2 is the unwanted-tracker advertisement. The network byte says Find My, Find Hub, SmartTag, Amazon, or another registered network"),
                HelpFact("Cell trackers", "A GPS tracker that only talks to a cell tower does not appear in this scan")
            )
        ),

        "signature_watch" to FeatureHelp(
            title = "Signature Watch",
            description = "Find Hub, glasses, action cams, serial modules",
            steps = listOf(
                "Tap Start and stay in the room or street you care about.",
                "Filter by kind if the list is long.",
                "A match is a reason to look, not proof of a threat."
            ),
            howItWorks = "The phone already hears BLE advertisements and Wi-Fi names. This list matches Google Find Hub, camera-glasses names, action-cam and cheap IP-cam hotspots, and common serial-module names such as HC-05.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_INFO, "FIND HUB", "Google Find My Device network advertisement"),
                HelpLegend(LegendMark.TAG_WARN, "GLASSES", "Wearable camera name"),
                HelpLegend(LegendMark.TAG_WARN, "SERIAL", "HC-05-style module name")
            ),
            facts = listOf(
                HelpFact("Receive only", "It identifies advertisements. It does not connect."),
                HelpFact("False names", "Anyone can rename a module. Treat a match as a hint.")
            )
        ),

        "hidden_camera" to FeatureHelp(
            title = "Hidden Camera",
            description = "Check a room: WiFi OUI, BLE, magnetic, ports",
            steps = listOf(
                "Tap Start while connected to the room’s WiFi.",
                "Walk slowly past smoke detectors, clocks, chargers and vents.",
                "Review findings; strong ones combine several methods."
            ),
            howItWorks = "No single signal proves a camera, so five weak signals are combined: the vendor prefix (OUI) of nearby networks, camera-style network names, BLE signatures, magnetic anomalies from electronics, and open video ports like RTSP 554 on your network. A finding that matches several at once is much more likely to be real.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_BAD, "LIKELY", "Several methods agree"),
                HelpLegend(LegendMark.TAG_WARN, "POSSIBLE", "One method matched"),
                HelpLegend(LegendMark.TAG_GOOD, "CLEAN", "Method ran and found nothing")
            ),
            facts = listOf(
                HelpFact("Offline cameras", "A camera recording to an SD card with no radio can’t be seen by any app"),
                HelpFact("Port scan scope", "Only the network you are connected to is checked"),
                HelpFact("Magnetometer", "Speakers and metal furniture also cause spikes")
            )
        ),

        "rogue_ap" to FeatureHelp(
            title = "Rogue AP",
            description = "Spot evil twins and karma hotspots",
            steps = listOf(
                "Tap Start near the networks you want to check.",
                "Mark your home and work networks as trusted.",
                "Review findings; twins of trusted networks rank highest."
            ),
            howItWorks = "An evil twin copies a real network’s name so your phone joins it instead. The detector compares every access point sharing a name: different vendors, a sudden stronger signal, or a name one character off (H0me vs Home) all point to impersonation. Karma attacks answer to many network names from one radio.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_BAD, "EVIL TWIN", "Same name, different hardware"),
                HelpLegend(LegendMark.TAG_WARN, "SPOOF", "Look-alike name of a trusted network"),
                passMark
            ),
            facts = listOf(
                HelpFact("Mesh and multi-AP networks", "Offices often have many APs with one name; same vendor is usually fine"),
                HelpFact("Android limits scans", "4 WiFi scans every 2 minutes per app"),
                HelpFact("Safer habit", "Forget open networks you no longer use")
            )
        ),

        "deauth_detector" to FeatureHelp(
            title = "Deauth Detector",
            description = "Notice floods kicking you off WiFi",
            steps = listOf(
                "Connect to the network you want to watch.",
                "Tap Start and leave it running.",
                "Check the timeline when your WiFi keeps dropping."
            ),
            howItWorks = "WiFi lets a router tell a device to disconnect with a deauthentication frame, and older networks don’t sign those frames, so anyone can forge them. Android doesn’t expose raw frames, so the detector watches the symptoms: repeated disconnects while the signal stays strong, the network vanishing, or sudden channel changes.",
            legend = listOf(failMark, warnMark, passMark),
            facts = listOf(
                HelpFact("Bad signal looks different", "Weak-signal drops come with falling dBm; attacks don’t"),
                HelpFact("WPA3 helps", "Protected Management Frames make forged deauths ineffective"),
                HelpFact("Connected network only", "It can’t see frames aimed at other devices")
            )
        ),

        "cell_tower" to FeatureHelp(
            title = "Cell Tower",
            description = "Watch for IMSI-catcher indicators",
            steps = listOf(
                "Tap Start and leave the phone still for a few minutes.",
                "Watch the network-type timeline for drops to 2G.",
                "Review indicators; repeated events matter more than one."
            ),
            howItWorks = "An IMSI catcher (Stingray) pretends to be a cell tower so nearby phones connect to it. Many force phones down to 2G, which has weak encryption. Signs include the area code changing while you stand still, a sudden jump in signal strength, and forced 2G downgrades.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_INFO, "LTE", "4G, current encryption"),
                HelpLegend(LegendMark.TAG_BAD, "2G", "Old, weakly encrypted network"),
                warnMark
            ),
            facts = listOf(
                HelpFact("Indicators, not proof", "Handovers at coverage edges look similar"),
                HelpFact("Location permission", "Android requires it to read cell info; nothing is stored"),
                HelpFact("Disable 2G", "Android 12+ lets you turn off 2G in network settings")
            )
        ),

        "gps_spoof_detector" to FeatureHelp(
            title = "GPS Spoof",
            description = "Cross-check GPS against cell, WiFi, sensors",
            steps = listOf(
                "Tap Start outdoors or near a window.",
                "Wait for all seven checks to report.",
                "One warning is common indoors; several together are not."
            ),
            howItWorks = "GPS signals are faint and unauthenticated, so a transmitter can feed a fake position. The detector checks whether GPS agrees with everything else the phone knows: the nearest cell tower, WiFi networks, barometric altitude, the accelerometer, and whether a mock-location app is on. Spoofing rarely fools all of them.",
            legend = listOf(passMark, warnMark, failMark),
            facts = listOf(
                HelpFact("Indoor drift", "GPS indoors can wander hundreds of metres"),
                HelpFact("No barometer", "The altitude check is skipped on phones without one"),
                HelpFact("Mock locations", "Developer options can enable fake GPS apps")
            )
        ),

        "rf_bug_sweeper" to FeatureHelp(
            title = "RF Bug Sweeper",
            description = "BLE + ultrasonic + magnetic sweep",
            steps = listOf(
                "Tap Start in the room you want to check.",
                "Move slowly around outlets, furniture and fixtures.",
                "Review flagged radio modules and anomalies."
            ),
            howItWorks = "Cheap bugs are built from common radio modules (HC-05, ESP32, nRF) that advertise recognisable names. The sweep looks for those over Bluetooth, listens for ultrasonic tones above human hearing, and watches the magnetometer for electronics hidden behind surfaces.",
            legend = listOf(failMark, warnMark, passMark),
            facts = listOf(
                HelpFact("Hobby gear is common", "Smart plugs and DIY projects use the same modules"),
                HelpFact("Microphone", "Used only during the sweep; audio is never saved")
            )
        ),

        "usb" to FeatureHelp(
            title = "USB Devices",
            description = "Inspect USB, flag BadUSB combos",
            steps = listOf(
                "Connect the device through a USB-OTG adapter.",
                "Allow access when Android asks.",
                "Check the interfaces; unplug anything flagged you don’t recognise."
            ),
            howItWorks = "Every USB device declares what it is through interface classes. A BadUSB looks like a flash drive but also declares itself a keyboard, then types commands at machine speed. A keyboard and storage in one device, with blank manufacturer and serial fields, is the classic pattern.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_WARN, "INPUT", "Keyboard or mouse interface"),
                HelpLegend(LegendMark.TAG_INFO, "STORAGE", "Mass-storage interface"),
                failMark
            ),
            facts = listOf(
                HelpFact("Android accepts keyboards", "A USB keyboard can type into an unlocked phone"),
                HelpFact("Safest action", "Unplugging stops it immediately")
            )
        ),

        "camera" to FeatureHelp(
            title = "QR Scanner",
            description = "Check codes for phishing before opening",
            steps = listOf(
                "Point the camera at a QR code or barcode.",
                "Read the verdict before doing anything.",
                "Copy the link, or open it only if it looks safe."
            ),
            howItWorks = "Phishing QR codes (quishing) hide a malicious link behind an innocent-looking square. The scanner decodes the content without opening it, then checks the link for look-alike brand names, free or abused domains, missing HTTPS, and login-style paths.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_BAD, "DANGEROUS", "Several phishing signs"),
                HelpLegend(LegendMark.TAG_WARN, "SUSPICIOUS", "One sign; check carefully"),
                HelpLegend(LegendMark.TAG_GOOD, "SAFE", "No known warning signs")
            ),
            facts = listOf(
                HelpFact("Short links hide the target", "bit.ly and similar can point anywhere"),
                HelpFact("WiFi codes", "Joining shares nothing, but check the network name")
            )
        ),

        "privacy_score" to FeatureHelp(
            title = "Privacy Score",
            description = "16+ checks on your phone’s exposure",
            steps = listOf(
                "Tap Start to run all checks.",
                "Look at Fix first; it’s sorted by impact.",
                "Tap Open to jump to the right Android setting."
            ),
            howItWorks = "The score weighs five areas by how much they expose you: WiFi security, device security, Bluetooth, network and physical settings. Each check reads a setting Android already exposes, such as Private DNS, USB debugging or the security patch date.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_BAD, "HIGH", "Fix this first"),
                HelpLegend(LegendMark.TAG_WARN, "MEDIUM", "Worth fixing soon"),
                HelpLegend(LegendMark.TAG_INFO, "LOW", "Minor or situational")
            ),
            facts = listOf(
                HelpFact("Nothing leaves the phone", "Checks and the device readout are read locally"),
                HelpFact("What any app can read", "Time zone, languages, screen, identifiers, paired names, and home-screen apps are listed beside the score. They do not change the score"),
                HelpFact("Some checks need permissions", "Skipped ones don’t lower the score")
            )
        ),

        // ── Scan & discover ──────────────────────────────────────────────────

        "wifi" to FeatureHelp(
            title = "WiFi Analyzer",
            description = "Find crowded channels and weak security",
            steps = listOf(
                "Tap Start. The scan keeps running until you tap Stop.",
                "Open Channels to see which channel is least crowded.",
                "Tap Floor walk to map the Wi-Fi this phone is joined to as you walk the room.",
                "Tap a network for its details, or flag it as trusted."
            ),
            howItWorks = "Routers announce themselves several times a second with beacons that carry their name, channel and security. In 2.4 GHz only channels 1, 6 and 11 don’t overlap, so networks crowded onto the same channel slow each other down. Security type decides who can read your traffic.",
            legend = listOf(
                HelpLegend(LegendMark.SIGNAL_STRONG, "", "Strong, about -50 dBm or better"),
                HelpLegend(LegendMark.SIGNAL_WEAK, "", "Weak, about -80 dBm"),
                HelpLegend(LegendMark.TAG_GOOD, "WPA3", "Strongest encryption"),
                HelpLegend(LegendMark.TAG_BAD, "OPEN", "No encryption, others can read traffic")
            ),
            facts = listOf(
                HelpFact("Android limits scans", "4 scans every 2 minutes per app"),
                HelpFact("Location permission", "Required by Android for WiFi results; never stored"),
                HelpFact("Hidden networks", "Shown as [hidden]; their names can’t be read"),
                HelpFact("Clients", "Cards, Compact, and Links list devices heard talking to an access point. A USB adapter hears nearby clients. The phone radio lists this phone when it is joined. Stopping the scan keeps those clients in the scan log"),
                HelpFact("Floor walk", "The camera and Play Services for AR keep a position in the room. The path uses the strength of the joined network. The picture is not saved, and the walk does not request extra Wi-Fi scans. Share image on a saved walk opens a PNG of that map")
            )
        ),

        "ble" to FeatureHelp(
            title = "BLE Scanner",
            description = "Find Bluetooth devices around you",
            steps = listOf(
                "Tap Start. Most devices appear within 10 seconds.",
                "Filter by type, or hide weak signals.",
                "Tap a device to open its GATT services or dump everything."
            ),
            howItWorks = "Bluetooth Low Energy devices advertise small packets many times a second: a name, a manufacturer ID and the services they offer. RadiusRF uses those to guess the device type. Connecting opens the GATT table, the device’s list of readable and writable values.",
            legend = listOf(strongSignal, weakSignal, HelpLegend(LegendMark.TAG_WARN, "TRACKER", "Matches a tracker signature")),
            facts = listOf(
                HelpFact("Distance is estimated", "Signal strength varies with walls and bodies; can be off by 2×"),
                HelpFact("Random addresses", "Phones and trackers rotate their address for privacy"),
                HelpFact("Nearby devices permission", "Needed by Android to scan; used only while scanning")
            )
        ),

        "bluetooth_classic" to FeatureHelp(
            title = "Bluetooth Classic",
            description = "Discovery, SDP services, SPP serial",
            steps = listOf(
                "Tap Start; discovery runs for about 12 seconds.",
                "Tap a device to list its services (SDP).",
                "Connect to a Serial Port service to open the terminal."
            ),
            howItWorks = "Classic Bluetooth (BR/EDR) is the older, higher-bandwidth side of Bluetooth used by speakers, cars and serial modules. Each device publishes its services through SDP. The Serial Port Profile (SPP) carries plain text, which is how OBD-II car adapters and HC-05 modules talk.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_GOOD, "PAIRED", "Already paired with this phone"),
                HelpLegend(LegendMark.TAG_INFO, "NEW", "Found in this discovery")
            ),
            facts = listOf(
                HelpFact("Discoverable only", "Devices must be in pairing mode to be found"),
                HelpFact("Only connect to your own devices", "Serial commands can change device settings")
            )
        ),

        "network_scanner" to FeatureHelp(
            title = "Network Scanner",
            description = "Hosts, open ports, banners on your LAN",
            steps = listOf(
                "Connect to a network you own or may test.",
                "Tap Start to find every device on it.",
                "Tap a device to see its open ports and name it."
            ),
            howItWorks = "Each device on your network has an IP address. The scanner checks every address in your subnet, then tries common ports. An open port is a service listening for connections; the banner it replies with often names the software and version, which is how outdated or exposed services are spotted.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_GOOD, "KNOWN", "You marked this device as known"),
                HelpLegend(LegendMark.TAG_INFO, "NEW", "Not seen before"),
                HelpLegend(LegendMark.TAG_WARN, "REVIEW", "Exposes a risky service")
            ),
            facts = listOf(
                HelpFact("Permission matters", "Only scan networks you own or have written permission to test"),
                HelpFact("Your LAN only", "It never scans the internet"),
                HelpFact("Announced services", "mDNS and SSDP list Chromecasts, printers, and cameras that a port scan can miss")
            )
        ),

        "connection_log" to FeatureHelp(
            title = "Connection Log",
            description = "This phone's apps, hosts, and a PCAP",
            steps = listOf(
                "Tap Start and allow the local VPN.",
                "Read the app, the host, and the bytes for each connection.",
                "Share PCAP to save the capture that is already on this phone.",
                "Tap Stop so IPv4 uses the phone's normal connection again."
            ),
            howItWorks = "The phone routes its IPv4 through a VPN that stays on the device. RadiusRF forwards each connection and writes the app, the host, and the byte counts. The same packets go into a PCAP in the app's export folder. HTTPS stays encrypted. IPv6 stays on the phone's normal connection.",
            facts = listOf(
                HelpFact("This phone only", "Other devices on the network are not included"),
                HelpFact("One VPN", "Android allows one VPN at a time, so stop any other VPN first"),
                HelpFact("PCAP limit", "The capture stops growing at about 6 MB"),
                HelpFact("Forwarded", "Apps are not blocked. Their traffic is passed through")
            )
        ),

        "ftm" to FeatureHelp(
            title = "FTM Ranging",
            description = "802.11mc distance to nearby access points",
            steps = listOf(
                "Tap Start near the access points you want to range.",
                "APs that answer 802.11mc show a metre reading.",
                "Compare that distance with the RSSI guess on Proximity Radar."
            ),
            howItWorks = "Fine Timing Measurement sends a Wi-Fi round-trip time to an AP that supports 802.11mc. The phone converts that time to metres. RSSI is not used for the distance.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_GOOD, "FTM", "The AP answered and a distance is shown"),
                HelpLegend(LegendMark.TAG_INFO, "NO FTM", "The AP does not advertise 802.11mc")
            ),
            facts = listOf(
                HelpFact("Hardware", "The phone and the AP both need Wi-Fi RTT"),
                HelpFact("Home routers", "Most consumer APs do not answer FTM")
            )
        ),

        "proximity_radar" to FeatureHelp(
            title = "Proximity Radar",
            description = "Plot nearby devices by distance",
            steps = listOf(
                "Tap Start and hold the phone still.",
                "Closer devices sit nearer the centre.",
                "Pinch the radar, or tap + and −, to zoom the range. Tap Fit to show every device again.",
                "Tap a dot for the device’s details."
            ),
            howItWorks = "Signal strength drops predictably with distance, so RSSI gives a rough range. Direction can’t be measured with a single antenna, so each device keeps a fixed angle and only its distance moves.",
            legend = listOf(strongSignal, weakSignal),
            facts = listOf(
                HelpFact("Angles are not real", "Only the distance from the centre means anything"),
                HelpFact("Bodies block signal", "Turning around can change readings by 10 dB")
            )
        ),

        "wifi_aware" to FeatureHelp(
            title = "Wi-Fi Aware",
            description = "Router-less NAN peer discovery",
            steps = listOf(
                "Pick Publish on one phone and Subscribe on another.",
                "Use the same service name on both.",
                "Send short messages once peers appear."
            ),
            howItWorks = "Wi-Fi Aware (Neighbor Awareness Networking) lets phones form a small cluster and find each other with no router or internet. Some phones can also measure distance to a peer using round-trip time (RTT).",
            facts = listOf(
                HelpFact("Both phones need it", "Wi-Fi Aware support varies by model"),
                HelpFact("Message size", "Up to 255 bytes per message"),
                HelpFact("Range", "Typically within about 30 m")
            )
        ),

        "foxhunt" to FeatureHelp(
            title = "Fox Hunt",
            description = "Find the heading where a signal is strongest",
            steps = listOf(
                "On Fox Hunt, tap Start to open the plugged-in receiver. Pause and Stop are on the same screen.",
                "Enter the watch frequency. The meter measures the background for a moment, then locks its scale for the sweep.",
                "Sweep a directional antenna slowly left to right. The fill is live level; the pale line is peak hold.",
                "Centre the antenna on the highest peak. Set Ref makes that the top of the scale so you can work either side of it. Reset Peak clears the hold for another sweep."
            ),
            howItWorks = "The meter watches one frequency. After a short background sample it freezes the scale so the stack does not jump while you turn. Peak hold keeps the strongest reading as a pale line after a burst fades. Set Ref locks that peak as the ceiling. The bearing is the compass heading at that peak — a direction from where you stand, not a location. Map and heatmap plot this phone’s path on OpenStreetMap. Import a tile archive to keep streets offline.",
            facts = listOf(
                HelpFact("Bearing, not a fix", "One line from where you stand. A second hunt from another spot crosses it"),
                HelpFact("Heat belongs to the phone", "A red patch is where this phone heard the strongest signal, not a claimed transmitter position"),
                HelpFact("Receive only", "It listens. It does not transmit"),
                HelpFact("Offline streets", "Import a tile archive from the folder button so the map works without a network")
            )
        ),
        "field_map" to FeatureHelp(
            title = "Field Map",
            description = "On-device pins for surveys, Remote ID, and bearings",
            steps = listOf(
                "Pins, Path, and List are three ways to read the same points.",
                "Turn Survey, Remote ID, and Bearing on or off.",
                "A Remote ID aircraft is red. Its takeoff, pilot, or fixed point is amber, joined by a dotted line."
            ),
            howItWorks = "Positions come from wardrive logs, OpenDroneID broadcasts, and the fox-hunt bearing already stored on the phone. Streets come from an imported OpenStreetMap archive, or from the network if you allow it. The Cameras layer pulls mapped surveillance and ALPR nodes from OpenStreetMap.",
            facts = listOf(
                HelpFact("Offline streets", "Import an .mbtiles, .zip, .sqlite, or .gemf archive from Settings or the folder button on the map"),
                HelpFact("Remote ID", "Bluetooth and Wi-Fi beacon broadcasts are shown together. Aircraft and operator locations are whatever the broadcast included"),
                HelpFact("Cameras layer", "Public OSM tags only. Unmapped cameras do not appear.")
            )
        ),

        "ism_decoder" to FeatureHelp(
            title = "ISM Decoder",
            description = "Name 315/433/868/915 MHz sensor bursts",
            steps = listOf(
                "Plug in an RTL-SDR or HackRF.",
                "Pick 315, 433, 868, or 915 MHz.",
                "Start and wait for a burst. The family is a timing match."
            ),
            howItWorks = "Short OOK and FSK bursts are pulled from the IQ stream. Pulse widths are compared with common ISM families: TPMS, doorbells, weather nodes, and alarm sensors.",
            facts = listOf(
                HelpFact("Receive only", "The dongle listens. It does not transmit."),
                HelpFact("Not a full rtl_433 dump", "A family name is timing, not a decoded sensor ID.")
            )
        ),

        "traffic_radio" to FeatureHelp(
            title = "ADS-B / AIS",
            description = "Decode nearby aircraft and ships",
            steps = listOf(
                "Plug in a dongle that can reach 1090 MHz or 162 MHz.",
                "Pick ADS-B or AIS, then Start.",
                "Aircraft need two CPR frames before a position appears."
            ),
            howItWorks = "ADS-B reads Mode S DF17 on 1090 MHz. AIS reads public A/B messages on 162 MHz. Both are broadcasts meant to be heard.",
            facts = listOf(
                HelpFact("Airspy HF+", "Cannot reach 1090 MHz"),
                HelpFact("Antenna", "A 1090 MHz or marine VHF antenna hears much farther than a stock dongle whip")
            )
        ),

        "meshtastic" to FeatureHelp(
            title = "Mesh Network Scanner",
            description = "Bluetooth companion links and LoRa mesh broadcasts",
            steps = listOf(
                "Choose Bluetooth or LoRa.",
                "Bluetooth lists the link a mesh node advertises for a phone or computer.",
                "LoRa uses a plugged-in RTL-SDR, HackRF, Airspy, or HydraSDR and hops the common mesh channels.",
                "The map marks where this phone heard each node."
            ),
            howItWorks = "Bluetooth hears companion advertisements: Meshtastic, a Meshnology node running Meshtastic, Reticulum over Bluetooth, an RNode, and a MeshCore node that puts its name in the advertisement. LoRa listens for chirp preambles. Meshtastic’s public sync word is named when it is present. Sync word 0x12 is the Semtech default used by many RNode and MeshCore setups, so those two stay on one row. The pin is this phone’s GPS fix at the moment of the hear, not a position from inside the packet.",
            facts = listOf(
                HelpFact("Hear only", "No mesh join and no transmit"),
                HelpFact("LoRa channels", "The survey dwells on 906.875, 915, 910.525, 869.525, 868.1, and 433.92 MHz"),
                HelpFact("Airspy HF+", "That receiver stops near 260 MHz, below the LoRa mesh bands"),
                HelpFact("Map", "A pin needs a location fix on this phone. A node with Bluetooth off is invisible to the Bluetooth survey")
            )
        ),

        "sdr" to FeatureHelp(
            title = "SDR Radio",
            description = "Receive console for waterfall, audio, and decoders",
            steps = listOf(
                "Plug in an RTL-SDR, HackRF, Airspy, Airspy HF+, or HydraSDR and tap Listen.",
                "Pick Audio, Scan, Glass, Aircraft, Ships, APRS, Pager, ACARS, SubGHz, FT8, Drones, or Capture.",
                "Drag the waterfall or type a frequency in MHz. A decoder app tunes itself."
            ),
            howItWorks = "The dongle samples the radio and the phone draws the waterfall, then demodulates or decodes. Audio covers WFM, NFM, AM, USB, LSB, and CW. Aircraft reads 1090 MHz Mode S. Ships reads AIS. APRS reads 1200 baud packet. Pager reads POCSAG. ACARS reads 131.550 MHz. SubGHz names short bursts. FT8 tunes a standard USB frequency and leaves the message on the waterfall. Positions from aircraft and ships plot on a local chart. Nothing is transmitted.",
            facts = listOf(
                HelpFact("Status", "Beta test. The receive console is unfinished"),
                HelpFact("Receive only", "The console tunes and listens. It does not transmit"),
                HelpFact("Same radios", "RTL-SDR, HackRF, Airspy, Airspy HF+, and HydraSDR"),
                HelpFact("Remote ID", "Drones listens for public OpenDroneID Bluetooth broadcasts")
            )
        ),

        "usb_camera" to FeatureHelp(
            title = "USB Camera",
            description = "UVC camera modes and preview",
            steps = listOf(
                "Connect the camera through a USB-OTG adapter.",
                "Allow access when Android asks.",
                "Pick a resolution to preview."
            ),
            howItWorks = "Most webcams and endoscopes follow the USB Video Class (UVC) standard, so one driver can talk to all of them. The camera lists the formats it supports; MJPEG sends compressed frames, YUYV sends raw ones.",
            facts = listOf(
                HelpFact("UVC required", "Cameras with their own drivers won’t work"),
                HelpFact("Adapter power", "Some cameras need a powered OTG hub")
            )
        ),

        // ── Analyze signals ──────────────────────────────────────────────────

        "ultrasonic" to FeatureHelp(
            title = "Ultrasonic",
            description = "18–24 kHz spectrum for beacons",
            steps = listOf(
                "Tap Start and keep the room quiet.",
                "Watch the spectrum for narrow, steady peaks.",
                "Use the tone generator to test other devices."
            ),
            howItWorks = "Some ads and apps play tones above human hearing that other devices’ microphones pick up, linking your devices together. The analyzer runs a Fast Fourier Transform (FFT) on the microphone signal to show how loud each frequency is, and flags narrow, persistent tones in the 18–24 kHz band.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_WARN, "BEACON?", "Narrow, steady tone above hearing"),
                HelpLegend(LegendMark.TAG_GOOD, "QUIET", "Nothing above the noise floor")
            ),
            facts = listOf(
                HelpFact("Audio is never saved", "Only the spectrum is kept"),
                HelpFact("Mic roll-off", "Many phone mics are deaf above 20 kHz, so quiet isn’t proof"),
                HelpFact("Innocent sources", "Chargers and old screens whine at high frequencies")
            )
        ),

        "emf_mapper" to FeatureHelp(
            title = "EMF Mapper",
            description = "Magnetic field map and hotspots",
            steps = listOf(
                "Tap Start away from metal to set a baseline.",
                "Tap a grid cell, hold the phone there, and measure.",
                "Fill the grid; hot cells show where fields are strongest."
            ),
            howItWorks = "The magnetometer measures the magnetic field in microtesla (µT). Earth’s field is about 25–65 µT; wiring, motors, speakers and magnets add to it. Mapping the difference from a baseline shows where hidden electronics or cables are.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_GOOD, "<15", "µT above baseline; normal"),
                HelpLegend(LegendMark.TAG_WARN, "15–40", "Elevated; nearby electronics"),
                HelpLegend(LegendMark.TAG_BAD, ">100", "Strong; magnet or motor very close")
            ),
            facts = listOf(
                HelpFact("Sensor position", "The magnetometer is near the top edge on most phones"),
                HelpFact("Phone cases", "Magnetic cases and mounts distort readings")
            )
        ),

        "sensors" to FeatureHelp(
            title = "Sensor Dashboard",
            description = "Motion, compass, level, metal detector",
            steps = listOf(
                "Tap Start; sensors refresh 5 times a second.",
                "Open a card for its instrument: level, compass, metal detector.",
                "Press Reset on the metal detector to set a baseline first."
            ),
            howItWorks = "Your phone carries MEMS sensors: an accelerometer measures force including gravity, a gyroscope measures rotation, a magnetometer measures magnetic fields, and a barometer measures air pressure. Combining them gives tilt, heading and even which floor you’re on.",
            facts = listOf(
                HelpFact("Auto-stop", "Stops after 60 s to save battery"),
                HelpFact("Missing sensors", "Cheaper phones often lack a barometer or gyroscope"),
                HelpFact("Calibrate the compass", "Wave the phone in a figure 8")
            )
        ),

        "gps" to FeatureHelp(
            title = "GPS Tracker",
            description = "Position, satellites, raw NMEA",
            steps = listOf(
                "Tap Start near a window or outdoors.",
                "Wait for a 3D fix (4+ satellites).",
                "Open Satellites or NMEA to see the raw data."
            ),
            howItWorks = "Your position comes from timing signals from satellites in several constellations: GPS (US), Galileo (EU), GLONASS (Russia) and BeiDou (China). With four or more, the receiver solves for latitude, longitude, altitude and time. NMEA sentences are the text format receivers use to report it.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_GOOD, "USED", "Satellite used in the fix"),
                HelpLegend(LegendMark.TAG_INFO, "SEEN", "Visible but not used")
            ),
            facts = listOf(
                HelpFact("C/N0", "Signal quality in dB-Hz; above 35 is good"),
                HelpFact("Indoors", "Accuracy drops sharply without a clear sky")
            )
        ),

        "uwb" to FeatureHelp(
            title = "UWB Radar",
            description = "FiRa ranging and AoA capability",
            steps = listOf(
                "Check what this phone’s UWB chip supports.",
                "Start ranging with a second UWB phone running RadiusRF.",
                "Watch distance and angle update live."
            ),
            howItWorks = "Ultra-wideband sends very short pulses across a wide band, so it can time their flight precisely: a nanosecond is about 30 cm. Two-way ranging gives distance to within about 10 cm, and phones with several antennas measure the Angle of Arrival too.",
            facts = listOf(
                HelpFact("Rare hardware", "Few Android phones have UWB"),
                HelpFact("AirTags", "Use Apple’s protocol, which Android can’t range")
            )
        ),

        // ── Read, write & transmit ───────────────────────────────────────────

        "nfc" to FeatureHelp(
            title = "NFC Tools",
            description = "Read, write, dump MIFARE, emulate",
            steps = listOf(
                "Hold a tag flat against the back of the phone.",
                "Keep still for a second while it reads.",
                "Use Write, MIFARE or Emulate from the tabs."
            ),
            howItWorks = "NFC works at 13.56 MHz over a few centimetres: the phone powers the tag with its field and the tag answers. Most tags store NDEF records (a URL, WiFi credentials, text). MIFARE Classic cards split memory into sectors, each protected by keys, and many still use factory-default keys.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_GOOD, "WRITABLE", "Tag can be changed"),
                HelpLegend(LegendMark.TAG_WARN, "LOCKED", "Read-only, permanently")
            ),
            facts = listOf(
                HelpFact("Range", "1–4 cm; cases can block it"),
                HelpFact("Locking is permanent", "A locked tag can never be written again"),
                HelpFact("Only clone your own cards", "Copying access cards you don’t own may be illegal")
            )
        ),

        "ir" to FeatureHelp(
            title = "IR Remote",
            description = "TV remotes, Flipper .ir import",
            steps = listOf(
                "Pick a remote, or enter a custom code.",
                "Point the top of the phone at the device.",
                "Import Flipper Zero .ir files to add more."
            ),
            howItWorks = "Infrared remotes blink an LED on and off at a carrier frequency, usually 38 kHz, in patterns that encode an address and a command. Protocols like NEC, RC5 and Sony SIRC define that pattern, which is why one blaster can speak to many brands.",
            facts = listOf(
                HelpFact("Needs an IR blaster", "Most phones since 2020 don’t have one"),
                HelpFact("Line of sight", "Range is about 3–5 m"),
                HelpFact("Carrier", "Most TVs use 38 kHz; Sony uses 40 kHz")
            )
        ),

        "wifi_direct" to FeatureHelp(
            title = "Wi-Fi Direct",
            description = "P2P groups and file transfer",
            steps = listOf(
                "Tap Start to discover nearby peers.",
                "Connect or invite a peer to form a group.",
                "Send a file once the group is formed."
            ),
            howItWorks = "Wi-Fi Direct lets devices connect without a router: one becomes the group owner and acts like a small access point, the others join as clients. Printers and screen-casting often use it.",
            facts = listOf(
                HelpFact("One group at a time", "Leave the group before joining another"),
                HelpFact("Speed", "Much faster than Bluetooth for large files")
            )
        ),

        // ── Log & map ────────────────────────────────────────────────────────

        "signal_logger" to FeatureHelp(
            title = "Signal Logger",
            description = "WiFi + BLE arrival/departure timeline",
            steps = listOf(
                "Tap Start and put the phone down.",
                "Come back later and read the timeline.",
                "Filter to anomalies to see only unusual events."
            ),
            howItWorks = "The logger records when devices appear and disappear. Patterns reveal a lot: a device that arrives whenever you do, a hidden network that suddenly gets strong, or a burst of new access points in one scan.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_INFO, "NEW", "Device appeared"),
                HelpLegend(LegendMark.TAG_WARN, "SPIKE", "Signal jumped sharply"),
                HelpLegend(LegendMark.TAG_BAD, "BURST", "Many new devices at once")
            ),
            facts = listOf(
                HelpFact("Keeps the last 500 events", "Older ones roll off"),
                HelpFact("Battery", "Uses low-power scans, but long logs still cost battery")
            )
        ),

        "wardriving" to FeatureHelp(
            title = "Wardriving",
            description = "GPS + Wi-Fi log and export",
            steps = listOf(
                "Pick the phone radio or a plugged-in USB adapter. Wi-Fi Analyzer uses the same choice.",
                "Tap Start; a notification keeps it running.",
                "Walk, cycle or drive a route.",
                "Stop, then export WiGLE, Kismet, airodump-ng, PCAPNG, or CSV."
            ),
            howItWorks = "Wardriving pairs each Wi-Fi network with the GPS fix where it was heard. A USB adapter listens for beacons on that stick. WiGLE CSV, Kismet CSV, Kismet netxml, and airodump-ng CSV open in Netseer. PCAPNG opens in Wireshark. The PCAPNG is rebuilt from the log.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_GOOD, "SECURED", "WPA2 or WPA3"),
                HelpLegend(LegendMark.TAG_WARN, "WEP", "Broken encryption"),
                HelpLegend(LegendMark.TAG_BAD, "OPEN", "No encryption")
            ),
            facts = listOf(
                HelpFact("Runs in the background", "A foreground service keeps logging with the screen off"),
                HelpFact("Passive only", "It records what networks broadcast; it never connects"),
                HelpFact("One survey at a time", "Starting another wardriving tool replaces the one that is logging"),
                HelpFact("USB adapters", "Archer T2U Plus, Alfa RTL8812AU, Alfa AWUS1900, Panda PAU0A, and MediaTek MT7612U log beacons the same way Wi-Fi Analyzer does"),
                HelpFact("Clients", "A USB adapter logs each device heard talking to an access point, with that access point and the GPS fix. The Clients list shows them, and the exports include them")
            )
        ),
        "wardriving_plus" to FeatureHelp(
            title = "Wardriving +",
            description = "GPS + Wi-Fi + Bluetooth log and export",
            steps = listOf(
                "Pick the phone radio or a plugged-in USB adapter, then grant Bluetooth along with location.",
                "Walk, cycle or drive a route.",
                "Stop, then export the same formats as Wardriving."
            ),
            howItWorks = "Logs Wi-Fi plus nearby Bluetooth LE and classic devices, each with the GPS fix. Bluetooth rows use type BLE or BT in the WiGLE file and become advertising frames in the PCAPNG.",
            facts = listOf(
                HelpFact("Same exports", "WiGLE, Kismet, airodump-ng, PCAPNG, and a plain CSV"),
                HelpFact("Clients", "A USB adapter logs devices heard talking to an access point, with the GPS fix"),
                HelpFact("Passive only", "It records advertisements and names; it does not connect")
            )
        ),
        "wardriving_plus_plus" to FeatureHelp(
            title = "Wardriving ++",
            description = "GPS + Wi-Fi + Bluetooth + cellular log and export",
            steps = listOf(
                "Pick the phone radio or a plugged-in USB adapter, then grant location, Bluetooth, and phone state.",
                "Walk, cycle or drive a route.",
                "Stop, then export. Cellular rows are in the CSV and Kismet files."
            ),
            howItWorks = "Adds the serving cell and neighbours to the Wi-Fi and Bluetooth log. Each tower is stored as MCC/MNC, area code, and cell id with the GPS fix. Netseer reads those rows from the WiGLE CSV.",
            facts = listOf(
                HelpFact("Clients", "A USB adapter logs devices heard talking to an access point, with the GPS fix"),
                HelpFact("Cell rows", "Identified as LTE, NR, GSM, or the radio the phone reports"),
                HelpFact("PCAPNG", "Wi-Fi beacons and Bluetooth advertisements. Cell sightings stay in the CSV and Kismet files")
            )
        ),
        "run_compare" to FeatureHelp(
            title = "Run Compare",
            description = "Find devices that stayed with you across saved runs",
            steps = listOf(
                "In Wardriving, log one leg and stop. Start a new run for the next leg.",
                "Open Run Compare and check two to four of those runs.",
                "Tap Compare. The map draws each leg and the devices that kept up.",
                "Use All to see every match, or one chip to see a single device.",
                "Tap a card for every sighting: time, signal, name, and GPS fix.",
                "Tap the trash button to delete a run you no longer need."
            ),
            howItWorks = "Each leg stays a wardriving run. Compare reads those logs. A match is a Wi-Fi or Bluetooth address heard on at least two selected runs, with fixes more than 150 m apart. A radio that never leaves a stop is left out. The map stays in its frame. Pinch to zoom and drag to move around the route. Dashed lines are your legs. Dots are the matching device. One chip connects that device's dots in time order.",
            legend = listOf(
                HelpLegend(LegendMark.TAG_WARN, "MATCH", "Heard on more than one leg and moved with the route"),
                HelpLegend(LegendMark.TAG_INFO, "ALL", "Every match at once"),
                HelpLegend(LegendMark.SIGNAL_STRONG, "", "Dashed line is the leg you logged")
            ),
            facts = listOf(
                HelpFact("Collection stays in Wardriving", "Run Compare does not scan. It reads runs you already logged"),
                HelpFact("150 m", "A device whose fixes all sit inside 150 m is treated as a place"),
                HelpFact("Rotating addresses", "A phone that keeps changing its Wi-Fi or Bluetooth address will not stay one match"),
                HelpFact("Your own radios", "A hotspot, watch, or headset you carry will match every leg. Open the card and check the name"),
                HelpFact("Cells", "Towers are left out. They are too coarse to say a person stayed with you"),
                HelpFact("Delete a run", "The trash button removes that saved log")
            )
        )
    )
}
