# Quick Share wire protocol

This is the on-the-wire format two copies of this app use to find each other and
move files over the local network. It is intentionally small and documented.

**Scope:** it is used only between two instances of this app. It is **not**
Google's Quick Share protocol, and the app makes no claim of interoperability
with Google Quick Share or Windows Quick Share.

## Ports

| Port | Protocol | Purpose |
|---|---|---|
| 8890 | UDP | discovery beacons |
| 8891 | TCP | file transfers |

## 1. Discovery (UDP, port 8890)

Each device broadcasts, every ~2.2 seconds, a UTF-8 datagram:

```
QS1 {"id":"a1b2c3d4e5f6","name":"My Pixel","model":"Google Pixel 7","port":8891,"t":1712345678901}
```

- `QS1 ` (four bytes) is the magic prefix; anything without it is ignored.
- `id` is a per-install random id. A device ignores its own beacons.
- `name` / `model` are shown in the device list. `port` is the sender's TCP port.
- On receiving a beacon, a device replies with its own beacon to the sender's
  address (unicast), so both sides learn about each other immediately.

Broadcasts are sent to `255.255.255.255` and to each interface's broadcast
address. A device that has not been heard from for 9 seconds is dropped from the
list.

## 2. Transfer (TCP, port 8891)

The receiver runs a TCP server. The sender connects and speaks a sequence of
newline-terminated JSON lines, interleaved with raw file bytes.

### 2.1 Offer (sender to receiver)

```
{"t":"offer","id":"t1712345678901","name":"My Pixel","model":"Pixel 7","files":[{"name":"holiday.jpg","size":2097152,"mime":"image/jpeg"}],"count":1,"total":2097152}
```

The sender then **waits** and sends no file bytes yet.

### 2.2 Decision (receiver to sender)

Accept:

```
{"t":"accept"}
```

Decline:

```
{"t":"reject"}
```

The receiver waits up to 60 seconds for the user's decision (or accepts at once
when auto-accept is on). On timeout it behaves as a decline.

### 2.3 File bodies (sender to receiver, only after accept)

For each file, a header line:

```
{"t":"file","name":"holiday.jpg","size":2097152,"mime":"image/jpeg"}
```

immediately followed by exactly `size` raw bytes. The receiver writes them
straight to storage as they arrive.

### 2.4 End (sender to receiver)

```
{"t":"done"}
```

The receiver then closes the connection.

## 3. Notes

- Every JSON line ends with `\n`; a `\r` is tolerated. Headers are limited to 4 MB.
- File names may contain any UTF-8 text; the receiver sanitises them (path
  separators are stripped, length is capped) and de-duplicates collisions with
  `name (1)`, `name (2)`, …
- The body is read with a size-limited view of the socket, so a file body can
  never run past its declared size into the next header.
- Files are never buffered whole in memory; both sides stream in 64 KB chunks.
- A file whose size is unknown at pick time is staged to a temporary cache file
  first so an exact size can be sent; the temporary file is deleted afterwards.
