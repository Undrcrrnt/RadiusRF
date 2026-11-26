# Privacy

RadiusRF does not create an account and does not upload surveys.

## What stays on the phone

Wi-Fi, Bluetooth, cell, GPS, and sensor readings stay in the app. So do floor-walk records, the connection log, and the PCAP that log writes. Saved maps and exports stay until you delete them or share them.

The privacy score can show identifiers the phone already has, such as the Android ID and the advertising ID. Those values are shown on the device. They are not sent to a RadiusRF server.

## What leaves the phone

- Field Map and wardriving can download map tiles when online maps are on.
- Floor walk uses Google Play Services for AR to keep a position. The camera picture is shown during the walk. RadiusRF does not save or upload those frames.
- **Share image** and **Share PCAP** open the Android share sheet. The file goes only where you send it.

## Permissions

Location is used while a tool that needs it is open. The app does not request background location.

The camera is used for the QR scanner, hidden-camera checks, and floor walk. The microphone is used when you start an ultrasonic listen. Phone state is used to read the serving cell.

Connection Log uses Android’s VPN interface so the app can see this phone’s own packets. That interface is used only to observe and forward IPv4. It is not a remote VPN. RadiusRF does not encrypt a tunnel and does not send the traffic to a server. HTTPS stays encrypted by the apps that opened it. IPv6 stays on the phone’s normal connection. Android allows one VPN at a time, so another VPN has to be stopped first.

## Contact

Questions about this note can go through a private [GitHub Security Advisory](https://github.com/Undrcrrnt/RadiusRF/security/advisories/new), as described in [SECURITY.md](SECURITY.md).
