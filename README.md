# PeerZap

**PeerZap** is a high-performance, open-source, standalone local file-sharing application for Android. It enables fast, direct file transfers between devices on the same local network without requiring an active internet connection, user accounts, cloud storage, or third-party servers.

---

## Key Features

- **Instant Local Discovery:** Automatically discovers nearby devices on the local Wi-Fi network using a lightweight UDP beacon protocol (drops stale devices after 9 seconds).
- **Zero-Configuration Transfers:** Send and receive files of any size or type with live accept/decline prompts.
- **Quick Settings Integration:** Access a frosted-glass bottom panel directly from the Quick Settings tile or device sharesheet without needing to open the main app.
- **Advanced File Manager:** Features twelve distinct browsing categories: Recent, Images, Documents, Audio, Downloads, Screenshots, Apps, APK, Archive, Files, Folder, and Contacts (with full SD card and external storage support).
- **Real-Time Progress & Live Updates:** Track live transfer metrics (percentage, transferred size, transfer speed, and ETA) inside the app, within the Quick Settings panel, via system notifications, and as native **Live Updates** on Android 16+.
- **Flexible Customization:** Choose between Light, Dark, and Pitch Black themes, custom accent colors, glow effects, frosted glass styling, and selectable launcher icons.
- **Google Quick Share Interoperability (Experimental):** Includes a reverse-engineered mDNS and UKEY2/cryptographic frame engine enabling interoperability with stock Google Quick Share receivers on the local network.
- **Privacy First & Lightweight:** No background services run continuously; the application terminates immediately upon task completion, preserving battery and system resources.

---

## System Requirements

- **Android Version:** Android 7.0 (API 24) or newer.
- **Network:** Both devices must be connected to the same Wi-Fi network (same subnet / VLAN, with client isolation disabled). Wi-Fi hotspots are fully supported when network isolation is active.

---

## Installation

1. Copy `QuickShare.apk` (or the built PeerZap APK) to your Android device.
2. Open the file and permit installation from unknown sources when prompted.
3. Tap **Install**.

*Note: The APK is signed with a debug key. If updating from a previous version, uninstall the existing build first.*

To add the Quick Settings tile:
1. Open the Quick Settings panel and tap the edit (pencil) icon.
2. Drag the **PeerZap** tile into your active tiles.

---

## Project Architecture & Layout

PeerZap is structured around a high-performance native Java backend communicating with a modern HTML/CSS/JS frontend via a secure JavaScript bridge (`QsBridge`):

```
├── AndroidManifest.xml
├── build_apk.sh                 # Zero-Gradle custom build script (aapt2, javac, d8, apksigner)
├── assets/www/                  # WebView User Interface
│   ├── index.html, app.js       # Main application client
│   ├── panel.html, panel.js     # Quick Settings bottom panel
│   ├── files.js                 # Built-in file manager UI
│   └── app.css                  # Shared design system & styling
├── res/                         # Android resources, icons, and animations
├── src/com/peerzap/             # Native Java Android Engine
│   ├── MainActivity, SharePanelActivity, QuickShareTileService
│   ├── QsBridge                 # Bidirectional JS <-> Java bridge
│   ├── Discovery, TransferManager, TransferService
│   ├── Files, FileUtil, ReceivedFileProvider, Store, Notifier
│   ├── Qs*                      # Quick Share discovery, mDNS browsing, Wi-Fi Direct & receive service
│   └── qs/                      # Pure-Java Quick Share protocol core (independent of Android SDK)
│       └── Proto, Hkdf, Ukey2, D2DKeys, SecureMessage,
│           SecureChannel, QsFrames, QsReceiver, QsSender
├── PROTOCOL.md                  # Native wire protocol specification
├── INSTALL.md                   # Detailed installation & usage guide
├── QUICKSHARE-INTEROP.md        # Google Quick Share reverse-engineering notes
└── PROGRESS.md                  # Development log & testing history
```

---

## Wire Protocol & Networking

### 1. Native Peer-to-Peer Protocol
- **UDP Port 8890:** Discovery broadcasts (sent every ~2.2s with unicast responses).
- **TCP Port 8891:** High-speed streaming file transfers.
- **Transfer Mechanism:** The receiver runs an asynchronous TCP server. The sender initiates a connection, transmits a JSON `offer` object, awaits an `accept` or `reject` response, and streams files in optimized 64 KB chunks preceded by a JSON header.

### 2. Google Quick Share Compatibility Layer (Experimental)
Advertises and browses `_FC9F5ED42C8A._tcp.` mDNS services, implementing cryptographic handshakes (`UKEY2`, HKDF key derivation) and secure message framing to communicate with stock Quick Share devices over Wi-Fi LAN.

---

## Building from Source

PeerZap builds entirely without Gradle using standard Android SDK build-tools (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`):

```bash
bash build_apk.sh <project_dir> <output_apk>
```

*Prerequisites:* JDK 17, Android SDK Build-Tools, and a valid platform `android.jar`. Ensure tool paths in `build_apk.sh` are configured to match your local SDK installation.

---

## Documentation

- [`INSTALL.md`](INSTALL.md) — Comprehensive setup, usage instructions, and troubleshooting steps.
- [`PROTOCOL.md`](PROTOCOL.md) — Technical specification of the native network protocol.
- [`QUICKSHARE-INTEROP.md`](QUICKSHARE-INTEROP.md) — Field reports and protocol analysis for Google Quick Share interop.
- [`PROGRESS.md`](PROGRESS.md) — Full development history and feature logs.

---

## Disclaimer

PeerZap is an independent open-source project and is not affiliated with, endorsed by, or sponsored by Google LLC or Samsung Electronics Co., Ltd. "Quick Share" is a trademark of its respective owners.

---

## Credits

Developed by **BonkerUnkil (Bonki)** · [GitHub](https://github.com/BonkerUnkilBonki) · [Telegram](https://t.me/BonkerUnkilBonki).  
The design system draws inspiration from Gitly. Quick Share interop research builds upon community reverse-engineering efforts including [NearDrop](https://github.com/grishka/NearDrop), [rquickshare](https://github.com/Martichou/rquickshare), and [Bada](https://github.com/kyujin-cho/Bada).
