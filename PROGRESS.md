# Quick Share — progress document

A standalone nearby file-sharing client for Android, built on the UI/UX of the
original Gitly source (same One UI-inspired design language), with a new native
sharing engine behind it. A Quick Settings tile opens a bottom panel that sends
and receives files without opening the app, and a built-in file manager browses
everything on the device.

- **Package:** `com.quickshare`
- **Version:** 1.71 (versionCode 73)
- **Min / target / compile SDK:** 24 / 34 / 36
- **Artifact:** `QuickShare.apk` (signed, installable)
- **Workspace:** `/scratch/work/quickshare` (project), `/scratch/work/sdk` (toolchain)

---

## 1. What was done

| Area | Status |
|---|---|
| Toolchain (JDK 17 + build-tools 36 + platform-36) downloaded and wired | Done |
| Original Gitly source built unchanged (baseline sanity build) | Done |
| New project created from that source, UI/UX reused verbatim | Done |
| Native discovery + transfer engine implemented | Done |
| Web UI wired to the engine (Share / Devices / Transfers / Files / Settings) | Done |
| Quick Settings tile + bottom panel (v1.1) | Done |
| Sharesheet opens the panel (v1.2) | Done |
| Motion pass, black share hero, accent pulse, panel theming, live progress (v1.3) | Done |
| Built-in file manager with all-files access (v1.4) | Done |
| Plain icons, panel X closes the app, transfer keep-alive + auto-shutdown (v1.5) | Done |
| In-panel file picker: "Choose files" opens the app's own browser (v1.6) | Done |
| SD card support, no-background rule, glass panels (v1.7) | Done |
| Panel fixes: badge icon size, smaller tick circles, smooth tab switch (v1.8) | Done |
| Glass made much more opaque + a "Glass effect" toggle (v1.9) | Done |
| Panel shows live send/receive percentage; missing-class bug fixed (v1.10) | Done |
| Motion pass: sliding tab pill, staggered rows, ripples, skeletons, GPU hints (v1.11) | Done |
| Removed the file-picked toast and the "page refresh" re-entrance (v1.12) | Done |
| Glass-effect toggle moved out of the panel into app Settings (v1.13) | Done |
| APK built, signed and verified | Done |
| Protocol framing unit-tested (loopback, SHA-256 verified) | Done |
| UI, panel and file manager rendered headlessly and checked for layout errors | Done |
| Runtime test on a real device / emulator | **Not done** (no device available in this environment) |

## 2. Quick Settings tile and panel

- `QuickShareTileService` opens the panel with `startActivityAndCollapse`; the
  tile reflects whether the device is discoverable.
- `SharePanelActivity` is a transparent, recents-excluded activity; the scrim and
  bottom sheet are drawn by the page, so it slides up over whatever app you were in.
- The panel has a **Send / Receive** switch and inline Accept / Decline, and it
  starts the same engine, so it works even if the app was never opened.
- **Sharing from any app opens the panel** — the sharesheet filters live on the
  panel activity, with the shared files already queued.
- **v1.5:** the *Open app* / *Close* footer was removed. The **X in the header is
  the only control that dismisses the panel, and dismissing it closes the app**
  (see the background rules in section 5).
- **v1.8:** three panel fixes reported from a screen recording. The pending-files
  badge's icon had no width/height, so it rendered at ~50 px and made the badge
  64 px tall - it is now a 16 px glyph. The picker's selection circles are smaller
  (21 px) with tighter rows. And the **Send / Receive tab strip and the category
  chips are now static elements** that only change their `on` state, instead of
  being rebuilt on every render - which is what made switching tabs jump and
  flicker; the body below them cross-fades and keeps a minimum height.
- **v1.9:** the glass was too transparent - app icons on the home screen showed
  through the panel and cluttered it. The panel and sheets are now ~93-95% opaque
  (they keep the blur, just not the see-through), and a new **"Glass effect"**
  switch turns the frosted look off entirely for a solid surface. The switch is in
  the panel's Receive tab and in Settings > Appearance.
- **v1.10:** the panel now shows a **live percentage for a running transfer** in both
  Send and Receive - a circular percent, direction and peer, file count,
  transferred/total size and speed/ETA - updated in place so the ring eases rather
  than being rebuilt.
- **v1.11:** a motion pass across both surfaces. The panel's **Send / Receive tab now
  has a sliding pill** that glides between the two options instead of a background
  snapping on and off; list rows in the panel and the file manager **stagger in**;
  buttons get a **soft press ripple**; switches have a springier knob; screen
  changes in the app **slide directionally** (deeper slides in from the right,
  going back from the left); loading lists show **shimmer skeleton rows** instead
  of a bare spinner; and the animated bits (progress rings/bars, the pill, the
  live dot) carry `will-change`/`translateZ` hints with `contain` on the lists so
  the work stays on the compositor.
- **v1.12:** two things removed after watching a recording. The **"N file(s) ready"
  toast** that popped up in the panel whenever files were picked is gone - the
  queued-files badge is the feedback now. And the **whole-page entrance
  animations** are gone: navigation no longer replays a fade/slide over the entire
  view, the panel body no longer fades on a tab switch, and the list rows no longer
  stagger in on every render. Those re-ran on each update, which is what read as a
  "page refresh". A list that is re-loading now **dims** (`.busy`) instead of
  flashing a blank skeleton page. Interaction motion is untouched - press feedback,
  the sliding tab pill, the progress easing, toasts and the sheet entrance all stay.
- **v1.13:** the **Glass effect** switch no longer appears in the panel's Receive tab;
  it lives only in **Settings > Appearance** now. The panel still *applies* the
  setting (it reads it from the native store on every refresh) - it just is not a
  control there any more.
- **v1.6:** **"Choose files" opens the app's own file browser inside the panel**
  rather than the system picker — category chips, a scrolling file list with
  multi-select ticks, folder browsing with a back stack, an **Add (n)** button and
  a *System picker* fallback in the footer. Chosen files are handed to the engine
  through the new `pickRefs` bridge call and become the pending send.

## 3. v1.3 — motion, theme and live progress

- A full animation pass (cross-fade between screens, springier sheets, eased press
  feedback, an accent sheen on the share card) and an eased progress ring/bar.
- The share card is **black in every theme**, with the accent carried by its dot,
  chips and buttons.
- The "Listening" dot pulses in the **app's accent colour** with an expanding ring.
- The **panel matches the app's theme, accent and glow** (appearance is stored
  natively, not only in `localStorage`).
- **Live transfer progress in the notification** — on Android 16 it uses
  `Notification.ProgressStyle`, making it eligible to be a **Live Update**; older
  versions get a normal progress bar.

## 4. Built-in file manager (v1.4)

A **Files** tab with its own UI in the app's design language, backed by a native
index (`Files.java`).

- **"Allow access to all files"** (`MANAGE_EXTERNAL_STORAGE` on Android 11+, the
  storage runtime permission below) with a deep link to the system grant screen.
- **Twelve categories** as a tile grid with live counts: Recent, Images, Documents,
  Audio, Downloads, Screenshots, Apps, APK, Archive, Files, Folder, Contacts.
- Per entry: **Open**, **Send with Quick Share**, **Share with another app**; apps
  also get Open app / Share APK / App info, contacts get Call / Copy number, and
  Folder is a full browser with breadcrumb and Up.
- Search filters within any listing; a refresh control re-scans.
- **SD cards and other volumes (v1.7):** MediaStore is queried on **every** external
  volume, so all the media/document/APK/archive categories include SD-card content;
  the folder browser lists each mounted volume with a name and an *SD card* badge;
  and files under `/storage` can be opened and shared.

## 5. v1.5 — plain icons, the panel X, and transfer keep-alive

**Icons have no filled background any more.** The send / receive / folder rows and
the file-manager category tiles used to sit in a tinted circle; the fill is gone
and the glyph itself carries the colour (send = accent, receive = green, folder =
amber).

**The panel's X closes the app.** The footer buttons are gone; the X (or tapping
the scrim, or Back) dismisses the panel, and dismissing it stops Quick Share.

**A transfer in flight keeps the app alive, then it shuts itself down.** This is
the new lifecycle rule:

1. The panel (or the app screen) goes away.
2. If a transfer is **still running**, the engine is *not* stopped: the foreground
   service is started so Android keeps the process, the transfer finishes, and the
   **live progress notification keeps updating** the whole time.
3. The moment the last transfer ends, the app **shuts itself down** — discovery,
   the receive server and the foreground service all stop, and the ongoing
   service notification is removed. A toast confirms it.
4. If nothing was transferring, the app simply stops - there is no background mode.

**There is no background mode at all (v1.7).** Closing the panel - or leaving the
app screen - stops Quick Share. It never holds a background service of its own.
The **only** exception is a transfer that is still in flight: it is kept alive
just long enough to finish, and then Quick Share stops itself.

Implementation: `QsBridge.startEngine()` starts only the in-process engine (no
service); `QsBridge.onVisibleUiGone()` (called from `onStop`/`onDestroy` of both
activities) either stops everything, or - if `TransferManager.hasActive()` - starts
the service for the duration and sets `setShutdownWhenIdle(true)`, which
`shutdownIfIdle()` acts on the moment the active-transfer map empties.

## 6. Feature checklist (from the brief)

- Nearby device discovery over the local network (UDP broadcast beacon).
- Available-device list, live, with expiry of stale devices.
- Device selection; sending to a chosen device.
- Receiving files from other instances, with an accept/decline step.
- Incoming request shown in-app, in the panel, and as a notification.
- Support for any file type the system picker can return.
- Multiple-file sending in one transfer.
- File selection through Android's system file picker.
- Android Sharesheet integration — opens the panel, not the app.
- Live transfer progress in a notification (Live Update on Android 16), kept
  alive after the panel closes and removed when the transfer ends.
- In-app progress: percentage, transferred size, total, speed, ETA.
- Transfer cancellation; completed / failed / cancelled / rejected states.
- Transfer history (persisted, newest 200).
- Received-file storage management (folder configurable; saved to Downloads).
- Notifications for incoming requests and completed transfers.
- Device visibility / discoverability setting (also on the tile).
- Runtime permission requests, including **all-files access** and contacts.
- Connection status indicators; connection timeouts; disconnection handling.
- File integrity (exact-size streaming; SHA-256 verified in the protocol test).
- Duplicate-file handling ("name (1)", "name (2)" …).
- Large-file support (64 KB chunks, never loaded into memory).
- Safe handling of temporary/incomplete files.
- Transfer details: names, sizes, timestamps, peer device.
- Background transfers, with automatic shutdown once they finish.
- Error handling for unavailable devices, rejected connections, interrupted
  transfers and permission failures.
- Settings screen, and a built-in file manager with the twelve categories.

## 7. Technical decisions

- **No Gradle.** `aapt2 + javac + d8 + zipalign + apksigner` against build-tools 36
  and android-36 (`/scratch/work/build_apk.sh`).
- **WebView UI, native engine.** `app.css` is the shared design system; `app.js`
  is the app, `panel.js` the panel, `files.js` the file manager.
- **Discovery:** UDP broadcast on 8890 with unicast replies.
- **Transfer:** a TCP server on 8891; newline-delimited JSON handshake then raw
  bytes. See `PROTOCOL.md`.
- **File index:** MediaStore for media/documents, a plain `File` walk for folders,
  `PackageManager` for apps, `ContactsContract` for contacts.
- **Lifecycle:** the foreground service is only held while it is needed — during a
  transfer - and never at any other time.

## 8. Supported protocols and compatibility

- Plain UDP broadcast + TCP on the local network. No internet, no server, no account.
- **A standalone client: it talks to other copies of this app only.**
- It does **not** implement Google's proprietary Quick Share protocol and cannot
  interoperate with Google Quick Share or Windows Quick Share.

## 7b. Google Quick Share interoperability (in progress)

A separate effort to talk to **stock Google Quick Share**. The protocol is
reverse-engineered and documented; see **`QUICKSHARE-INTEROP.md`** for the plan,
the status and the honest caveats.

**Built** (`src/com/quickshare/qs/`): the protobuf wire codec, HKDF-SHA256, the
AES-256-CBC + HMAC-SHA256 secure-message envelope, the **UKEY2 handshake**, the
**D2D key derivation**, all the wire frames, a **sequence-numbered secure
channel**, and the **receive session FSM**. **42 JVM checks pass** (`QsCoreTest`
20, `QsUkey2Test` 16, `QsChannelTest` 6) - including RFC 5869 vectors, a full
handshake where both sides derive identical secrets and mutually consistent
traffic keys, encrypted exchange over a real socket, tamper detection and replay
rejection.

Android side (v1.15): `QsDiscovery` advertises `_FC9F5ED42C8A._tcp.` over
`NsdManager`, `QsReceiveService` runs a `connectedDevice` foreground service, and
**Settings > Google Quick Share** has the switch plus a live status line.

**Field test (v1.16):** a stock Quick Share sender found the device on a real
phone - so the mDNS advertisement and endpoint-info byte are correct - but the
transfer failed. Three real bugs were found and fixed: payload transfer frames
were missing the `packet_type` field (fatal - every negotiation message was
unparseable), the receiver never sent its own paired-key-encryption frame, and
there were no 10-second keep-alives. The 4-digit PIN now uses Chromium's real
algorithm, verified against an independent vector.

**Second field test (v1.17):** the device was listed again but the transfer still
failed, and this time the receiver's status line showed that **no TCP session ever
arrived**. Rather than guess again, v1.17 adds in-app diagnostics: the whole
protocol core logs every step into a ring buffer shown at
**Settings > Diagnostics**, with a Copy-log button and a copy written to
`Download/QuickShare/qs-diagnostics.txt`. The accept loop was also fixed - a
transient `accept()` failure previously killed the listener for good.

**Third field test (v1.18):** the diagnostics found the bug at once. The receiver
was double-stripping the 4-byte length prefix from the Ukey2 handshake - the
socket layer had already removed it - so the handshake died on its first message
with an `ArrayIndexOutOfBoundsException` reading the message's own opening bytes
as a length. The unit test had passed *framed* bytes between client and server,
which is not what a socket does, so it never caught this. Framing is now owned
solely by the transport layer, and a new `QsIntegrationTest` runs a complete
session over a real loopback socket (handshake, paired-key exchange,
introduction, consent, file on disk) so that boundary is tested for real.

**Fourth round (v1.19):** cross-checked against **Bada**, a Kotlin Android Quick
Share port whose docs record Samsung-specific findings from real devices. It
documents our exact symptom and its cause: the transport `ConnectionResponseFrame`
must carry **six** fields, because Samsung One UI validates them unconditionally
whichever role we play - and **missing `multiplex_socket_bitmask` makes One UI
8.0.5 silently FIN ~104 ms after our ACCEPT**, while **missing
`safe_to_disconnect_version` makes One UI 7+ silently drop us before the consent
dialog opens**. This implementation was sending two of the six. Also adopted from
Bada: the receiver-first paired-key ordering, the safe-disconnect handshake, the
`attachment_hash` echo on ACCEPT, and the `IPv4` TXT record with raw-byte mDNS
attributes set reflectively.

**Fifth round (v1.20):** the log showed the handshake reaching the commitment
check, with the ClientInit at exactly the right 136 bytes and the ServerInit at
exactly the right 116 - but the frame read as the ClientFinished at 55 bytes,
where a valid one must be 78. No decomposition of 55 forms a valid ClientFinished,
so the receiver was treating the wrong frame as the handshake's third message.
It now **classifies every handshake frame by its UKEY2 message type** and skips
anything that is not a ClientFinish (with a test that sends a deliberate stray
frame to prove it), and the commitment check is advisory rather than fatal.

Also in v1.20: **`QsBrowser`**, an mDNS browse for `_FC9F5ED42C8A._tcp.` that
decodes the endpoint id and endpoint info so this app can finally *see* stock
Quick Share devices, not just be seen by them.

**Root cause found (v1.21):** the UKEY2 `Ukey2Message.Type` enum was **wrong by one for
every value**. `ukey.proto` says `ALERT=1, CLIENT_INIT=2, SERVER_INIT=3, CLIENT_FINISH=4`;
this code had `CLIENT_INIT=1, SERVER_INIT=2, CLIENT_FINISH=3`. So the ServerInit went out
tagged 2, the sender read it as a ClientInit, and replied with a `Ukey2Alert` -
`BAD_MESSAGE_TYPE`, "Expected, but did not find SERVER_INIT" - which is exactly what the
decoded bytes said. Fixed, quoted from the proto, and pinned by tests that check both the
constants and the wire bytes.

**v1.22:** the handshake now completes against a real Samsung sender - the log shows
`commitment verified`, `key exchange complete`, a PIN, both connection responses and
the switch to the encrypted layer. Two follow-on bugs found in the same log are also
fixed: **P-256 coordinates must be encoded in the signed form** the peer parses
(a bare 32-byte magnitude reads as negative when the top bit is set, which caused
intermittent `BAD_PUBLIC_KEY` alerts), and **bandwidth-upgrade requests are now
answered** rather than ignored, so the sender does not stall after the encrypted
channel opens.

**v1.23:** the handshake is confirmed working on hardware (`commitment verified`,
`key exchange complete`, PIN, both connection responses, encrypted layer). The next
blocker is visible in the log: the sender closes the connection **~330 ms after the
encrypted layer without sending anything**, so it is rejecting the receiver's
paired-key-encryption frame. This build logs **every frame it sends** so the next log
shows exactly what the peer receives, and fixes the bandwidth-upgrade frame's field
number (V1Frame field 5, not 2) plus handling of the retry type.

**Not verified:** a completed file transfer with a stock Quick Share device. See
`QUICKSHARE-INTEROP.md` section 12.

## 8b. Bug fixed in v1.10: a compile error the build was hiding

`Files.java` stopped compiling in v1.7 (my multi-volume edit removed the `args`
parameter from `mediaOne()` but left `cr.query(..., args, ...)` behind). The build
script piped javac through a filter ending in `|| true`, so **the failure was
swallowed** and the APK shipped without `Files.class`; every file listing then
returned empty, which is exactly the "no files displayed" the user saw.

Two fixes: the query now passes `null`, and `build_apk.sh` captures javac's output
to a log, checks its exit status, and additionally verifies that a `.class` file
exists for **every** source file - refusing to package otherwise. The script is now
kept in the project as `build_apk.sh` so the fix travels with the source.

## 9. Build status

- Builds cleanly against API 36; signed with a debug key; `apksigner verify` passes.
- APK size ≈ 792 KB.

## 10. Test results

1. **Protocol framing test** (`/scratch/work/proto_test/ProtoTest.java`) — a real
   sender/receiver over a loopback socket using the app's own framing code: a 1 MB
   multi-chunk file, a 37-byte file, a 0-byte file, a non-ASCII name and the reject
   path all match byte-for-byte (SHA-256).
2. **App UI test** (`uitest.js`, jsdom) — 45 checks, all pass.
3. **Panel UI test** (`paneltest.js`, jsdom) — 62 checks, all pass: the panel
   itself, theme following, the footer being gone, the X closing the panel, and
   the whole in-panel picker (open, category chips, listing, single-row selection,
   Add → `pickRefs` payload, the system-picker fallback and folder back-stack
   navigation).
4. **File manager test** (`filestest.js`, jsdom) — 48 checks, all pass (including
   the SD-card root and browsing into it).
5. **Visual check** — app, panel, file manager and in-panel picker rendered in
   headless Chromium. Previews: `QuickShare-v112.png`, `QuickShare-v113.png`.
   The dex is also inspected to confirm every class is present.
   Two real bugs were found this way and fixed: the picker's "up" navigation did
   not return to the storage roots (now a proper path stack), and two picker rows
   sharing a URI were selected together (the row key is now uri + name).

## 11. Known issues / not yet verified

- **No on-device run.** No Android device or emulator is available here, so the
  build, the protocol and the UIs are verified independently but nothing has run
  on hardware. Please test on a real phone.
- **All-files access is a sideload feature.** `MANAGE_EXTERNAL_STORAGE` is not
  granted by Google Play to a general file manager, so this build is meant to be
  installed directly.
- Contacts need the Contacts runtime permission.
- Live Update promotion is the system's decision; the API only makes the
  notification eligible.
- The Quick Settings tile must be added by the user.
- If the phone kills the process despite the foreground service (very aggressive
  OEM battery savers), a transfer can still be cut short.
- Some Wi-Fi networks (guest / client-isolated) block device-to-device traffic.
- A transfer whose declared size is wrong will stall on the receiver; cancel recovers it.

## 12. How to resume

- Rebuild: `bash /scratch/work/build_apk.sh /scratch/work/quickshare /scratch/work/quickshare/QuickShare.apk`
- Toolchain: `/scratch/work/sdk` (build-tools 36 at `sdk/bt36/36.0.0`, platform-36
  at `sdk/platforms/android-36`); see `env.sh`. Run the jsdom tests with
  `ulimit -v unlimited` (node needs it for jsdom's WASM parser).
- Source: `src/com/quickshare/*.java`; web UI in `assets/www/` — `index.html`,
  `app.js` (app), `panel.js` (panel), `files.js` (file manager), `app.css` (shared).
