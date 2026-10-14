# Google Quick Share interoperability — plan and status

This document is about making this app talk to **stock Google Quick Share**
(and its relatives: NearDrop, Quick Share for Windows, rquickshare). It is
separate from the app's own protocol, which is documented in `PROTOCOL.md` and
works today between two copies of this app.

## 1. What is actually possible

Google's Quick Share (formerly Nearby Share) has **no public API**. It is,
however, *reverse-engineered and documented*, and several independent projects
implement it, so third-party interop is real:

| Project | Platform | Direction |
|---|---|---|
| [NearDrop](https://github.com/grishka/NearDrop) | macOS | receive (reference implementation + the protocol spec) |
| [rquickshare](https://github.com/Martichou/rquickshare) | Linux/macOS/Windows | send + receive |
| [quickshare-cli-py](https://github.com/adityatelange/quickshare-cli-py) | Linux (Python) | send + receive |
| [Bada](https://github.com/kyujin-cho/Bada) | **Android** | send + receive — the closest match to what we want |
| QShare | macOS (Swift) | send + receive |

The protocol write-up used here is @grishka's
[PROTOCOL.md](https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md).

**Feasible today:** the **Wi-Fi LAN** path, on the same network and VLAN.
Both directions work over plain TCP + mDNS:

- receiving from stock Quick Share works because our app advertises an mDNS
  service that a stock sender browses for;
- sending to stock Quick Share works when the stock device has its Receive
  screen open (that is what makes *it* advertise).

**Not feasible without a lot more work:** Wi-Fi Direct, Wi-Fi hotspot, BLE, and
the Google-server-backed paths. Android only becomes *discoverable to us* when
it sees a specific BLE advertisement, which we cannot send on every platform —
that is the same wall NearDrop hit.

## 2. How the protocol works (short version)

Roles are per-transfer: the **receiver is the TCP server** and advertises mDNS;
the **sender is the client** and connects.

1. **Discovery.** mDNS service type `_FC9F5ED42C8A._tcp.` (the first six bytes of
   `SHA256("NearbySharing")`), an instance name carrying a 4-character endpoint
   ID, and a TXT record `n` holding the "endpoint info" (visibility bit, device
   type, a 16-byte identity blob and, when visible, the device name).
2. **Framing.** Length-prefixed protobuf `OfflineFrame`s over TCP.
3. **Handshake.** The client sends a `CONNECTION_REQUEST` and a UKEY2
   `CLIENT_INIT`; the server answers with UKEY2 `SERVER_INIT`; the client sends
   `CLIENT_FINISH`. UKEY2 is a P-256 ECDH exchange; a 4-digit PIN derived from
   the handshake lets the user confirm both ends.
4. **Encryption.** Everything after that is a `SecureMessage` envelope —
   AES-256-CBC + HMAC-SHA256, with its own sequence numbers — carrying
   `DeviceToDeviceMessage` → `OfflineFrame`.
5. **Sharing.** Inside that: a paired-key exchange, then the sender offers files
   (`IntroductionFrame`), the receiver accepts, and the bytes stream as
   `PayloadTransferFrame` chunks (with Android's quirk of a second, empty
   `LAST_CHUNK` frame after each message).

## 3. What is implemented

`src/com/quickshare/qs/` is a **pure-Java protocol core** (no Android imports),
so it can be tested on the JVM:

- `Proto.java` — a small protobuf wire-format codec (varints, length-delimited,
  fixed32, nested messages), because the app ships without a protobuf runtime.
- `Hkdf.java` — HKDF-SHA256 built directly on `javax.crypto.Mac`.
- `SecureMessage.java` — the AES-256-CBC + HMAC-SHA256 envelope, with
  verify-before-decrypt.
- `Ukey2.java` — the UKEY2 handshake (ClientInit / ServerInit / ClientFinished),
  P-256 ECDH, commitment check, and the auth-string / next-secret derivation from
  the google/ukey2 spec.
- `D2DKeys.java` — the four AES/HMAC traffic keys, with the exact reference
  constants (D2D salt `82AA55A0…`, SecureMessage salt `BF9D2A53…`) and the
  server/client role swap.
- `QsFrames.java` — every wire frame: `OfflineFrame`/`V1Frame`, connection
  request/response, payload transfer, disconnection, and the sharing layer
  (`Frame`, `IntroductionFrame`, `FileMetadata`, response, paired-key).
- `SecureChannel.java` — framed, encrypted I/O with per-direction sequence
  numbers, verify-before-decrypt, and replay rejection.
- `QsReceiver.java` — the TCP server and the receive session: handshake, then
  the sharing FSM (paired-key result, introduction, consent, payload assembly,
  file streaming).

**42 JVM checks pass** across three test classes (`QsCoreTest` 20,
`QsUkey2Test` 16, `QsChannelTest` 6), including:

- protobuf round-trips (nested messages, multi-byte varints, proto2 default
  omission);
- **HKDF against the RFC 5869 test vectors**;
- secure-message round-trip, fresh IV per message, wrong-key rejection and
  **tamper detection**;
- an `OfflineFrame` → `V1Frame` → `PayloadTransferFrame` built and parsed back
  to the exact bytes;
- a **full UKEY2 handshake** where both sides independently derive the same auth
  string and next secret, the same 4-digit PIN, and **mutually consistent**
  traffic keys (client encrypt == server decrypt, and vice versa);
- an encrypted exchange in **both directions** over a real loopback socket;
- **tamper detection** (a flipped bit in ClientFinished is rejected), **replay
  rejection** (the same sequence number twice), and rejection of a peer with
  the wrong keys.

This is the part where mistakes are silent and expensive, and it is now pinned
down by tests.

### Android wiring (v1.15)

- `QsDiscovery.java` — advertises `_FC9F5ED42C8A._tcp.` over `NsdManager` with
  the endpoint-info TXT record and a Wi-Fi multicast lock.
- `QsReceiveService.java` — a `connectedDevice` foreground service that binds the
  TCP port, advertises, and streams received files into `Download/QuickShare`.
- A **Settings → Google Quick Share → Quick Share compatibility** switch with a
  live status line.

## 4. What is left

1. **Sending** to stock Quick Share (our app as the client): browse mDNS, connect,
   run the handshake as the client, offer files with an `IntroductionFrame`. The
   transport and crypto are already symmetric, so this is mostly the outbound FSM.
2. **Consent UI** — the receiver currently auto-accepts and shows the PIN in the
   notification; it should prompt in the app with the PIN and Accept/Reject.
3. **MediaStore** — files land in `Download/QuickShare` as plain files; saving
   through MediaStore would make them show up in the gallery immediately.
4. **Verification against a real peer** — see section 5.

## 5. The honest caveat

**The protocol core is verified; the on-device behaviour is not.** There is no
Android device, no stock Quick Share peer and no emulator here, so:

- everything in section 3 is *proved correct* by JVM tests;
- the Android wiring and the receive FSM compile and are written from the spec;
  they have been tried against one real device, which found the bugs above, but
  a **successful end-to-end file transfer has not been observed yet**;
- the first on-device run almost always surfaces small mismatches (a field
  number, an encoding detail, the ordering of two frames). Those need a device,
  `adb logcat`, and iteration.

Two details in particular are the most likely to need adjusting against a real
peer, because the spec is ambiguous about them: the exact bit layout of the
endpoint-info byte, and whether the UKEY2 commitment hash is taken over the
ClientFinished message with or without its 4-byte length prefix (this
implementation includes it, as the spec says "including framing").

So this is a real, multi-step port rather than a switch to flip. The core is
done; the connection layer and the Android wiring are the next steps.

## 6. Sources

- grishka/NearDrop — PROTOCOL.md and the reference implementation
- google/ukey2 — the UKEY2 handshake specification
- Chromium's Nearby Share `.proto` files (vendored by the projects above)
- kyujin-cho/Bada — an Android port of the same protocol, useful as a map

---

# Field-report history (on-device testing against a Samsung SM-S938B)

Each round below was driven by a log the user captured from the app's own
Diagnostics panel. The pattern worth noting: the protocol core passed its tests
every time, and every real bug was in the seam between it and a real sender.

## 7. Payload frames were missing `packet_type` (v1.16)

A stock sender found the device (so mDNS and the endpoint-info byte were right)
but the transfer failed. Every payload frame is
`PayloadTransferFrame { packet_type = 1, payload_header = 2, payload_chunk = 3 }`;
the code was writing the header at field 1 and the chunk at field 2 with no
`packet_type` at all, so every negotiation message was unparseable and the
receiver never answered. Also fixed: the receiver never sent its own
paired-key-encryption frame, and sent no keep-alive frames (Android drops a peer
that misses a 10-second keep-alive).

## 8. Framing was stripped twice (v1.18)

The log showed `ArrayIndexOutOfBoundsException: src.length=136 ... length=134353539`.
`134353539` is `0x08011298` - the first four bytes of the Ukey2 message itself. The
socket layer already strips the 4-byte length prefix, and UKEY2 stripped it again,
reading the message's own opening bytes as a length. The unit test missed it because
it passed *framed* bytes between the client and server halves, which is not what a
socket does. A full-session integration test over a real loopback socket now covers
that boundary.

## 9. Six fields on the connection response (v1.19)

Cross-checked against [kyujin-cho/Bada](https://github.com/kyujin-cho/Bada), whose
docs record Samsung-specific findings from real devices. The transport
`ConnectionResponseFrame` must carry **six** fields, because Samsung One UI
validates them unconditionally whichever role we play:

| field | value | consequence if absent |
| --- | --- | --- |
| `status` (1) | 0 | legacy field |
| `response` (3) | ACCEPT | - |
| `os_info.type` (4) | ANDROID | LINUX "causes Samsung silent FINs" |
| `multiplex_socket_bitmask` (5) | 0 | One UI 8.0.5 "silently FINs ~104 ms after our ConnectionResponse{ACCEPT}" |
| `safe_to_disconnect_version` (7) | 1 | One UI 7+ "silently drops us before the consent dialog opens" |
| `keep_alive_timeout_millis` (9) | 600000 | One UI mid-payload disconnect |

Also adopted from Bada: the receiver-first paired-key ordering, the
safe-disconnect handshake, the `attachment_hash` echo on ACCEPT, and the `IPv4`
TXT record with mDNS attributes set as raw bytes via reflection (the `byte[]`
overload is missing from some Android 36 platform stubs, and the String path is
lossy above 0x7F).

## 10. The root cause: a wrong UKEY2 message-type enum (v1.21)

The sender's own error message, decoded from a 55-byte frame, read:

```
08 02 12 33  45 78 70 65 63 74 65 64 2c 20 62 75 74 20 64 69 64 20 6e 6f 74 20 66 69 6e 64 ...
              E  x  p  e  c  t  e  d  ,     b  u  t     d  i  d     n  o  t     f  i  n  d ...
```

**"Expected, but did not find SERVER_INIT"** - a `Ukey2Alert` with `type = 2`, which
`ukey.proto` defines as `BAD_MESSAGE_TYPE`. Then the proto settled it:

```proto
message Ukey2Message {
  enum Type {
    UNKNOWN_DO_NOT_USE = 0;
    ALERT = 1;
    CLIENT_INIT = 2;
    SERVER_INIT = 3;
    CLIENT_FINISH = 4;
  }
```

This implementation had `CLIENT_INIT = 1, SERVER_INIT = 2, CLIENT_FINISH = 3` -
**every value off by one.** So the ServerInit went out tagged 2, the sender read it
as a ClientInit, and answered that it never found a SERVER_INIT. That single mistake
explains every symptom from the first round onwards. The enum is now correct, quoted
from the proto in the source, and pinned by tests that assert both the constants and
the bytes on the wire.

## 11. Signed coordinates and bandwidth upgrades (v1.22)

With the enum fixed, a real Samsung sender completes the handshake:

```
recv  handshake frame 79 bytes, ukey2 type=4      <- CLIENT_FINISH
commitment verified
handshake  key exchange complete
handshake  confirmation PIN 5836
send  connection response (accept)
recv  their connection response 20 bytes
encrypted  switched to the encrypted layer
recv  bytes payload sharing type 3                <- paired-key exchange
```

Two further bugs were visible in the same log and are fixed:

1. **P-256 coordinates must be the signed form.** The sender writes
   `0a 21 00 a01b607a...` - 33 bytes with a leading `0x00`, i.e.
   `BigInteger.toByteArray()`. A fixed 32-byte magnitude is parsed by the peer as a
   **negative** number whenever the top bit is set, so the point lands off the curve
   and the sender answers `BAD_PUBLIC_KEY` ("Cannot parse public key: Point
   encoded..."). That was intermittent - roughly half of attempts - and is now
   encoded identically to the sender, verified across 60 generated keys.
2. **Bandwidth-upgrade requests are answered.** Stock senders solicit an upgrade as
   soon as the encrypted channel is up; staying silent stalled the transfer. The
   receiver now replies `UPGRADE_FAILURE` so the sender continues on Wi-Fi LAN.

## 12. The next blocker: the sender closes right after the encrypted layer (v1.23)

With the handshake fixed, a real sender now reaches the encrypted layer every time:

```
commitment verified
handshake  key exchange complete
handshake  confirmation PIN 9087
send  connection response (accept)
recv  their connection response 20 bytes
encrypted  switched to the encrypted layer
send  disconnection (requesting safe disconnect)     <- 330 ms later
```

The receiver sends its paired-key-encryption frame immediately (as Bada's
`InboundSharingFsm.start()` does), and the sender then closes the TCP connection
**~330 ms later without sending a single frame back**. It is consistently ~330 ms,
which points at the sender rejecting the receiver's paired-key frame rather than a
timeout.

This build adds **send-side logging** - every frame the receiver emits is now logged
with its payload id, sharing type and length - so the next log will show exactly
what the peer receives. It also fixes two smaller things found while investigating:
the bandwidth-upgrade frame's `V1Frame` field number (5, not 2) and handling of
`BANDWIDTH_UPGRADE_RETRY` (type 12) alongside the negotiation type.

## 13. Ordering: the upgrade exchange comes before the paired-key frame (v1.24)

The v1.23 log confirmed the receiver emits the paired-key frame correctly
(`send bytes payload id=1001 sharing type 3 (90 bytes)`), and the sender then closes
the TCP connection ~380 ms later having sent nothing at all.

Bada's receiver does something this implementation did not: immediately after the
handshake it runs `BandwidthUpgradeOrchestrator.runServerUpgradeIfAvailable(...)`
**before** `startNegotiation(...)` - i.e. the upgrade exchange happens first and the
paired-key frame is sent only afterwards (with a 750 ms delay when the medium
actually changed). A stock sender solicits an upgrade as soon as the encrypted
channel is up.

v1.24 matches that order: after the handshake the receiver briefly waits for the
peer's first encrypted frame, declines an upgrade offer with `UPGRADE_FAILURE` if one
arrives, and only then sends its own paired-key frame. The wait is logged either way,
so the next run will show whether the sender offers an upgrade, says nothing, or
hangs up.

## 14. The pre-negotiation wait dropped the peer's first frame (v1.25)

The v1.24 ordering fix worked - the sender now sends its paired-key frame:

```
recv  first encrypted frame type=3
recv  peer sent its own paired-key frame first
send  bytes payload id=1001 sharing type 3 (90 bytes)
recv  payload ... off=89 len=0 flags=1
recv  bytes payload ... sharing type -1 (0 bytes)
```

But the receiver's new pre-negotiation wait **consumed that first payload frame and
threw it away**. A paired-key message arrives as two payload frames - the data, then
an empty `LAST_CHUNK` terminator - so discarding the data left only the terminator,
which the assembler then reported as a complete zero-byte payload (`sharing type -1`).
The sender waited for a reply that never came and the connection timed out.

The frame is now carried into the receive loop instead of being dropped, and there is
a regression check asserting the peer's paired-key frame is assembled whole
(`sharing type 3`) rather than truncated.

## 15. The paired-key exchange completes (v1.26)

The frame-carrying fix worked. A real sender now completes the paired-key exchange:

```
recv  first encrypted frame type=3
recv  carrying that frame into the receive loop
send  bytes payload id=1001 sharing type 3 (90 bytes)     <- our PKE
recv  payload ... off=0  len=90 flags=0
recv  payload ... off=90 len=0  flags=1
recv  bytes payload ... sharing type 3 (90 bytes)         <- peer's PKE assembled whole
send  bytes payload id=1002 sharing type 4 (10 bytes)     <- our PKR
```

The peer's encryption frame is now assembled intact (previously only its empty
terminator survived) and answered. The sender then closes ~310-550 ms later without
sending its own paired-key result, so the exchange stalls one step short of the
introduction.

v1.26 adds loop-exit diagnostics - whether the peer closed cleanly (EOF) or a read
failed, and how long the encrypted phase lasted - to pin down that final gap.

## 16. Send ordering: wait for the peer's frame in full (v1.27)

The v1.26 log distinguished the failure precisely: the peer **closes cleanly (EOF)**
about 300 ms after receiving the receiver's result frame, having sent nothing more -
no read failure, no decryption error. So it is hanging up, not breaking.

Frame contents were ruled out: Bada uses `secret_id_hash = 6` and
`signed_data = 72`, and the receiver's 90-byte encryption frame and 10-byte result
frame match the sender's byte counts exactly.

What differs is **ordering**. The log shows the receiver sending its encryption frame
while the peer's was still in flight:

```
.545  send  our PKE            <- sent before the peer's PKE arrived
.546  recv  peer's PKE data
.582  recv  peer's PKE terminator
.582  send  our PKR
```

v1.27 waits for the peer's paired-key frame to arrive **in full** (both the data frame
and its `LAST_CHUNK` terminator) before sending its own, then sends its encryption
frame and result frame as a pair. The loop no longer re-answers a frame that was
already handled in that phase.

## 17. Reverted to the reference ordering (v1.28)

v1.27's wait for the peer's paired-key frame made things worse: the sender still
closed cleanly ~360 ms after the receiver's two frames, having sent nothing further.

Comparing rounds, the sender actually got **further** when the receiver sent its
paired-key frame immediately: back then it replied with its own frame *and* a
bandwidth-upgrade frame. Re-reading Bada confirmed that the wait was this
implementation's own invention - `runServerUpgradeIfAvailable` returns without
blocking when no upgrade medium is available, and only then does `startNegotiation()`
emit the frame. So the receiver sends its paired-key frame straight away.

v1.28 restores that: the frame goes out immediately after the handshake, with the
upgrade exchange (when the sender solicits one) handled inside the receive loop.
That combination - immediate send plus upgrade handling - has not been tried on
hardware before.

## 18. An asymmetry in the envelope (v1.29)

Reverting to the reference ordering (v1.28) did not change the outcome: the sender
still closes cleanly ~385 ms after the receiver's paired-key and result frames,
having sent nothing further.

The decisive observation is an **asymmetry**: the receiver decrypts the sender's
frames successfully, yet the sender closes the instant it receives ours. That rules
out the key derivation (shared) and points at something in the receiver's outgoing
envelope that the sender validates and the receiver's own decrypt routine does not.

Checked and cleared so far: `SigScheme.HMAC_SHA256 = 1` and `EncScheme.AES_256_CBC = 2`
both match `securemessage.proto`, and the header field numbers
(`signature_scheme = 1, encryption_scheme = 2, iv = 5, public_metadata = 6`) match too.

Still unverified is the **`public_metadata` payload** (header field 6). The spec says
it holds `version = 1` and `type = DEVICE_TO_DEVICE_MESSAGE`, but the vendored protos
do not define that message, so its `type` value has been assumed to be 1.

v1.29 logs a readable dump of every envelope header - on send and on receive - so the
two can be compared field by field. `describeHeader` reports the signature and
encryption schemes, the IV length, the public metadata bytes and the body length.

## 19. The public metadata: field order swapped, type value wrong (v1.30)

The header dump found it in a single line. Ours against the sender's:

```
sending:  sig=1 enc=2 iv=16B pubmeta=08011001 body=128B     <- ours
received: sig=1 enc=2 iv=16B pubmeta=080d1001 body=144B     <- the sender's
```

Everything else matched - same signature scheme, same encryption scheme, same 16-byte
IV. Only the public metadata differed.

`08011001` is `{1: 1, 2: 1}`; the sender's `080d1001` is `{1: 13, 2: 1}`.

The message is `GcmMetadata`, whose fields are **`type = 1` and `version = 2`** - the
opposite of the "version that is always 1 and type that is always
DEVICE_TO_DEVICE_MESSAGE" wording in the protocol notes, which is what this code had
followed. And `Type.DEVICE_TO_DEVICE_MESSAGE` is **13**, not 1.

So this implementation sent `{type: 1, version: 1}` where the sender sends
`{type: 13, version: 1}`. The receiver's own decrypt routine never inspects this field,
so the mistake was invisible from our side: we could read the sender's frames, and it
rejected ours on sight. That is precisely the asymmetry the previous round identified.

Fixed, and pinned by a test asserting the exact bytes `080d1001`.

## 20. Receiving from stock Quick Share works (v1.31)

The public-metadata fix was the last blocker. A real Samsung sender now completes the
whole receive path:

```
recv  bytes payload ... sharing type 4 (12 bytes)       <- peer's paired-key result
recv  bytes payload ... sharing type 1 (110 bytes)      <- INTRODUCTION
intro  offered 1 file(s) from ... NOTA's Galaxy
send  bytes payload id=1012 sharing type 2 (61 bytes)   <- our ACCEPT
recv  payload ... type=2 off=0      len=524288 flags=0  <- FILE data
recv  payload ... type=2 off=524288 len=192005 flags=0
recv  payload ... type=2 off=716293 len=0      flags=1  <- complete: 716,293 bytes
```

Handshake, paired-key exchange, introduction, consent and file payloads all work
against stock Google Quick Share on the same Wi-Fi network.

One rough edge remains: after the file lands the sender sits in keep-alives for 60 s
and then disconnects, because it asks to upgrade the medium and the receiver never
answers it. The upgrade frame's body sits at a different `V1Frame` field depending on
the frame type - field 5 for BANDWIDTH_UPGRADE_NEGOTIATION (type 4), field **13** for
BANDWIDTH_UPGRADE_RETRY (type 12). Reading only field 5 is why the log showed
`event=-1`. Both the parser and the frame builder now handle both shapes, and any
upgrade frame is answered with UPGRADE_FAILURE so the sender carries on over Wi-Fi
LAN. File writes are logged too, so a completed transfer is visible in the log.

## 21. Confirmed on hardware, and the session now closes cleanly (v1.32)

The v1.31 log shows a complete, saved file from a real Samsung sender:

```
recv  payload ... type=2 off=0      len=524288 flags=0
recv  payload ... type=2 off=524288 len=300447 flags=0
recv  payload ... type=2 off=824735 len=0      flags=1
file  saved QuickShare (29).apk (824735 bytes)
```

The upgrade frame also parses correctly now (`event=5`) rather than `-1`, confirming
the field-13 fix for BANDWIDTH_UPGRADE_RETRY.

One rough edge remained: after the file landed, the receiver waited for the sender to
hang up, so both sides sat in keep-alives for the full 60 s timeout before the sender
gave up. Bada's receiver sends a `Disconnection` once every announced payload has
arrived and then closes; this implementation only sent one when the peer disconnected
first. v1.32 tracks the payload ids announced in the IntroductionFrame and disconnects
as soon as the last of them completes, so the session ends when the transfer does.

## 22. Two transfers verified end to end, clean closes (v1.33)

The v1.32 log shows two complete receives from a real Samsung sender, both saved and
both closed promptly:

```
recv  payload ... type=2 off=824735 len=0 flags=1
file  saved QuickShare (31).apk (824735 bytes)
session  all announced payloads received - disconnecting
session  receive loop ended after 964ms in the encrypted phase

recv  payload ... type=2 off=22985 len=0 flags=1
file  saved PROGRESS.md (22985 bytes)
session  receive loop ended after 479ms in the encrypted phase
```

The encrypted phase now ends in under a second instead of the previous 60 s timeout.

One cosmetic bug fixed in v1.33: the peer name was read from `endpoint_name`
(field 2 of `ConnectionRequestFrame`), which contains binary junk; the readable name
lives in the `endpoint_info` blob (field 6) as
`1 byte flags | 16 bytes identity | 1 byte name length | UTF-8 name`.

## 23. Where received files go (v1.34)

A third transfer succeeded (`file saved IMG-20261010-WA0000.jpg (154175 bytes)`) but the
user could not find the file on the phone. The cause:

```java
File dir = new File(getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS), "QuickShare");
if (!dir.exists()) dir.mkdirs();
if (!dir.exists()) dir = getExternalFilesDir(DIRECTORY_DOWNLOADS);   // <-- silent trap
```

`mkdirs()` on the public `Download/QuickShare` needs "All files access". Without it the
call fails and the code fell through to `Android/data/com.quickshare/files/Download`,
which **file managers cannot display on Android 11+**. The log said "saved" because a
write did succeed - just somewhere invisible.

v1.34 stages incoming files in a private cache directory and **publishes each one
through MediaStore** into `Download/QuickShare`. MediaStore inserts need no special
permission and the result is visible to every file manager and to the gallery. The
staging path is logged at start, publish failures are logged instead of being
swallowed, and each completed receipt is recorded in the app's History.

## 24. Sending to stock Quick Share (v1.35)

`QsSender` mirrors `QsReceiver` for the outbound direction:

1. connect to the peer's host/port from the mDNS browse;
2. send a `ConnectionRequest`, then run UKEY2 as the **client**
   (ClientInit -> ServerInit -> ClientFinish);
3. send the connection response, *then* read the peer's (reading first deadlocks
   both peers until one times out);
4. derive the D2D keys with the **client** role and switch to the encrypted layer;
5. paired-key exchange;
6. send an `IntroductionFrame` offering the files;
7. wait for the peer's sharing-layer `RESPONSE` with status ACCEPT;
8. stream each file as FILE payload chunks, ending with the split `LAST_CHUNK`
   terminator at `offset == size`;
9. send a `Disconnection` and close.

Two Samsung requirements from Bada's on-device notes are honoured, because both cause
silent discard on One UI if missed: `IntroductionFrame.use_case` must be
`NEARBY_SHARE`, and every `FileMetadata.id` must be **non-zero** (One UI keys its
receive-side bookkeeping on `id`, and the proto default of 0 silently drops the
attachment).

Wiring: `QsBrowser.deviceById` looks a discovered peer up by id,
`QsBridge.qsSendTo(deviceId)` stages the app's pending files into the cache and starts
the sender, and the Devices list routes a tap on a device labelled **Quick Share**
down this path. Completed sends are recorded in History.

**Not verified on hardware yet** - this is the first build with the outbound path.

## 25. The send button (v1.36)

Tapping a discovered Quick Share device did nothing. The cause was a wiring mistake
rather than a protocol one: `qsSendTo` had been written into the **outer** `QsBridge`
class instead of the JavaScript-facing `Api` inner class, so it was never bound to the
WebView. `QS.qsSendTo` was therefore `undefined`, calling it threw a TypeError, and the
tap produced no effect and no message. It now lives inside `Api` with
`@JavascriptInterface` - the same class of mistake as the earlier `qsCompatStatus`
bug, where a method was defined outside the class bound to JavaScript.

Two robustness changes went in with it:

- the device row matches stock peers by the `stock` flag **or** the `qs:` id prefix, so
  a missing flag can no longer route a real Quick Share device down the app's own
  protocol (which returns null and previously failed silently);
- the fall-through path now shows a toast, so a tap can never again do nothing;
- `qsSendTo` logs every step (`qsSendTo(id)`, the peer it resolved, and each send
  stage), so the Diagnostics panel shows how far a send gets.

## 26. The connection request needs more than endpoint_id (v1.37)

The send button worked and the app reported "sending", but the stock device never
prompted. The cause is stated plainly in Bada's on-device notes:

> "Match NearDrop's ConnectionRequestFrame shape. **Stock Quick Share closes the socket
> immediately if these fields are absent** - only endpoint_id and endpoint_info is not
> enough on Android 14+:
>  * **mediums** tells the receiver which transports this device can negotiate up to.
>  * **keep_alive_interval / timeout** are read by the receiver to decide its own
>    KEEP_ALIVE cadence.
>  * endpoint_name is a legacy string field; some forks still inspect it."

This implementation was sending only `endpoint_id`, `endpoint_name` and
`endpoint_info`. The request now carries the full shape an Android client sends:

| field | value |
| --- | --- |
| `endpoint_id` (1) | 4-character alphanumeric, matching the advertised instance name |
| `endpoint_name` (2) | the visible device name |
| `nonce` (4) | 0 |
| `mediums` (5) | WIFI_LAN |
| `endpoint_info` (6) | the endpoint info blob |
| `medium_metadata` (7) | `supports_5_ghz = true`, `mobile_radio = false` |
| `keep_alive_interval_millis` (8) | 10000 |
| `keep_alive_timeout_millis` (9) | 600000 |

The endpoint id was also wrong: it took the first four characters of the base64
instance name rather than the 4-byte id inside it. It is now decoded and mapped to a
4-character alphanumeric string.

## 27. The client's commitment was over the framed bytes (v1.38)

The first hardware test of the outbound path reached the handshake and then stopped:

```
send  connecting to 192.168.29.224:53601
send  connection request sent
recv  ukey2 server init 116 bytes, type=3
handshake  key exchange complete, PIN 2496
recv  their connection response none      <- clean EOF right after the handshake
```

The peer accepted the connection request (so the v1.37 request shape is correct), sent
its ServerInit with the right type, and the handshake completed - then it closed
without sending its connection response. That means it rejected the **ClientFinish**.

The cause was already established by the receive path. When this app acted as the
*server*, a real sender's commitment only ever matched the **bare** Ukey2Message:

```
commitment: framed   cf08cd1a...
commitment: bare     d358372e...   <- verified
```

The client was committing to `sha512(frame(clientFinish))` - the length-prefixed form -
so the peer's verification failed and it dropped the socket. It now commits to
`sha512(clientFinish)`, the bare message, matching the sender.

## 28. The app listed itself as a nearby device (v1.39)

The device list showed the user's own phone **twice**, both at the same address:

```
Samsung SM-S938B   192.168.29.184 · Phone · Quick Sh...    <- us
Quick Share device 192.168.29.184 · Quick Share · Qui...    <- us
Quick Share device 192.168.29.224 · Quick Share · Qu...     <- the real peer
```

Two causes:

1. **`QsBrowser` browses the same mDNS service type the app advertises**
   (`_FC9F5ED42C8A._tcp.`), so our own record comes back in the browse results and was
   added to the list as a peer.
2. The app's own UDP discovery could also surface a beacon from one of our addresses.

Both paths now drop any result whose address is one of this device's own
(`QsDiscovery.localAddresses()` enumerates every up interface, loopback included).
Bada's discovery does the same with `filterSelfPublishedInstances` and
`isLocalInterfaceRecord`.

## 29. The panel showed no devices (v1.40)

The panel (`SharePanelActivity` + `panel.js`) shares the app's bridge - it binds the
same `QsBridge.Api` - and reads `st.devices` from `getState()`, which already merges the
Quick Share browse results. Yet its device list was empty in both send and receive mode.

The cause: the mDNS browse, the UDP discovery and the transfer manager are all started
by `startEngine()`, which only the app's `app.js` called. Opening the panel alone never
started them, so nothing was discovered. `getState()` now ensures the engine is running
(all three start calls are idempotent), which fixes the list in both modes.

Two additions on top:

- the panel routes a tap on a device labelled **Quick Share** down the interop send
  path, matching the app (`qsSendTo`, matched by the `stock` flag or the `qs:` id
  prefix), and its fall-through shows a toast rather than failing silently;
- the panel's Receive view gained a **Quick Share compatibility** section - a switch
  that starts/stops the stock-Quick-Share listener from the panel, with live status
  from `qsCompatStatus()` (listening port, or why not).

## 30. Two panel bugs (v1.41)

**Send did nothing.** The panel listed stock devices correctly but tapping one
called `sendTo` - the app's own protocol - instead of `qsSendTo`. The panel's send
case had been patched in an earlier edit that was lost: the script mutated the
string, then aborted on a later assertion *before* writing the file, so the change
never landed. A device carrying `stock:true` or a `qs:` id prefix now routes to the
interop sender, and every failure mode returns a distinct string that the panel
turns into a specific toast (`no-files`, `not-found`, `no-address`).

**A receive showed no progress.** Files arrived and were published to Downloads,
but the panel stayed blank. The compat receiver's progress only went to its own
notification (`lastStatus`) - it never reached the shared state. The panel reads
`transfers` from `getState()`, which is built from `TransferManager`, and the compat
service does not use `TransferManager` at all. The service now publishes a
transfer-shaped object on every callback:

    { id:"compat", dir:"recv", peer, count, done, size, speed:0, eta:0, status }

`status` runs `waiting` (announced, PIN shown) -> `active` (bytes moving) ->
`completed`/`failed`. `getState` folds it into `transfers` while it is `waiting` or
`active`, so the panel's existing progress card renders it live with no new UI code,
and it drops out of the list once the transfer ends. Per-session counters are reset
on consent so a second transfer does not inherit the first one's totals.

The notification channel also moved from `qs_compat` to `qs_compat2` at
`IMPORTANCE_DEFAULT`: an existing channel's importance cannot be raised in code, so a
fresh id was the only way to make it visible rather than silent. If notifications are
still absent, check that POST_NOTIFICATIONS was granted.

## 31. One place for the compatibility toggle (v1.42)

v1.40 had added the Quick Share compatibility switch to the panel's Receive view as well
as Settings, which duplicated it. The panel copy is removed - the section, its `qscompat`
action handler, and the `qsStatusText()` helper - so nothing in `panel.js` references it
any more. The single toggle and its Status row stay in Settings under **Google Quick
Share**, where they were already wired: `prefs.qsCompat` -> `setSetting("qsCompat")` ->
`startQsCompat()` / `stopQsCompat()`. Removing the panel switch loses no capability,
since the panel only ever toggled the same setting.

## 32. Auto-accept, and a version that had gone stale (v1.43)

**Auto-accept follows compatibility.** Stock Google Quick Share sends without our consent
prompt - there is no dialog on the receiving side to answer - so a device that is visible
and reachable but does not auto-accept simply never completes a transfer. Turning **Quick
Share compatibility** on now also turns **Auto-accept files** on, both in the UI action
and again in the bridge's `setSetting("qsCompat")`, so the coupling holds no matter how
compat is switched on. The user can still switch auto-accept off afterwards; it is set,
not locked.

**The version was hardcoded.** Settings > About read `"version": "1.9"` from `getState`,
a literal that had not been touched since the app was at 1.9 - so a v1.42 build claimed to
be v1.9. `getState` now reads it from the installed package
(`PackageManager.getPackageInfo`): version name, version code and package name, all at
runtime. There is nothing left to forget on a release. About shows the app version with
its build number and the package name.

## 33. The live notification (v1.44)

"Live notification" here means Android 16's Live Update. `Notifier.progress` attaches a
`Notification.ProgressStyle` on SDK 36+ and sets `setStyledByProgress(true)` plus
`setShortCriticalText`, which is what turns a transfer into a live card in the status
bar. The app's own transfers have always used it through `TransferManager`.

Quick Share compat transfers bypassed it in both directions:

- **outbound** (`QsBridge.qsSendTo`) posted no notification at all. Its `QsSender`
  listener only appended to the protocol log, so a send produced no notification, no
  Live Update and no panel progress.
- **inbound** (`QsReceiveService`) used a private `build(text)` / `updateNotification`
  helper - a plain `Notification.Builder` with no `ProgressStyle` - so the receive
  notification was never live either.

Both now route through `Notifier`:

- `Notifier.buildProgress(Context, JSONObject)` is the progress-notification builder,
  extracted from `progress()` so it can be used without posting.
- **sends** post `Notifier.progress` on each update and `Notifier.completed` on success
  (gated on the notify-on-complete preference), and cancel the progress notification on
  error. A `liveSend` holder shaped like a transfer is folded into `getState`'s
  `transfers`, so the panel's progress card covers sends too.
- **receives** call `updateLiveNotification()`, which builds the progress notification
  and re-posts it through `startForeground(NOTIF_ID, n, type)`. That keeps exactly one
  notification for the service - the live one - instead of a plain one plus a second
  progress notification. It fires `Notifier.completed` on finish.

## 34. The stock device's name in the browse list (v1.45)

A stock Quick Share peer showed up as "Quick Share device", with "Quick Share" printed
twice on the subtitle line. Both came from `QsBrowser.toDevice`.

**The name was gated on the wrong bit.** The parse read:

    int flags = decoded[0] & 0xFF;
    boolean hasName = (flags & 0x10) == 0;   // wrong
    if (hasName) { ...read name... }

`endpoint_info` is `flags | 16-byte identity | name length | name`, and bit 4 of the
flags byte is the **visible** flag - our own builder sets it from `visible`. So the gate
skipped the name for every device that was visible, which is every device you can
discover. A peer with `visible = 1` never had its name read, and the row fell back to the
placeholder. The receive path (`QsReceiver`) reads the same layout with no gate at all,
which is why incoming transfers showed names while the browse list did not.

The gate is gone; the name is read whenever the record is long enough, and a name that
is not printable triggers a second attempt at the alternate layout - some advertisers
omit the length byte and run the name to the end of the record.

**Two more small faults in the same method.**

- the device type was read as `(flags & 0x07) >> 1`, which masks bits 0-2 and then shifts,
  so it could only ever see bits 1-2. The type occupies bits 1-3: `(flags >> 1) & 0x07`.
- `deviceTypeLabel` returned "Quick Share" for an unknown type, and the row already
  appends "· Quick Share" for a stock peer, so the subtitle read
  "192.168.29.224 · Quick Share · Quick Share". Unknown types now return nothing.

## 35. v1.45 was wrong, and what the `n` record actually holds (v1.46)

v1.45 removed the `(flags & 0x10) == 0` gate on the name, on the theory that bit 4 was
the visible flag and the gate was skipping names for visible devices. Checking NearDrop's
`EndpointInfo.init(data:)` shows the opposite: it gates on exactly the same bit.

    let hasName = (data[0] & 0x10) == 0
    if hasName { deviceNameLength = Int(data[17]); ... } else { deviceName = nil }

So bit 4 set means the device is **not advertising a name**, and the gate was right. It
also means NearDrop only lists a discovered device when a name was present:

    if let _ = endpointInfo.name { deviceInfo = addFoundDevice(...) }

v1.45 is therefore reverted. Reading the name unconditionally on a nameless record just
picks up identity bytes, which fail the printable check and fall back to the placeholder
anyway - which is exactly what was still being seen.

**What changed in v1.46.** The parse tries the record at offset 0 and at offset 1.
Android's `NsdServiceInfo.getAttributes()` is inconsistent about the TXT value - some
versions return the attribute value, others leave its length byte on the front - and a
single byte of shift is the difference between a name and none. The `hasName` rule is
restored, the device type is read as `(flags >> 1) & 0x07` (bits 1-3; the old
`(flags & 0x07) >> 1` could only see bits 1-2), and an unknown type no longer returns
"Quick Share" as a label - the row already appends that for a stock peer.

The parse now logs both the raw `n` value and the decoded bytes to Diagnostics. If the
name is still missing for a given peer, that log says definitively whether the peer
broadcast a name at all: `hasName` clear with a readable name inside is our bug, while a
record with bit 4 set means the peer has nothing to show and no client could display it
without connecting.

## 36. Auto-accept off now prompts, with the PIN (v1.47)

`QsReceiveService`'s `onConsent` returned `true` on every call - it had to, in the first
cut, because stock Quick Share has no consent prompt of its own and a receiver that
never answers simply times out the sender. But that made the Auto-accept preference
meaningless on the compat path: turning it off changed nothing, because the service
never consulted it.

`onConsent` now reads `Store.autoAccept()`:

- **on** - accept immediately, exactly as before;
- **off** - hold the connection open and ask. The service publishes the request
  (`id: "qsconsent"`, peer, PIN, file names, count, total size), posts the incoming
  notification with the PIN in it and Accept / Decline buttons, then blocks on a monitor
  for up to two minutes. It returns `true` only if the user accepted, and logs
  "declined/timed out" otherwise.

The request is folded into `getState`'s `incoming` array, so it renders in the same
Incoming requests card the app's own transfers use - the card gained a PIN line. Its
buttons call `qsConsentAccept()` / `qsConsentReject()`, which resolve the wait through
`QsReceiveService.resolveConsent`; the app's own transfers keep using `accept(id)` /
`reject(id)`. The notification's buttons land in the same place: `NotifyReceiver` now
branches on the `qsconsent` id and calls `resolveConsent` instead of `TransferManager`.

The PIN is the one the sender is displaying, read from the connection's `onConsent`
callback, so the user can compare it against the sending phone before accepting.

## 37. Different networks: Wi-Fi Direct (v1.48)

Everything up to now assumed one LAN. Discovery is mDNS - a link-local multicast that
never crosses a subnet - and the transfer is a TCP connection to the peer's private
address. Two phones on different Wi-Fi networks, or one on mobile data, have no route to
each other's private address and never see each other's mDNS, so the app could not help
them at all.

`QsWifiDirect` closes that gap using Wi-Fi Direct (P2P), the same radio technique stock
Quick Share uses for its direct path:

- **discovery** - `WifiP2pManager.discoverPeers` finds P2P peers; they are turned into
  the app's device shape (`id: "p2p:<mac>"`, `direct: true`) and merged into the same
  device list as the LAN and stock peers, labelled **Direct link**;
- **link setup** - tapping one calls `connect()`, which forms a group. On
  `WIFI_P2P_CONNECTION_CHANGED_ACTION` the app reads `WifiP2pInfo`: the group owner is
  always reachable at `192.168.49.1`, and the client is handed the next address;
- **transfer** - the peer's address on the link is written into the device record and
  passed to `TransferManager.sendTo`, the existing LAN send. **No transfer code was
  touched** - the whole feature is discovery plus link setup, so a bug here cannot
  regress the LAN path.

Permissions: `ACCESS_FINE_LOCATION` (maxSdkVersion 32 - required for P2P discovery up to
Android 12), `NEARBY_WIFI_DEVICES` with `neverForLocation` from Android 13, plus
`ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE`.

**What it does not do.** Wi-Fi Direct is still a radio link, so the devices must be
within tens of metres. It answers "different networks", not "different cities". Genuinely
remote transfer needs a relay server, NAT traversal with a signalling channel, or a VPN
overlay that puts both devices on one virtual LAN - none of which this app has. It also
does not interoperate with stock Quick Share's own direct path; that is Google's
implementation of the same radio, not a protocol we can speak.

## 38. Mobile data, and why nothing is listed (v1.49)

A phone on mobile data with Wi-Fi off lists no devices. That is correct behaviour, not a
fault, and it is worth writing down because the app previously gave no hint of it: the
device list just said "Scanning the network..." forever, which looks like a failure.

Both discovery paths need the Wi-Fi **radio**:

- the app's own discovery is mDNS - multicast on the Wi-Fi interface;
- the Wi-Fi Direct path from v1.48 is itself a Wi-Fi radio link.

Mobile data carries neither. Wi-Fi Direct does **not** need a Wi-Fi network - it only
needs the radio switched on - so "mobile data, Wi-Fi on but not connected" works, while
"mobile data, Wi-Fi off" cannot discover anything at any layer.

**What changed.** `QsWifiDirect.wifiEnabled(Context)` reads `WifiManager.isWifiEnabled()`;
`getState` publishes it as `wifiOn`; both the app and the panel render a **Wi-Fi is off**
card with a **Turn on Wi-Fi** button (`Settings.ACTION_WIFI_SETTINGS`) in place of the
empty list, and the header reads "Wi-Fi is off" rather than "Scanning the network...".
The wording is explicit that no network connection is required. `QsWifiDirect` also
registers for `WifiManager.WIFI_STATE_CHANGED_ACTION` and calls `discover()` the moment
the radio returns, so peers appear without restarting the app.

## 39. Two different reasons for an empty list (v1.50)

v1.49 reported only whether the Wi-Fi **radio** was on. There is a second case that looks
identical in the UI but has a different fix, and it is the one that decides stock
Quick Share interop:

| state | this app to this app | stock Quick Share to this app |
|---|---|---|
| Wi-Fi radio off | no | no |
| radio on, not joined to a network | yes, over Wi-Fi Direct | **no** |
| radio on, same network as the other phone | yes | yes |

The middle row is the one that trips people up. We advertise ourselves over mDNS
(`_FC9F5ED42C8A._tcp.`), and mDNS only exists on a Wi-Fi network. Google's app finds
peers by Bluetooth and Wi-Fi Direct, but to *resolve us* it has to see our mDNS
advertisement - which is not there when we are not joined to a network. So a phone on
mobile data with Wi-Fi on but unconnected can still exchange files with another phone
running this app (the Wi-Fi Direct path forms its own link) but is invisible to stock
Quick Share.

`QsWifiDirect.wifiOnNetwork(Context)` reads `WifiManager.getConnectionInfo().getNetworkId()`
to tell the two apart. `getState` publishes it as `wifiNetwork`, and both the app and the
panel show a **Not on a Wi-Fi network** card for that case - stating that direct-link
transfers still work, but that being found by the stock app needs a shared network, or a
hotspot one phone shares and the other joins.

## 40. Received files were filed with the wrong MIME type (v1.51)

A zip sent over Quick Share arrived unusable - the file manager offered no extract
action and it needed a specialised tool to open. The payload was fine; its **type** was
wrong.

`mimeOf` was a short hardcoded list:

    jpg/jpeg -> image/jpeg      png -> image/png      mp4 -> video/mp4
    pdf      -> application/pdf  apk -> application/vnd.android.package-archive
    txt/md   -> text/plain       everything else -> application/octet-stream

So a zip was filed as `application/octet-stream`. Android decides what a file *is* from
its MIME type, so an octet-stream zip gets no "extract" affordance in any file manager -
the reported "requires advanced tools" behaviour.

Both copies of the method - `QsReceiveService.mimeOf`, used when publishing into
MediaStore, and `QsBridge.mimeOf`, used for the outgoing file metadata - now go through
`android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)`, the system's
own extension table, which covers zip, rar, 7z, docx, xlsx and hundreds more. The
hardcoded entries stay as a fallback for anything the table misses.

**The bytes were checked, not assumed.** Three places could plausibly have truncated a
large binary payload; all three are correct:

- the sender slices each frame to the exact bytes read - `byte[] body = new byte[n]` -
  rather than sending the whole 512 KB buffer on a short final read;
- the receiver closes each file's stream before reporting it saved, so nothing is
  reported complete while still unflushed;
- the publish copy loop reads and writes byte-for-byte.

The receiver now also compares the announced size with the bytes actually written and
logs any shortfall, so a genuinely truncated transfer would be visible in Diagnostics
rather than guessed at.

## 41. Can it work without Wi-Fi? (v1.52)

The honest answer, having checked Google's own documentation rather than reasoned about
it: **stock Quick Share does not send nearby files over mobile data either.** Its nearby
path is Bluetooth for discovery plus a direct Wi-Fi link for the transfer - "you don't
need to be on the same Wi-Fi network or use cellular data", because it uses neither the
router nor the mobile network, just the Wi-Fi radio between the two phones. Its
internet path - to your own devices, or to iPhone/iPad/macOS - runs through **Google's
servers** and your Google account, end-to-end encrypted with the file held for 24 hours.
That is infrastructure, not protocol, and a reverse-engineered client has no equivalent.

So the shape of the answer is:

| want | needs | possible here |
|---|---|---|
| nearby, no router | Wi-Fi radio (Direct) | yes - v1.48 |
| nearby, no Wi-Fi at all | Bluetooth | possible, slow, proximity only |
| far apart / mobile data | a server: relay, NAT traversal, or a VPN | yes, if a path exists |

**v1.52 adds the escape hatch: Connect by address.** Both the app and the panel take a
hostname/address and a port and send over the ordinary transfer with that address
substituted - `TransferManager.sendTo` is unchanged, only the address is typed in. This
covers every case where a routable path exists but the app cannot discover it:

- a **VPN overlay** (Tailscale, ZeroTier) - both phones get a routable address and the
  transfer runs over it, **mobile data included**; this is the direct answer to the
  question, and it needs no server of ours, only the VPN the user installs;
- a **hotspot**, which gives a shared network without any router;
- a host with a forwarded port, or a **relay the user runs**;
- anything else that can reach the port.

`getState` also exposes every IPv4 address the device answers on, so the receiving phone
can read out the address to type.

## 42. Bluetooth: no Wi-Fi, no internet (v1.53)

Every transport so far needs either a Wi-Fi radio (Wi-Fi Direct for the direct link,
mDNS for the stock interop) or a routable address (LAN, VPN overlay, relay). Bluetooth
needs **neither** - two paired phones within about ten metres can exchange files with
Wi-Fi off and no data connection at all.

**The transfer was not rewritten.** `TransferManager` was built against `Socket`, but an
audit showed it touches the socket in only four places: connect, `setTcpNoDelay`, the two
streams, and close. So the method was generalised to a `java.io.Closeable` link plus an
`InputStream`/`OutputStream`, and the connect step now chooses:

    if (dev has "bt")  -> QsBluetooth.openLink(...)   // RFCOMM
    else               -> new Socket(ip, port)        // TCP

Everything above the streams - the offer, the accept handshake, the file framing,
progress, cancellation, history - is byte-for-byte the code that is already verified on
hardware. `handleIncoming` was split the same way, and `acceptLink(...)` lets the
Bluetooth accept loop hand a connection to the same receiver the LAN server uses.

`QsBluetooth` does discovery and linking only:

- `bondedDevices` lists paired phones as `bt:<mac>` records, merged into the same device
  list and labelled **Bluetooth**;
- `openLink` connects an RFCOMM socket to a paired device using a fixed service UUID
  (both ends are this app, so any fixed UUID works);
- `startServer` listens on that UUID and passes each accepted socket to
  `TransferManager.acceptLink`.

Permissions: `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` (with `neverForLocation`) from
Android 12, and the legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` pair up to API 30.

**Caveats, stated plainly.** RFCOMM is slow - roughly 100-200 KB/s, so a 100 MB file is
minutes, not seconds. And Android will not connect to an **unpaired** device without a
pairing prompt, so both phones must be paired in system settings first. This is also not
how stock Quick Share uses Bluetooth: it uses Bluetooth only to *discover* peers and
transfers over Wi-Fi. There is no way to make the stock app transfer over Bluetooth, so
this path is this-app-to-this-app.

## 43. An empty Bluetooth list had two causes, both silent (v1.54)

v1.53 read only `BluetoothAdapter.getBondedDevices()` - phones already **paired** - and
only when the adapter was on. So there were two ways to see nothing, and the app
explained neither:

- **Bluetooth off.** `bondedDevices` returns an empty array, the merged device list is
  empty, and the UI shows the same "Scanning the network..." it shows when Wi-Fi is off.
- **Nothing paired.** Even with Bluetooth on, the list is empty until the two phones have
  been paired in system settings, which the user has no reason to have done.

Both now surface, and the second is removed as a prerequisite:

- `QsBluetooth.state()` reports `supported`, `enabled`, `paired`, `found` and `scanning`;
  `getState` publishes it as `btState`; and with Wi-Fi off the app and panel render a
  card that says which case applies, with a **Scan for phones** button and a shortcut to
  Bluetooth settings.
- `startDiscovery()` now scans with `BluetoothAdapter.startDiscovery()` and collects
  `ACTION_FOUND` results, so **unpaired** phones appear in the list too, tagged
  `unpaired`. `btPairAndSend` calls `createBond()` and waits for
  `ACTION_BOND_STATE_CHANGED` (up to 45 s, since the user has to answer Android's own
  pairing prompt on both phones) before opening the RFCOMM link.

The device list is now paired phones plus whatever the scan found, and `deviceById`
searches both. A phone still has to have Bluetooth on, and to be discoverable if it is
not already paired - neither of which an app can do on the user's behalf.

## 44. Reverted to 1.48

v1.48 is the Wi-Fi Direct release: two phones that are near each other can transfer
without sharing a Wi-Fi network. Everything added after it has been backed out:

| version | what it added | state |
|---|---|---|
| 1.49 | Wi-Fi radio state + "Wi-Fi is off" card | removed |
| 1.50 | "Not on a Wi-Fi network" card | removed |
| 1.51 | zip MIME fix | **kept** |
| 1.52 | Connect by address (VPN / relay escape hatch) | removed |
| 1.53 | Bluetooth transport | removed |
| 1.54 | Bluetooth scan, pairing, state card | removed |

`QsBluetooth.java` is deleted, along with its bridge methods (`btScan`,
`btPairAndSend`, `openBluetoothSettings`), its UI (the Bluetooth card, the `bt:` send
routing, the "Bluetooth" device label, `btState`) and its manifest permissions.
`QsBridge`'s `sendTo` no longer resolves `bt:` ids, `QsWifiDirect` no longer reports
Wi-Fi state, `TransferManager` is back to a plain `Socket` on both sides, and the
manual-address methods (`localAddressesList`, `sendToAddress`) are gone.

**Kept deliberately:** the zip MIME fix from 1.51. It is a bug fix, not a feature - a
received zip filed as `application/octet-stream` gets no "extract" action anywhere - and
it has no bearing on discovery, so removing it would only reintroduce a problem that was
reported. The transfer's internal `Socket`-to-stream refactor is also retained: it is
behaviour-identical and was already the shape of the receive side.

Both are easy to revert if you want a strictly faithful 1.48.

## 45. Toasts hidden, panel toggle removed (v1.49)

Two UI changes, both on top of the reverted 1.48.

**Toasts are hidden.** `toast()` in both `app.js` and `panel.js` now returns immediately.
The function is deliberately kept rather than deleted, so the ~40 call sites stay as they
are and the change is a single line per file to reverse. This silences every toast,
including the failure ones - "Could not link", "That device is no longer nearby" and so
on - so if a transfer fails there is no longer any on-screen hint; the protocol log in
Settings > Diagnostics is the only signal left.

**The panel's "Visible to nearby devices" row is removed.** It was the one setting the
panel carried; the identical control remains in Settings, so nothing is lost. The panel's
Receive tab is now progress, the ready badge, the device name and address, and Incoming
requests.

## 46. History records, and the peer's name (v1.50)

**History showed no file names, and no size.** `TransferManager.historyEntry` writes a
`files` array, the real `size` and the peer name. The Quick Share paths did not: the
receiver's `recordHistory` wrote only a count and a size, and the sender's inline record
hardcoded `size: 0` and the string `"Quick Share device"`. That is exactly the difference
visible in the UI - a Quick Share row read `1 file(s) · 0 B` and its sheet had no file
list, while a Wi-Fi row read `1 file(s) · 200 KB`.

Both now write the same shape. The receiver keeps the file names from `onConsent` and
writes them into the record; the sender writes its entry names and sums their sizes, and
uses the real peer name.

**The peer's real name.** This is the harder half. Stock Quick Share does not always put
its name in the mDNS `n` record - bit 4 of the flags byte is a "no name" marker, and
NearDrop gates on the same bit - so for such a device the browse list has literally
nothing to read. Two mitigations:

- `QsSender` now parses the peer's `endpoint_info` (field 6) out of the **connection
  response** when the peer includes one, which yields the real name on an outbound
  transfer without waiting for a return transfer. It is best-effort: our own response
  omits field 6, and a peer may too.
- every name learned - from that response, or from the `endpoint_info` of an inbound
  connection - is cached against the peer's **address** (`Store.peerNameFor` /
  `rememberPeerName`). Address is the one key the mDNS browse result and a live
  connection share. `QsBrowser` falls back to it when the record has no name, so after a
  single exchange the list shows the real name.

A device we have never exchanged with can still read "Quick Share device" - not because
the parse is wrong, but because it advertised no name and we have no other source.

## 47. The hero card in light mode (v1.51)

The share hero - the top card on the Share tab, carrying the device name, the
"Listening" chip and the Choose files / Send to a device buttons - was pinned black for
every theme:

    /* the share hero is a black card in every theme */
    .shero, .dlhero.shero { background: #0A0A0B !important; }

Deliberate, and fine on dark and pitch, but in light mode it left a black card on a white
page.

Themes are applied as classes on `<body>`: `dark` and `pitch` are added, and **light is
the absence of both**. So the correction is scoped to `body:not(.dark):not(.pitch)`,
which cannot leak into the other two themes:

    body:not(.dark):not(.pitch) .shero { background: #FFFFFF !important; ... }

with dark text, muted chips and ghost buttons, and a much weaker `::after` sheen - the
drifting accent glow was tuned against black and is far too strong on white. The
`!important` is needed to override the original rule, which also uses it.

## 48. Compatibility on by default, and the receive card (v1.52)

**Compatibility now defaults on.** `Store.qsCompat()` returned `false`, so the stock
interop was inert until the user found the toggle in Settings. The default is now `true`;
the toggle still turns it off.

**The receive card was only shown when a decision was needed.** `onConsent` read:

    if (new Store(this).autoAccept()) { updateLiveNotification(); return true; }

so with auto-accept on nothing was published - a transfer simply began, with no
indication of who or what. The card is now assembled and published first in **both**
modes, and only afterwards does the auto path return `true`. The difference is the
buttons: with auto-accept off the card carries the PIN and Accept/Decline and the
connection blocks on the answer; with it on the card carries no buttons, reads
"Receiving…", and is cleared when the transfer finishes.

**The card describes what is coming.** `fileKind` maps the offered names to a phrase -
"1 image", "2 videos", "3 files" - and the card reads "<device> wants to share 1 image
with you.", the same wording the stock prompt uses. The PIN appears only when a decision
is required, since with auto-accept on there is nothing to compare it against.

The request payload gained two fields for this: `kind` (the phrase) and `auto` (whether
the transfer is proceeding without a decision), both consumed by the app's and the
panel's incoming cards.

## 49. The incoming card, trimmed (v1.53)

The request card had accumulated four lines of detail:

    From NOTA's Galaxy
    NOTA's Galaxy wants to share 1 file with you.
    1 file(s) · 821 KB
    PIN 7398
    QuickShare.apk

Only three things matter when deciding: how many files, what they are, and the PIN. The
card is now:

    PIN 7398          <- centred, 26px, letter-spaced, accent colour
    1 file(s)
    QuickShare.apk
    [Decline] [Accept]

Dropped: the "From <device>" heading (redundant - the sender's name is on the progress
card above), the "wants to share ... with you" sentence, and the transfer size. The
`.pinbig` class is in `app.css`, which both the app and the panel load, so one rule
covers both.

## 50. Developer mode, opening files, and the receive progress (v1.54)

**The protocol log is now behind a developer mode.** The Diagnostics section - the
protocol log with Copy and Clear - was visible to every user in Settings. It now renders
only when `devMode()` is true, and that flag is set by **seven taps on the build number**
in Settings > About, the same gesture Android uses for its own developer options. The row
shows "N more taps" while counting so the taps do not feel ignored (toasts are silenced,
so the count had to go somewhere visible). `qs_devtaps` and `qs_devmode` live in
`localStorage`, so the unlock survives a restart.

**History entries can open their file.** `showHistoryDetail` built Open buttons from the
record's `saved` array - the uris a LAN transfer stores. Quick Share records have no such
array: their files are published into Downloads/QuickShare through MediaStore, so the
sheet showed nothing to open. They now fall back to an Open button per file name, backed
by `QsBridge.openHistoryFile`, which queries MediaStore Downloads for that display name
(newest first) and fires an `ACTION_VIEW` with the resolved content uri.

**The receive progress.** Two changes to the panel's Receive tab:

- while a request is waiting on the user's answer, `receiveView` suppresses the progress
  card entirely - nothing is being received yet, so showing "Receiving from ... 0%" was
  misleading. The request card is the only thing on screen.
- once accepted, the progress is a single **centred** ring (`centeredProgress`) with the
  percentage and the current file name, and the count / size / speed / ETA lines are
  dropped. The receiver's live state now carries `current`, the file being written, so
  the ring can name it.

## 51. Pop-up, strict mode, and smooth progress (v1.55)

**The panel is brought up when a transfer arrives.** `QsReceiveService.onConsent` now
calls `popPanel()`, which starts `SharePanelActivity` with
`NEW_TASK | SINGLE_TOP | REORDER_TO_FRONT`, so the user sees the Accept/Decline card (or
the receiving progress, in auto-accept mode) without touching the phone.

Android blocks background activity starts, so this only works with **Display over other
apps**. The manifest declares `SYSTEM_ALERT_WINDOW`, Settings shows a row to grant it when
it is missing, and `canPopup()` reads `Settings.canDrawOverlays`. If it is not granted
`popPanel()` logs the fact and returns - the incoming notification, which already carries
Accept/Decline, is the fallback.

**Strict mode.** `Store.strictMode()` plus a Background toggle. `QsBridge` tracks whether
the UI is on screen: `startEngine()` (called from both activities' `onResume`) sets
`uiVisible`, and `onVisibleUiGone()` (both `onStop`s) clears it and, in strict mode, calls
`stopQsCompat()`. So the listener exists only while the app or panel is actually visible,
and nothing runs behind the user's back. Toggling it in Settings takes effect immediately
- turning it on while nothing is on screen stops the listener at once.

**Progress smoothing.** `updateProgressInPlace` already avoided a full re-render and set
`--p` on the existing ring, and `.ring` transitions `--p` (registered via `@property`).
What still jumped was the **number**: `b.textContent = pct + '%'` assigns the new value
outright, and since the bridge is polled every 1.5s the figure went 1% -> 21%. A `setPct`
helper now tweens the displayed value to the target over ~700ms with
`requestAnimationFrame`, so the figure climbs continuously between polls. It is used by
the in-place updater and by the centred card.

**Sending gets the centred progress.** `sendView` now shows `centeredProgress` for an
active send, the same view the receive tab uses, instead of a card at the top. The centred
ring was reduced from 116px to 92px.

## 52. Panel animation, spacing, and two tab bugs (v1.56)

**The pop-up had no animation.** `PanelTheme` declared
`android:windowAnimationStyle = @null`, and `SharePanelActivity.finish()` called
`overridePendingTransition(0, 0)`. So the panel appeared and vanished with no transition,
which is what read as "broken". It now uses `@style/PanelAnim`, backed by
`res/anim/panel_in.xml` (translate 14% -> 0 over 280ms, decelerate, plus a 200ms fade) and
`panel_out.xml` (the reverse, accelerate). The activity's `overridePendingTransition` now
references the same pair instead of zeroing them.

**The Send tab showed a receive.** `activeTransfers()` was:

    return (S.transfers || []).filter(function (t) {
      return t.status === 'active' || t.status === 'waiting';
    });

with no direction test, and `sendView` had just been changed to render
`centeredProgress(activeTransfers()[0])`. Any live transfer - a receive included - therefore
rendered under Send. The function now takes `'send'` or `'recv'` (or nothing for either),
and `sendView`, `receiveView` and `progressHtml` each pass their own direction.

**The panel opened on the wrong tab.** `S.mode` defaults to `'send'`, and the only code
that switched to `'receive'` ran when a live `incoming` event arrived - not when the panel
was *launched* by an incoming transfer, which is the pop-up path added in v1.55. `refresh()`
now checks on its first fetch: if `st.incoming` is non-empty, it lands on Receive.

**Spacing.** `#pbody` forced `min-height: 190px`, and the empty-state, device rows, badge
and buttons all had generous padding, leaving the large gaps visible in the screenshots.
The minimum is gone, the paddings are tightened, and a `.panel-body` block pulls the
section headings closer to their cards.

**Progress easing.** The tween runs 900ms with an ease-out cubic (`1 - (1-k)^3`) rather
than a linear ramp, and `.ring`'s `--p` transition is 800ms on a matching curve, so the
ring and the number arrive together.

## 53. The Send-tab regression, transitions, and text selection (v1.57)

**The Send tab showed a receive - again.** v1.56 gave `activeTransfers` a direction
argument, but left this in `sendView`:

    var h = sending.length ? centeredProgress(sending[0]) : progressHtml();

`progressHtml()` renders **receives** (`activeTransfers('recv')`), so the fallback put an
inbound transfer back under Send - exactly the bug the direction filter had just fixed.
The fallback is removed: Send renders the centred progress for an active send and nothing
otherwise.

**Transitions.** The panel replaces its body HTML on every state change, so a view that is
leaving cannot animate out - only the arriving one can animate in. So:

- `.centerprog-in` fades and scales the centred progress in over 340ms;
- `.pdev` (the request card) eases in over 260ms;
- `#pbody` carries a short opacity transition.

That is what makes Accept -> progress, and progress -> back-to-normal, read as a
transition instead of a snap.

**Text selection.** The screen recording shows a long press on the PIN bringing up
Android's selection toolbar - Translate, Copy, Select all. On a control panel that is
unwanted, so `user-select: none` now applies to `.panel-body` and everything inside it,
with `.qslog` (the protocol log) exempted because that is genuinely worth selecting.

## 54. Smoothness pass (v1.58)

Motion only - no new UI - and every rule animates just `transform` and `opacity` so the
work stays on the compositor and cannot trigger layout.

**One easing family.** `--ease-out` (decelerating, for arrivals), `--ease-in`
(accelerating, for exits) and `--ease-soft` (for controls), with `--dur-1/2/3`. Existing
transitions that used `ease` or `linear` now share these, so the app moves as one thing
rather than several.

**Press feedback.** `.btn`, `.lrow`, `.pdev`, `.switch`, `.accentdot`, `.chip`,
`.iconbtn` and `.segbtn` all scale slightly on `:active`. Previously only `.lrow` reacted
to a tap, which made most of the interface feel dead.

**Switches glide** between states rather than jumping.

**A route change fades.** Navigation was intentionally instant (a full entrance read as a
refresh), so this is deliberately small: `#view` gets `viewSwap` - 400ms fade plus a 6px
rise - and only when `S.route` actually changes. A re-render of the same route does not
animate. The class is removed and re-added with a forced reflow so the animation restarts.

**Arrivals are scoped.** This is the important one for smoothness: a blanket entrance
animation on `.card`/`.lrow`/`.pdev` would replay on **every** state re-render - and the
panel polls every 1.5s - so the list would visibly pop on each refresh. That is jitter,
the opposite of the goal. The rule now targets only elements that genuinely appear: the
request card (`.reqcard`), the centred progress (`.centerprog`) and empty states
(`.pempty`), plus an opt-in `.card.stagger` for the few places a list does arrive at once.

**`prefers-reduced-motion`** collapses every animation and transition to nothing when the
user has asked the system for less motion.

## 55. Strict mode stopped the receiver while the panel was open (v1.59)

The report: with strict mode on, **sending** worked and **receiving** did not, and stock
Quick Share could not see the device at all. That split is the clue. Sending uses the mDNS
**browser** (`QsBrowser`), which is created by the bridge and lives as long as the UI does.
Receiving needs the **service** (`QsReceiveService`) that advertises us over mDNS and
accepts the TCP session. Strict mode was taking the service down.

**Why.** Opening the panel over the app fires two activity callbacks - `MainActivity.onStop`
and `SharePanelActivity.onResume` - and their order is not guaranteed. Both call into the
bridge (`onVisibleUiGone` and `startEngine`), and the strict-mode code in `setUiVisible`
performed the stop **immediately**:

    if (v && st.qsCompat()) startQsCompat();
    else if (!v && st.strictMode()) stopQsCompat();

So when the app's `onStop` arrived *after* the panel's `onResume`, the start was undone a
moment later. Nothing advertised, and the stock app had nothing to resolve.

**The fix, in two parts:**

- **the stop is debounced.** A hidden UI posts `stopWhenStillHidden` 1.5s out instead of
  stopping at once; any UI becoming visible in that window removes the callback. The
  listener therefore survives the transition.
- **a stale "gone" is ignored.** `startEngine()` stamps `lastVisibleAt`, and
  `onVisibleUiGone()` returns early if it arrives within 1.2s of that stamp - it is the
  outgoing activity, not the incoming one. This removes the race rather than narrowing it.

`onVisibleUiGone` and the `strictMode` setting both use the same path, and each branch
logs under `strict` so Diagnostics shows what actually happened.

## 56. Strict mode never actually shut down (v1.60)

v1.59 fixed the race that stopped the listener during an activity transition, but left a
path where the stop never ran at all - so with strict mode on, the app kept running after
being closed and swiped from recents.

**Who owned the visibility flag.** `QsBridge.getState()` opens with:

    try { startEngine(); } catch (Throwable ignored) {}

which is deliberate - the panel shares the bridge and would otherwise discover nothing.
But `startEngine()` also set `uiVisible = true` and stamped `lastVisibleAt`. Since
`getState()` is polled by the WebView continuously, the **closing WebView's last poll**
re-marked the UI visible. `stopWhenStillHidden` checks `!uiVisible`, so it declined to
fire, and the foreground service - and its notification - lived on. Swiping from recents
changed nothing, because a foreground service is not tied to the activity's lifetime.

Second problem in the same area: `startEngine()` never touched `QsReceiveService`. The
listener was started by the `qsCompat` setting alone, with no regard for strict mode or
visibility, so enabling compatibility was enough to bring it up permanently.

**The fixes.**

- `startEngine()` no longer claims visibility - only the activities do, by calling
  `setUiVisible(true)`, which now also starts the engine. One entry point, one owner.
- `setUiVisible(true)` stamps `lastVisibleAt` and starts the engine; a hidden UI still
  schedules the stop.
- the `qsCompat` setting will not start the listener when strict mode is on and nothing is
  on screen.
- `stopWhenStillHidden` is now a full teardown - listener, engine, and the Wi-Fi Direct
  receiver - rather than just the listener. An unregistered `BroadcastReceiver` is by
  itself enough to keep a process alive.
- `stopEngine()` releases the Wi-Fi Direct receiver too, so the non-strict path is equally
  clean.

## 57. The Credits tab (v1.61)

Settings gained a segmented **Settings | Credits** control, cloned from Gitly's - same
placement, same shape, same card language. The content is ours:

- **app card** - the name, and the version described: `Version <name> (build <n>)`, with a
  line explaining that the name is the release while the build number rises every build.
  Two more lines state that the app is unaffiliated with Google or Samsung and that the
  Quick Share compatibility is an independent reimplementation.
- **Developer** - the name from the user's profile, and a note that the app is built from
  Java, JavaScript, HTML, CSS and XML with no third-party libraries. Gitly's card also
  carries age, profession and contact buttons; those are the developer's to supply, so
  none were invented.
- **Languages used** - each language with what it does: Java (Android side, protocol,
  receivers and services), JavaScript (the WebView interface and its bridge), HTML
  (structure of the app and the panel), CSS (styling, themes, accents, animation) and XML
  (manifest, layouts, styles, icons). A chip row follows, as Gitly's does.
- **Compatibility** - the two interop paths, described plainly.

## 58. An incoming request moves the panel to Receive (v1.62)

The request, once clarified: when the panel is on **Send** and the user is not actually
sending, an arriving transfer should bring the **Receive** tab forward so there is nothing
to tap. Explicitly **not** auto-sending - that was the other reading, and the answer was
to ask before switching device, so nothing is sent without the user's own action.

**Why it did not happen before.** The panel switched to Receive in its `incoming` **event**
handler. But the Quick Share compatibility path does not push that event: the request is
assembled in `QsReceiveService` and surfaces through `getState()`, which the panel merely
**polls**. So a compat request never triggered the switch.

The check now lives in `refresh()`: a request whose id is not already in `S.incoming` is
new, and if the user is not sending - `S.pending` empty and no active send - the mode
becomes `'receive'`. It is deliberately suppressed mid-send, so a queued or running send
is never interrupted.

## 59. The Credits tab clones Gitly's developer card (v1.63)

v1.61 used the profile name and omitted the rest of Gitly's Developer card. The developer
is the same person, so the card is now Gitly's, verbatim:

- the logo at 96px with the same rounded-corner and glow treatment;
- **Name** - BonkerUnkil (Bonki);
- **Age** - 20;
- **Profession** - Coding, problem solving, and more;
- the **Connect** section, with Gitly's Telegram and GitHub brand SVGs inlined and the same
  two buttons - Telegram · @BonkerUnkilBonki, GitHub · @BonkerUnkilBonki - both opening
  through a new `QsBridge.openUrl(String)`.

Two smaller things: the app card's list items carried a `white-space` rule that ellipsed
them in the screenshot ("Version name is the release; the build number..."), so they now
wrap; and the languages list keeps its descriptions.

## 60. Credits wrapping, the profession, and the updater (v1.64)

**The truncated credits lines.** v1.63 set `white-space: normal` on the list items, but
the screenshot the user sent was **build 64 - v1.62**, taken before that build was
installed, so the fix had never run. The underlying cause is that `.lsub` is globally
`white-space: nowrap; overflow: hidden; text-overflow: ellipsis`, which ellipsed every
line of the app card. Rather than rely on an inline style beating a stylesheet rule, the
override is now a rule of its own:

    .creditslist li { white-space: normal !important; overflow: visible !important;
                      text-overflow: clip !important; }

**Profession** now reads "AI Prompt Engineering, UI/UX Designer, Coding".

**App updates, cloned from Gitly's.** The repo is `BonkerUnkilBonki/QuickShare`. Settings
gained the same card Gitly has: auto-download, auto-install (on by default), an Installed
vs latest-on-GitHub row, and a Check for updates button.

The check is done **natively** - `QsBridge.checkForUpdate()` - rather than with `fetch` in
the page. The UI is served from `file:///android_asset/www/`, and a cross-origin request
from that origin is not reliable, so the release list is fetched over HTTP in Java:

- `https://api.github.com/repos/BonkerUnkilBonki/QuickShare/releases`, newest non-draft
  release carrying an `.apk` asset;
- the tag is compared with the installed `versionName` by splitting both into number
  groups, so `v1.64` beats `1.63` regardless of the `v`;
- `available`, `latest`, `url` and `notes` come back as JSON.

`downloadAndInstall(url, name)` then streams the APK to Downloads on a worker thread and
fires an `ACTION_VIEW` with the package MIME type, which is what opens Android's installer.
That needs `REQUEST_INSTALL_PACKAGES`, now declared; Android asks the user once to allow
installing unknown apps from this app.

## 61. PeerZap: the rename and the four icons (v1.65)

**Renamed.** `app_name` is `PeerZap`, which drives the launcher label and the title, and
the in-app headings were updated to match. The tagline is the sheet's: *Direct. Fast.
Effortless.*

**The logo.** The four app icons on the supplied sheet were cropped with Pillow - boxes
(108,85,378,355), (468,85,738,355), (808,85,1075,355) and (1168,85,1438,355) - and resized
with LANCZOS into all five mipmap densities. 192px copies also ship to
`assets/www/ic_*.png` so the picker can show them.

**Four switchable icons.** A manifest icon is fixed, so runtime switching needs
`activity-alias` entries. `MainActivity` no longer carries the LAUNCHER category; four
aliases do, one per style, each with its own `android:icon` and `android:roundIcon`:

    .IconLight (enabled)   .IconLightIos   .IconDark   .IconDarkIos

`QsBridge.setIcon(which)` enables the chosen alias and disables the other three with
`setComponentEnabledSetting(..., DONT_KILL_APP)`, and stores the choice so it survives a
restart. Settings gained an **App icon** card with a four-up picker showing each icon.
Android may take a moment to refresh the home screen after a switch.

**The package is unchanged** - `com.quickshare` - so this is an in-place update rather than
a new app. Renaming the package would mean a fresh install and a new signing identity;
happy to do it if wanted.

## 62. The package rename (v1.66)

v1.65 renamed only what the user sees. This renames the identity:

- `src/com/quickshare/` -> `src/com/peerzap/`, with `package` and `import` lines rewritten
  in all 27 Java files;
- the manifest's `package="com.peerzap"`;
- the FileProvider authority, `com.peerzap.files`, and the `AUTHORITY` constant that
  `ReceivedFileProvider` exposes;
- the package name baked into `assets/www/app.js` (the About row's default).

Confirmed in the built APK:

    package: name='com.peerzap'
    application-label:'PeerZap'
    com/peerzap in classes.dex: 104        com/quickshare: 0
    activity-alias entries: 4

**What this means for installing.** `com.peerzap` is a *different application* to Android.
It will sit alongside the old `com.quickshare` build rather than replacing it, so the
previous version has to be uninstalled - and because the app's settings and history live in
its private storage, they do not carry across. The four launcher icons, the Credits tab and
everything else are unchanged.

## 63. The default icon (v1.67)

The **Light** style - which the manifest enables by default, and which the picker shows
first - is now the standalone PeerZap icon: mark plus wordmark on the blue rounded square.
It was cropped from the supplied 1254x1254 image by detecting the non-white content box
(86, 86, 1166, 1166) and rendered into all five mipmap densities again. The picker's 192px
preview and the Credits card's logo come from the same crop, so the app shows one mark
everywhere.

The other three styles are untouched. One note for the record: `logo.jpg` is a JPEG and
therefore has no alpha channel, so the crop is flattened onto white before saving - writing
RGBA to JPEG fails, which briefly left a zero-byte file until it was corrected.

## 64. The name, everywhere (v1.68)

The panel header still read "Quick Share" after the v1.65 rebrand, because the header is
drawn from `TITLES.home` in app.js - not from the manifest label, which is why the app
drawer said PeerZap while the panel said Quick Share. The notification title had the same
problem, from `svc_title` in strings.xml.

Both now read PeerZap, and so does every other string that names *this* app. Three things
were deliberately left as "Quick Share":

  * a peer discovered as a real Google Quick Share device - it is a Quick Share device, and
    mislabelling it would be worse than the inconsistency;
  * the "Quick Share compatibility" setting, which is named after what it talks to;
  * `window.QuickShare`, the JS-to-native bridge symbol. This is an interface name, not a
    display string; renaming it breaks every call from the web layer into Java.

The save folder moved to `Downloads/PeerZap`, including the two hardcoded paths in QsBridge
that resolve a received file from history - those would otherwise have silently failed to
find anything received after the change.

## 65. The credits page, and a logo that was load-bearing (v1.69)

The developer card's avatar pointed at `logo.jpg` - the same file as the app's own logo. That
worked until the app logo was replaced, at which point the developer's picture changed too,
because there was only ever one file. The avatar now reads `dev.jpg`, a separate copy of the
original Gitly picture, so the two are independent.

The blue dots were a padding bug. `.creditslist li::before` draws the dot absolutely at
`left:2px`, but each `<li>` carried the inline style `padding:7px 0`, and an inline style beats
a class - so the text started at x=0 and the dot landed on top of it. The fix is on the items
themselves: `padding:7px 0 7px 18px` on all five, with the dot moved to `top:17px` so it sits
against the first line rather than above it.

The update repo moved to `BonkerUnkilBonki/PeerZap`.

## 66. Back, and the page that owned it (v1.70)

Two bugs on the Credits page.

**The dead end.** `viewSettings()` accumulates its markup in a string `h`, beginning with the
Settings/Credits segmented control. It then read `if (STAB === 'credits') return viewCredits();`
- a bare `return`, not `return h + viewCredits()`. So on the Credits tab the function returned
only the credits body and silently dropped the segmented control it had just built. The tab
had no control to leave it, which is exactly what it looked like. The fix is to append rather
than replace.

**The back gesture.** MainActivity had no `onBackPressed` override at all, so a back press went
to the default handler and finished the activity - the app closed from any screen. Back now
belongs to the page:

    MainActivity.onBackPressed()
        -> web.evaluateJavascript("window.qsBack && window.qsBack()")
             -> close an open sheet
             -> or leave the Credits tab for Settings
             -> or walk a deeper route back to home
             -> or QuickShare.exitApp()   (native: finish the activity)

The page owns the decision because it is the only layer that knows what is on top. The activity
never finishes itself, so there is one path to exiting rather than two that can disagree.

Worth recording for anyone extending this: the override only fires because the manifest does
**not** set `android:enableOnBackInvokedCallback`. Opting into predictive back would move the
gesture to `OnBackInvokedCallback` and silently stop calling `onBackPressed`, which would
reintroduce this exact bug.

## 67. A setter that fired on every write (v1.71)

**Symptom.** Auto-accept would not stay off. Turning it off, then changing the accent, the
theme, the glow, or the icon would switch it back on.

**Cause.** Two layers, one wrong assumption.

`applyTheme()` in app.js pushes the theme prefs to native, and among them it sends
`QS.setSetting('qsCompat', ...)` - on *every* call, not only when compatibility changes. That
is harmless for a plain setter.

But the `qsCompat` setter in QsBridge was not plain. It carried the compatibility/auto-accept
coupling:

    case "qsCompat":
        st.setQsCompat("true".equals(value));
        if (st.qsCompat()) st.setAutoAccept(true);   // <-- ran on every write

So each unrelated settings change re-asserted `qsCompat=true`, and each of those re-assertions
switched auto-accept on again. The user's choice was being overwritten by a side effect of a
different setting.

**Fix.** The coupling now fires on the transition, not on the value:

    boolean wasCompat = st.qsCompat();
    boolean nowCompat = "true".equals(value);
    st.setQsCompat(nowCompat);
    if (nowCompat && !wasCompat) st.setAutoAccept(true);

Turning compatibility on still turns auto-accept on, once, as part of that switch - the intended
behaviour. Writing the same value again is now a no-op, so auto-accept stays wherever the user
put it.

The general lesson, worth keeping: a setter that carries a side effect has to know whether the
value actually changed. Idempotent writes are the norm in this codebase - applyTheme() re-sends
the whole settings block on every UI change - so any setter that acts on its value rather than
on the change will fire far more often than intended.

## 68. Still unverified

A **completed file transfer** with a stock Quick Share device has not been observed.
The handshake is confirmed working on hardware; the payload layer is proven by the
loopback integration test but not yet by a real sender. Sending *to* a stock peer is
not implemented - only receiving. Discovery in both directions is now built
(`QsBrowser` browses `_FC9F5ED42C8A._tcp.`), which is why this app can now list real
Quick Share devices rather than only being visible to them.
