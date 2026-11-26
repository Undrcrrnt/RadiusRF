# RadiusRF — Permissions

RadiusRF requests permissions **only when you open a feature that needs them** — nothing at startup.
For the project overview, screenshots, and setup, see the [main README](../README.md).

[← Back to README](../README.md)

---

| Permission | Required By | Why |
|-----------|-------------|-----|
| `BLUETOOTH_SCAN` | BLE Scanner, Tracker Scanner, RF Bug Sweeper, Proximity Radar, Hidden Camera, Privacy Score | Discover Bluetooth devices |
| `BLUETOOTH_CONNECT` | BLE Scanner (GATT), Bluetooth Classic, NFC (HCE), RF Bug Sweeper | Connect to devices |
| `ACCESS_FINE_LOCATION` | WiFi Analyzer, GPS Tracker, Wardriving, Cell Tower, Rogue AP, Proximity Radar, GPS Spoof, Hidden Camera | Android requires location for WiFi/BLE scanning |
| `ACCESS_COARSE_LOCATION` | Fallback for location-based features | Approximate location |
| `ACCESS_WIFI_STATE` | WiFi Analyzer, Network Scanner, Deauth Detector | Read WiFi scan results |
| `CHANGE_WIFI_STATE` | WiFi Analyzer | Trigger WiFi scans |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct (API 33+) | Discover WiFi Direct peers |
| `CAMERA` | QR Scanner, Hidden Camera, Floor walk | Camera for a code, a room check, or the walk. Floor-walk frames are not saved |
| `NFC` | NFC Tools | Read/write NFC tags |
| `TRANSMIT_IR` | IR Remote | Send infrared commands |
| `READ_PHONE_STATE` | Cell Tower, GPS Spoof Detector | Access cell tower data |
| `RECORD_AUDIO` | Ultrasonic Analyzer, RF Bug Sweeper, Privacy Score | Microphone for ultrasonic detection |
| `INTERNET` | Field Map, wardriving maps | Download map tiles when online maps are on |
| `FOREGROUND_SERVICE` | Wardriving, Connection Log, scans that stay up | A notification while that work is running. This is not a background-location permission |
| `FOREGROUND_SERVICE_LOCATION` | Wardriving and survey watches | Location while the notification is showing |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | USB receivers and companion radios | Keep a connected receiver session |
| `FOREGROUND_SERVICE_MICROPHONE` | Ultrasonic listen that stays up | Microphone while that listen is running |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Connection Log | The VPN interface that observes this phone’s IPv4. It is not a remote VPN |
| `POST_NOTIFICATIONS` | Those foreground services (API 33+) | The notification itself |
| `UWB_RANGING` | UWB Radar | Ultra-wideband distance ranging (API 31+) |

There is no background-location permission and no permission to list every app on the phone. Connection Log names an app from the packet’s user id when Android allows that lookup. Hardware features are optional, so a phone without NFC, UWB, or an IR blaster can still install the app.
