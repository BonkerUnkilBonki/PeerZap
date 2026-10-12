# Quick Share — install and test

## Install the APK

1. Copy `QuickShare.apk` to your Android phone (USB, Google Drive, or send it to
   yourself).
2. On the phone, open the file. Android will ask you to allow installs from this
   source — allow it, then tap **Install**.
   (Settings > Apps > Special access > Install unknown apps, if needed.)
3. The app is signed with a debug key. If you have an older build installed and
   Android refuses the update, uninstall the old one first.

Minimum Android version: **7.0 (API 24)**.

## Add the Quick Share tile

1. Open the Quick Settings shade (swipe down twice, or swipe down with two fingers).
2. Tap the pencil / edit button, find **Quick Share** in the list of tiles and
   drag it into your active tiles.
3. Tap the tile any time — a frosted-glass panel slides up from the bottom and the
   shade collapses. You can send or receive files from the panel without opening
   the app. The tile also shows whether your device is currently visible to others.

## First run

- Grant the notification permission when asked (needed for incoming-file alerts).
- On Android 13+ you may also be asked for the "Nearby devices" permission; on
  Android 12 and below, "Location". These are used only for local-network device
  discovery.

## Send or receive from the panel (no app needed)

- **Send:** tap the tile, tap **Choose files**, and the panel opens its **own file
  browser** — category chips (Recent, Images, Documents, Audio, Downloads,
  Screenshots, APK, Archive, Files, Folder), a file list with tick boxes, and
  folder browsing. Tick what you want and tap **Add**; the files queue up and you
  then tap the nearby device. (A **System picker** button in the footer falls back
  to Android's own picker if you need it.) The panel can stay open or be dismissed
  — the transfer continues in the background and you get a notification when it
  finishes.
- **Receive:** tap the tile, switch to **Receive**. Incoming requests appear right
  in the panel with **Accept** and **Decline**. You can also leave the app running
  and accept from the notification.

## Test a transfer (two phones)

1. Put **both phones on the same Wi-Fi network** (not one on mobile data).
2. Open Quick Share (or just its panel) on both. Give each a recognisable name in
   Settings.
3. On phone A, tap **Choose files** and pick one or more files.
4. Phone B should appear in the device list (allow a few seconds).
5. Tap phone B. Phone A starts sending; phone B shows an incoming request.
6. On phone B, tap **Accept**. The transfer runs with live progress, speed and ETA.
7. Received files are saved to **Downloads/QuickShare** on phone B. Open them from
   the Transfers screen, or from any file manager.

**Sharing from any other app opens the panel too.** Select files in Gallery,
Files, Photos, or anywhere else, tap **Share**, and choose **Quick Share** — the
panel slides up with those files already queued, ready for you to tap a device.
The full app screen is not opened.

## Closing the panel, and what runs in the background

The panel has no Open app / Close buttons — the **X** in its header is the way
out. Tapping it (or the area around the panel, or Back) dismisses the panel and
closes Quick Share.

**A transfer in progress is never cut short.** If files are still moving when you
close the panel, Quick Share stays alive in the background just long enough to
finish them — the live progress notification keeps updating the whole time — and
then it shuts itself down on its own and removes the notification.

**Quick Share has no background mode.** Closing the panel — or leaving the app
screen — stops it completely; there is no service left running and no permanent
notification. The only time it stays alive is for a transfer that is still in
flight, and it stops itself the moment that transfer ends.

## Live progress in the panel

While a transfer runs, the panel itself shows it - in **both** the Send and the
Receive tab: a circular percentage, who it is going to or coming from, the file
count, transferred/total size and the speed with time left. It updates live and
disappears when the transfer ends.

## Live progress in the notification

While a transfer runs you get a progress notification showing the direction, the
other device, the file count, transferred/total size, speed and time left. On
**Android 16** this uses the new progress notification style, so the system can
show it as a **Live Update** — a progress chip in the status bar and on the lock
screen — without you opening the app. On older Android versions the same
notification shows a normal progress bar.

## Theme and the glass effect

The app and the panel always look the same: whatever theme (Light / Dark / Pitch
black), accent colour and glow setting you pick in the app is also used by the
Quick Share panel and to tint the progress notification.

The panel and the bottom sheets use a frosted-glass surface. If you would rather
they were solid, turn off **Glass effect** in the app under
**Settings → Appearance**. (It is a Settings-only switch — the panel does not show
it.)

## The built-in file manager

Open the **Files** tab (fourth tab in the bottom bar). The first time, it asks for
**access to all files** — tap **Open permission** and turn on "Allow access to all
files" in the system screen, then come back. Nothing is uploaded anywhere; the
files stay on your device.

You then get twelve categories: **Recent, Images, Documents, Audio, Downloads,
Screenshots, Apps, APK, Archive, Files, Folder, Contacts**.

- Tap a category to list its items; use the search field to filter, and the
  refresh button to re-scan.
- Tap any file for **Open**, **Send with Quick Share** (queues it and jumps to the
  device list) or **Share with another app**.
- **Folder** is a full browser with a breadcrumb and an Up control. It lists every
  mounted volume, so an **SD card** appears alongside internal storage (with an
  *SD card* badge), and the media/document/APK/archive categories include SD-card
  content too.
- **Apps** lists installed apps — open one, share its APK, or open its app info.
- **Contacts** lists names and numbers — call or copy a number (needs the Contacts
  permission; the category prompts for it).

Note: "All files access" is a sideload-style permission that Google Play does not
grant to general file managers, so this build is meant to be installed directly.

## Troubleshooting

- **No devices found.** Both phones must be on the same Wi-Fi and both must have
  Quick Share (app or panel) open. Some public/guest Wi-Fi networks block
  phone-to-phone traffic (client isolation) — try a home network or a phone hotspot.
- **The tile does nothing.** Make sure it was added to the active tiles; on some
  phones new tiles land on a second page of the tile picker.
- **Transfer stalls.** Keep the screen on and the app in the foreground on both
  phones; very aggressive battery savers can pause background work. Use Cancel to
  recover.
- **A file won't open.** The receiving phone may have no app that handles that
  file type.
- **No Live Update chip in the status bar.** Live Updates are an Android 16
  feature and the system decides whether to promote a notification; make sure
  Quick Share's notifications are allowed. The progress notification itself still
  shows a progress bar on any Android version.
- **Files tab shows very little.** The "Allow access to all files" permission has
  not been granted — open it from the card at the top of the Files tab.
- **Contacts category is empty.** Grant the Contacts permission when prompted.

## What this app is, and is not

It is a **standalone** nearby-sharing client. It finds and transfers to **other
phones running this same app**. It does **not** use Google's Quick Share protocol
and cannot send to or receive from Google Quick Share or Windows Quick Share.

## Rebuilding from source

```
bash /scratch/work/build_apk.sh /scratch/work/quickshare /scratch/work/quickshare/QuickShare.apk
```

Requires the bundled toolchain in `/scratch/work/sdk` (JDK 17 + Android
build-tools 34 + platform-35). No Gradle needed.
