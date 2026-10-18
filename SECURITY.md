# Security Policy

RadiusRF is a security research and RF analysis toolkit — we take vulnerabilities in the app
itself seriously, separately from the fact that the app's *purpose* is finding vulnerabilities
in wireless environments around the user.

## Scope

**In scope** — vulnerabilities in RadiusRF's own code, for example:

- A flaw in RadiusRF that could leak data it collects (scan results, tag history, GPS logs) to
  another app or over the network
- A permission or IPC issue that lets another app on the device trigger RadiusRF's scanning,
  transmission (IR/NFC HCE), or ranging behavior without user interaction
- Memory-safety or injection issues in parsers that handle untrusted input (NFC tag payloads,
  BLE GATT characteristic bytes, QR code content, `.ir` file imports, HCI snoop logs)
- Room database or SharedPreferences data stored insecurely

**Out of scope** — these are inherent to what the app does, not bugs in it:

- "RadiusRF can detect/scan X" — that's the intended function of a security toolkit
- False positives/negatives in heuristic detectors (Rogue AP, GPS Spoof, Hidden Camera, etc.) —
  please file these as regular [bug reports](.github/ISSUE_TEMPLATE/bug_report.yml) instead,
  they're accuracy issues, not security vulnerabilities
- Misuse of the app by a user against networks/devices they don't own or have permission to
  test — see the [Disclaimer](README.md#-disclaimer)

## Reporting a vulnerability

**Please do not open a public issue or pull request for a security vulnerability.**

Report privately via
[GitHub Security Advisories](https://github.com/Undrcrrnt/RadiusRF/security/advisories/new).
This is the preferred channel and is private by default.

Please include:

- A description of the issue and its potential impact
- Steps to reproduce (device model, Android version, RadiusRF version/commit)
- Any relevant logs or proof-of-concept

## What to expect

This is a solo-maintained open-source project, so response times aren't guaranteed, but
security reports are prioritized over feature work. You'll get an acknowledgment, and a fix
timeline once the issue is confirmed. Credit will be given in the release notes unless you
prefer to stay anonymous.

## Supported versions

Only the latest commit on `main` is supported. There are no tagged/versioned releases with
ongoing security maintenance at this time.
