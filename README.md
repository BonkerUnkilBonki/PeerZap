# PeerZap

A standalone nearby file-sharing app for Android. Send and receive files directly between phones on the same network, with no account, no server and no internet connection required.

A Quick Settings tile opens a frosted-glass bottom panel so you can send or receive without opening the app, and a built-in file manager lets you browse everything on the device.

> **Naming note:** the app is shown as **PeerZap** (`com.peerzap`). Some older docs in this repo (`INSTALL.md`, `PROGRESS.md`) still call it **Quick Share**, its earlier name.

> **Status:** version 1.71 (build 73). The build is signed and installable. The development notes record on-device testing against a Samsung SM-S938B for the Google Quick Share compatibility feature; a completed file transfer with a stock Quick Share device is still listed as unverified in `QUICKSHARE-INTEROP.md`.

---

## Features

- **Nearby discovery** over the local network, with a live device list that drops stale devices after 9 seconds.
- **Send and receive** any file type, multiple files per transfer, with an accept / decline prompt on the receiving side.
- **Quick Settings tile and panel**: send or receive without opening the app. The tile shows whether your device is currently visible.
- **Sharesheet integration**: choose PeerZap from any app's Share menu and the panel opens with your files already queued.
- **Built-in file manager** with twelve categories: Recent, Images, Documents, Audio, Downloads, Screenshots, Apps, APK, Archive, Files, Folder and Contacts. SD cards are supported.
- **Live progress** in the app, in the panel and in a notification (percentage, size, speed, ETA). On Android 16 the notification is eligible to become a Live Update.
- **Transfer history** (newest 200), cancellation, and clear states for completed / failed / cancelled / rejected.
- **Theming**: Light, Dark and Pitch black themes, accent colours, glow, an optional glass effect, and four switchable launcher icons.
- **Google Quick Share compatibility** (experimental, Wi-Fi LAN only): a reverse-engineered implementation that lets stock Quick Share devices find and send to this app, and lets this app list stock devices.
- **No background mode**: closing the panel or leaving the app stops everything. The only exception is a transfer still in flight, which is kept alive until it finishes and then shuts itself down.

## Requirements

- Android **7.0 (API 24)** or newer
- Both devices on the **same Wi-Fi network** (same VLAN, with client isolation off). A phone hotspot works well if your network blocks device-to-device traffic.

## Install

1. Copy `QuickShare.apk` to your phone.
2. Open it and allow installs from that source if Android asks (Settings > Apps > Special access > Install unknown apps).
3. Tap **Install**.

The APK is signed with a debug key. If Android refuses to update an older build, uninstall the old one first.

To add the tile: open Quick Settings, tap the edit (pencil) button, and drag the PeerZap tile into your active tiles.

See [`INSTALL.md`](INSTALL.md) for the full walkthrough, a two-phone test procedure and troubleshooting.

## Permissions

| Permission | Why |
|---|---|
| Notifications | Incoming-file alerts and transfer progress |
| Nearby devices (Android 13+) / Location (Android 12 and below) | Local-network device discovery only. Never used to derive location |
| Media (images, video, audio) / storage | Reading files you choose to send |
| All files access | The built-in file manager (sideload only, see below) |
| Contacts | The Contacts category in the file manager |
| Display over other apps | Bringing the panel to the front when a transfer arrives |
| Foreground service (data sync, connected device) | Keeping an active transfer or the compatibility receiver alive |
| Install unknown apps | Handing a downloaded update to the Android installer |

"All files access" (`MANAGE_EXTERNAL_STORAGE`) is not granted by Google Play to general file managers, so this build is intended to be installed directly rather than through the Play Store.

## How it works

PeerZap has two transfer paths.

**1. PeerZap to PeerZap (native protocol)**

| Port | Protocol | Purpose |
|---|---|---|
| 8890 | UDP | Discovery beacons, broadcast about every 2.2 seconds with unicast replies |
| 8891 | TCP | File transfers |

The receiver runs a TCP server. The sender connects, sends a JSON `offer`, waits for `accept` or `reject`, then streams each file as a JSON header line followed by exactly `size` raw bytes, and finishes with `done`. Files are streamed in 64 KB chunks and never held whole in memory. Full details are in [`PROTOCOL.md`](PROTOCOL.md).

**2. Google Quick Share compatibility (experimental)**

Advertises and browses the `_FC9F5ED42C8A._tcp.` mDNS service and speaks the reverse-engineered Quick Share protocol (UKEY2 handshake, encrypted payload frames). This is an independent reimplementation, **not** an official integration, and it is **not affiliated with Google or Samsung**. Wi-Fi LAN only; Bluetooth, BLE and Google-server paths are not supported. See [`QUICKSHARE-INTEROP.md`](QUICKSHARE-INTEROP.md) for the protocol notes and the full field-report history.

## Project layout

```
quickshare/
├── AndroidManifest.xml
├── build_apk.sh                 # no-Gradle APK build script
├── assets/www/                  # WebView UI
│   ├── index.html, app.js       # main app
│   ├── panel.html, panel.js     # Quick Settings bottom panel
│   ├── files.js                 # file manager UI
│   └── app.css                  # shared design system, fonts, images
├── res/                         # manifest resources, icons, animations
├── src/com/peerzap/             # native Android engine (Java)
│   ├── MainActivity, SharePanelActivity, QuickShareTileService
│   ├── QsBridge                 # JavaScript <-> Java bridge
│   ├── Discovery, TransferManager, TransferService
│   ├── Files, FileUtil, ReceivedFileProvider, Store, Notifier
│   ├── Qs*                      # Quick Share discovery, browsing, Wi-Fi Direct, receive service
│   └── qs/                      # pure-Java Quick Share protocol core
│       └── Proto, Hkdf, Ukey2, D2DKeys, SecureMessage,
│           SecureChannel, QsFrames, QsReceiver, QsSender
├── PROTOCOL.md                  # native wire protocol
├── INSTALL.md                   # install, usage, troubleshooting
├── QUICKSHARE-INTEROP.md        # Google Quick Share interop notes
└── PROGRESS.md                  # development log
```

The UI is HTML, CSS and JavaScript running in a WebView, talking to a native Java engine through a small bridge. The `qs/` package has no Android imports, so it can be unit-tested on a plain JVM.

## Building from source

The project builds without Gradle, using `aapt2`, `javac`, `d8`, `zipalign` and `apksigner` directly.

```bash
bash build_apk.sh <project_dir> <output_apk>
# for example
bash build_apk.sh . QuickShare.apk
```

You need JDK 17 and the Android SDK build-tools and a platform `android.jar`. **`build_apk.sh` currently hard-codes the original author's environment** (`/scratch/work/sdk/env.sh`, build-tools `34.0.0`, `android-36`), so edit the `BT` and `AJ` paths and the `env.sh` line to match your own SDK before running it. The script generates a debug keystore on first run if one is not present.

The build refuses to package an APK if any source file fails to compile.

## Known limitations

- Received files are saved to `Download/QuickShare`.
- Some guest or public Wi-Fi networks block phone-to-phone traffic (client isolation).
- Very aggressive OEM battery savers can still kill the process and cut a transfer short.
- A transfer whose declared size is wrong will stall on the receiver; use Cancel to recover.
- Live Update promotion on Android 16 is the system's decision.
- Google Quick Share compatibility is experimental and not guaranteed to work with every device or version.

## Credits

Built by BonkerUnkil (Bonki), [GitHub](https://github.com/BonkerUnkilBonki) · [Telegram](https://t.me/BonkerUnkilBonki). The UI design language is based on the original Gitly source.

The Quick Share protocol work builds on public reverse-engineering by the community, notably [NearDrop](https://github.com/grishka/NearDrop), [rquickshare](https://github.com/Martichou/rquickshare) and [Bada](https://github.com/kyujin-cho/Bada).

## Disclaimer

PeerZap is an independent project and is not affiliated with, endorsed by, or sponsored by Google or Samsung. "Quick Share" is a trademark of its respective owners.

## License

No license file is included in this source archive. Add one (for example MIT or Apache-2.0) before publishing.
