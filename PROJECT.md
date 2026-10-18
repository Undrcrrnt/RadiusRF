# RadiusRF

Receive-only wireless and RF survey app. The layout and the full tool list are unchanged.

Installed name: **RadiusRF**. Package id: `app.radiusrf`. Short label, where a field cannot take a space: **RadiusRF**.

The green dashed-ring mark is the Windows folder icon (`RadiusRf.ico`) and the Android launcher icon.

## What is in this folder

- User-visible names say RadiusRF. Class names such as `RadiusRfApp` and `RadiusRfScreen` follow that.
- The Java package is `app.radiusrf`, the same as the install id.
- Empty Set’s receive-only drivers are in this tree (`third_party`, `app/src/main/cpp`, `com.emptyset.detector`). Transmit examples were not copied. PAU0B is not offered.
- No tool, screen, or capability from the base app has been deleted.

## The app as it stands

RadiusRF opens to a dark terminal-style home screen, a tool list, alerts, sessions, and a room sweep. Scans start when a tool is opened. Permissions are asked per tool. The first launch still requires the responsible-use agreement.

The tools below are all still present. “Works” means the phone API behind it does the thing the screen claims. “Hint only” means the screen runs, but the threat label is stronger than the data.

### Wireless

| Tool | Status |
|---|---|
| WiFi Analyzer | Works as a scan list: SSID, BSSID, channel, RSSI, open/WEP/WPA. Updates at Android’s scan rate. |
| BLE Scanner | Works. Advertisements, a GATT browser, a rough distance guess from RSSI. |
| NFC Tools | Works on phones with NFC: NDEF read, MIFARE Classic dump where the card allows it, host-card emulation. |
| Bluetooth Classic | Works for devices that are discoverable. Headphones that are already paired and not discoverable stay hidden. This is an Android limit. |
| Wi-Fi Aware | Works only on phones that implement NAN. |
| Wi-Fi Direct | Works for peer discovery and a direct file transfer on phones that support P2P. |

### RF and signals

| Tool | Status |
|---|---|
| IR Remote | Works on phones with a built-in IR blaster. Samsung, LG, and Sony code sets, plus Flipper `.ir` import. A phone with no IR LED cannot transmit. |
| UWB Radar | A capability check and ranging only if the phone has a FiRa chip and a second UWB phone is nearby. Most phones will show that the hardware is absent. |
| SDR Radio | Sees a plugged-in RTL-SDR, HackRF, AirSpy, or SDRplay by USB id. It does not tune or demodulate. The screen already says that. |
| Ultrasonic Analyzer | Uses the microphone and an FFT for energy around 18–24 kHz. A tone in that band is a hint, not proof of a tracking beacon. |

### Sensors

| Tool | Status |
|---|---|
| Sensor Dashboard | Works for whatever sensors the phone actually has. |
| QR Scanner | Works. Flags odd URLs before opening them. |
| USB Camera | Lists a UVC camera on USB. Preview depends on the phone and the camera. |
| GPS Tracker | Works: fix, satellites, NMEA. |
| EMF Mapper | Magnetometer map. Useful for finding a magnet or a speaker. It is not a bug detector by itself. |

### Network

| Tool | Status |
|---|---|
| USB Devices | Lists USB devices and flags a device that claims to be both storage and a keyboard. That pattern is a real BadUSB hint. It does not prove intent. |
| Cell Tower Analyzer | Shows the registered cell and neighbors. The “IMSI catcher” alerts are not that. A location-area change is labeled “without movement” even though the code never checks movement. A loud signal jump and a fall back to 2G are noted. Driving through town will raise them. |
| Wardriving | Logs Wi-Fi plus GPS and can write a WiGLE CSV. The log stays on the phone until you export it. |

### Security

| Tool | Status |
|---|---|
| Hidden Camera Detector | OUI and name guesses, a magnetometer bump, an infrared camera view, and connection attempts to common camera ports on the network you are joined to. A TP-Link router or a laptop speaker can score. |
| GPS Spoof Detector | Cross-checks GPS against other sensors. A real test needs a spoofed fix; normal jitter can disagree. |
| Tracker Scanner | Name and a couple of service UUIDs. The Apple manufacturer check returns unknown. An AirTag does not advertise the word “AirTag”, so the common case is missed. “Tile” in a device name can false-alarm. |
| Rogue AP Detector | Same SSID on two BSSIDs, an open network with a familiar name, a short name edit, a loud hidden SSID. Hotel “Guest” radios and mesh systems trip it. |
| Network Scanner | Probes hosts on the joined subnet. Keep this for networks you own. It is not a substitute for a wireless survey. |
| RF Bug Sweeper | BLE names such as ESP32, “record”, or “beacon”, plus a magnetometer bump. A development board looks like a bug. |
| Proximity Radar | Plots RSSI as if it were distance. Same caveat as every phone hunt: loudness, not a bearing. |
| Privacy Score | A checklist of phone settings. Fine as a checklist. |
| Deauth Detector | Does not see deauthentication frames. It treats a dropped association, a missing access point, a reconnect, or a channel change as an attack. |
| Signal Logger | A timeline of Wi-Fi and BLE arrivals. This part is real and worth keeping. |
| Alert Center | Stores whatever the other tools raise. The feed is only as good as those tools. |

## Upgrades to add

These are additions. They do not require deleting a screen.

### From Fieldwatch Plus

Bring over the parts that make a radio list useful:

- One observation list for Wi-Fi and BLE, with an editable signature file, instead of each tool keeping its own scan.
- Hunt on a radio you already selected. Bluetooth stays the fast hunt. Wi-Fi compares one fresh sweep with the previous sweep and shows the locked BSSID and channel. The phone still cannot park its own Wi-Fi chip on that channel.
- Wording that calls a match a hypothesis.

Fieldwatch’s catalog and hunt math can live beside the current Wi-Fi Analyzer and BLE Scanner. Those two screens stay.

### From Empty Set, receive-only

Empty Set is the deauth tool that actually works, because Android will not hand management frames to an app from the phone’s own Wi-Fi chip. The capture path opens a USB adapter, loads firmware, hops channels, and reports deauthentication and disassociation frames. It does not transmit.

Adapters to include:

- TP-Link Archer T2U Plus (RTL8821AU, USB id `2357:0120`)
- Panda PAU0A (MT7610U, USB id `0e8d:7610`)

Panda PAU0B is left out. It can be added later if you want it. The phone must be arm64 and must be in USB host mode. A powered hub matters if the stick browns out.

This replaces the behavior of the existing Deauth Detector. The screen name stays. Alerts are frames that were heard on the Archer T2U Plus or the Panda PAU0A. The phone does not transmit.

### Other external hardware

- **SDR.** Detection stays. A real tuner is a later tool, not a rename of the current screen. The maintained Android listener for an RTL-SDR is [RF Analyzer](https://github.com/demantz/RFAnalyzer) (`com.mantz_it.rfanalyzer` on F-Droid). HackRF and AirSpy need their own host code; RF Analyzer does not cover every dongle this app already recognizes.
- **Infrared.** The built-in blaster path stays. For phones with no IR LED, [android-ir-blaster](https://github.com/iodn/android-ir-blaster) already does three outputs this app only partly covers: the built-in emitter, a USB IR dongle (vendor `0x10C4` or `0x045E`, product `0x8468`), and an audio-to-IR LED. It also imports Flipper `.ir`, IRPLUS, and LIRC files. That is the add, not a replacement of IR Remote.
- **More Wi-Fi cards.** Not in this pass. New cards need a receive-only driver each. They get added only when there is a driver that does not transmit.

## Research before any removal

These are the tools whose labels overshoot what they do, and the projects that actually do the job. Nothing here has been deleted. If a tool is retired, the screen can stay as a placeholder that says what will replace it.

| Current tool | What would make it true | Source |
|---|---|---|
| Deauth Detector | Read deauth and disassoc frames on a USB adapter | Empty Set, already on this PC. Stock Android has no other API for this. |
| Tracker Scanner | Parse Find My advertisements and alert only if the same tracker shows up in more than one place | [AirGuard](https://github.com/seemoo-lab/AirGuard) (SEEMOO, Apache-2.0). It keeps locations on the phone and can play a sound on a supported AirTag. |
| Cell Tower “IMSI” alerts | Baseband diagnostics, not the public cell-info API | [SnoopSnitch](https://github.com/srlabs/snoopsnitch) does this on a narrow set of Qualcomm phones via the DIAG interface. [AIMSICD](https://github.com/CellularPrivacy/Android-IMSI-Catcher-Detector) is the older public-API approach and has the same false alarms as the current screen. Neither is a drop-in for every phone. |
| SDR Radio | Demodulate, or say “dongle check” | RF Analyzer for RTL-SDR. The current screen can stay as the dongle check. |
| Hidden camera / RF bug name lists | A signature file you can edit. Hits stay unlabeled as guesses. | Fieldwatch’s catalog. Magnetometer hits stay labeled as metal. Both screens stay as they are until a later pass. |
| Rogue AP | Keep the same-SSID check, and stop calling a mesh or a hotel guest network an attack by itself | No outside project fixes this inside `WifiManager` scan results. The upgrade is stricter wording and a signature, not a new radio. |

## Decisions recorded

1. **Deauth Detector.** Keep the screen. Replace the phone-only connection guess with Empty Set’s receive-only capture on the TP-Link Archer T2U Plus and the Panda PAU0A. No transmit. PAU0B is not listed.
2. **Tracker Scanner.** Keep the name. Replace the name matcher with AirGuard-style Find My matching: parse the advertisement and alert when the same tracker is seen again in another place.
3. **Cell Tower.** Keep it as a survey of towers the phone can already see. Identity, channel, signal, neighbours. Rayhunter is not ported into this app. See the note below.
4. **SDR Radio.** Stays a dongle check. A full spectrum tool stays a separate app. Forsyte and RF Analyzer are the references, not code to copy into this screen.
5. **IR Remote.** Left as the built-in blaster. No USB or audio infrared.
6. **Hidden camera and RF bug sweeper.** Both stay. No hit is labeled as a guess. Revisit later.

Deauth Detector starts a receive-only capture on a T2U Plus or PAU0A and lists deauth and disassoc frames. Tracker Scanner matches Find My type `0x12`, Tile’s service UUID, and Chipolo’s company ID, and marks a device after it is heard in 3 places. Neighbour cells expand to the same fields as the registered cell. SDR and infrared are unchanged.

Notices for included software are in `NOTICE`.
