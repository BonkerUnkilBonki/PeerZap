package com.peerzap.qs;

/**
 * Builders and parsers for the Quick Share wire frames.
 *
 * Two nested layers:
 *  - the transport layer: OfflineFrame { version, v1: V1Frame }
 *    V1Frame types: CONNECTION_REQUEST=1, CONNECTION_RESPONSE=2, PAYLOAD_TRANSFER=3,
 *    KEEP_ALIVE=5, DISCONNECTION=6, PAIRED_KEY_ENCRYPTION=7, ...
 *  - the sharing layer, carried inside BYTES payloads:
 *    Frame { version, v1: V1Frame } with INTRODUCTION=1, RESPONSE=2,
 *    PAIRED_KEY_ENCRYPTION=3, PAIRED_KEY_RESULT=4
 */
public final class QsFrames {

    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    /** OsInfo.OsType.ANDROID */
    public static final int OS_TYPE_ANDROID = 1;
    /** The keep-alive timeout we advertise, matching stock Nearby Connections. */
    public static final int KEEP_ALIVE_TIMEOUT_MILLIS = 600000;
    /** How often we must actually send a keep-alive frame. */
    public static final int KEEP_ALIVE_INTERVAL_MILLIS = 10000;

    // transport V1Frame types
    public static final int T_CONNECTION_REQUEST = 1;
    public static final int T_CONNECTION_RESPONSE = 2;
    public static final int T_PAYLOAD_TRANSFER = 3;
    public static final int T_BANDWIDTH_UPGRADE = 4;
    public static final int T_DISCONNECTION = 6;
    public static final int T_PAIRED_KEY_ENCRYPTION = 7;

    // sharing V1Frame types
    public static final int S_INTRODUCTION = 1;
    public static final int S_RESPONSE = 2;
    public static final int S_PAIRED_KEY_ENCRYPTION = 3;
    public static final int S_PAIRED_KEY_RESULT = 4;

    // payload packet types
    public static final int PACKET_DATA = 1;
    public static final int PACKET_CONTROL = 2;
    public static final int PACKET_ACK = 3;

    // payload types
    public static final int PAYLOAD_BYTES = 1;
    public static final int PAYLOAD_FILE = 2;
    public static final int FLAG_LAST_CHUNK = 1;

    // ------------------------------------------------------------ transport

    /** OfflineFrame { version = 1, v1 = <v1Payload> } */
    public static byte[] offlineFrame(int v1Type, byte[] v1SubMessage) {
        int field = v1FieldFor(v1Type);
        byte[] v1 = new Proto.Writer().int32Always(1, v1Type).message(field, v1SubMessage).toByteArray();
        return new Proto.Writer().int32Always(1, 1).message(2, v1).toByteArray();
    }

    private static int v1FieldFor(int type) {
        switch (type) {
            case T_CONNECTION_REQUEST: return 2;
            case T_CONNECTION_RESPONSE: return 3;
            case T_PAYLOAD_TRANSFER: return 4;
            case T_BANDWIDTH_UPGRADE: return 5;
            case T_BANDWIDTH_UPGRADE_RETRY: return 13;
            case 5: return 6;                    // keep alive
            case T_DISCONNECTION: return 7;
            case T_PAIRED_KEY_ENCRYPTION: return 8;
            default: return 2;
        }
    }

    /**
     * A payload transfer frame:
     *   PayloadTransferFrame { packet_type = DATA(1), payload_header = 2, payload_chunk = 3 }
     * wrapped in an OfflineFrame of type PAYLOAD_TRANSFER.
     */
    public static byte[] payloadFrame(long id, int payloadType, long totalSize,
                                      String fileName, long offset, int flags, byte[] body) {
        Proto.Writer header = new Proto.Writer()
                .int32(1, id)
                .int32Always(2, payloadType)
                .int32(3, totalSize);
        if (fileName != null) header.string(5, fileName);
        Proto.Writer chunk = new Proto.Writer()
                .int32Always(1, flags)
                .int32(2, offset)
                .bytes(3, body);
        byte[] ptf = new Proto.Writer()
                .int32Always(1, PACKET_DATA)
                .message(2, header.toByteArray())
                .message(3, chunk.toByteArray())
                .toByteArray();
        return offlineFrame(T_PAYLOAD_TRANSFER, ptf);
    }

    /** A parsed payload transfer frame. */
    public static final class Payload {
        public long id, totalSize, offset;
        public int type, flags, packetType;
        public String fileName;
        public byte[] body;
        public boolean isLast() { return (flags & FLAG_LAST_CHUNK) != 0; }
    }

    /** Reads a payload transfer out of an OfflineFrame; null if it is not one. */
    public static Payload parsePayload(byte[] offlineFrame) {
        byte[] v1 = subMessage(offlineFrame, 2);
        if (v1 == null) return null;
        long type = varintField(v1, 1, -1);
        if (type != T_PAYLOAD_TRANSFER) return null;
        byte[] ptf = subMessage(v1, 4);
        if (ptf == null) return null;
        Payload p = new Payload();
        p.packetType = (int) varintField(ptf, 1, 0);
        byte[] hdr = subMessage(ptf, 2);
        byte[] chk = subMessage(ptf, 3);
        if (hdr != null) {
            p.id = varintField(hdr, 1, 0);
            p.type = (int) varintField(hdr, 2, 0);
            p.totalSize = varintField(hdr, 3, 0);
            p.fileName = stringField(hdr, 5);
        }
        if (chk != null) {
            p.flags = (int) varintField(chk, 1, 0);
            p.offset = varintField(chk, 2, 0);
            p.body = subMessage(chk, 3);
        }
        return p;
    }

    /**
     * The transport connection response, in the exact six-field shape stock
     * Google Nearby Connections emits. Samsung One UI validates every field
     * unconditionally, whichever role we are playing:
     *   1. status = 0                      (legacy int field)
     *   2. response = ACCEPT / REJECT      (modern enum)
     *   3. os_info.type = ANDROID          (LINUX makes Samsung FIN silently)
     *   4. multiplex_socket_bitmask = 0    (absent => One UI 8.0.5 FINs ~104ms later)
     *   5. safe_to_disconnect_version = 1  (absent => One UI 7+ drops us before consent)
     *   6. keep_alive_timeout_millis = 600000
     */
    public static byte[] connectionResponse(int response) {
        byte[] osInfo = new Proto.Writer().int32Always(1, OS_TYPE_ANDROID).toByteArray();
        byte[] body = new Proto.Writer()
                .int32Always(1, 0)                          // status = STATUS_OK
                .int32Always(3, response)                   // response
                .message(4, osInfo)                         // os_info
                .int32Always(5, 0)                          // multiplex_socket_bitmask
                .int32Always(7, 1)                          // safe_to_disconnect_version
                .int32Always(9, KEEP_ALIVE_TIMEOUT_MILLIS)  // keep_alive_timeout_millis
                .toByteArray();
        return offlineFrame(T_CONNECTION_RESPONSE, body);
    }

    // BandwidthUpgradeNegotiationFrame.EventType
    public static final int UPGRADE_PATH_REQUEST = 7;
    public static final int UPGRADE_FAILURE = 5;

    public static final int T_BANDWIDTH_UPGRADE_RETRY = 12;

    /**
     * The event type of a bandwidth-upgrade frame, or -1.
     *
     * The body sits at a different V1Frame field depending on the frame type:
     * BANDWIDTH_UPGRADE_NEGOTIATION (4) uses field 5, BANDWIDTH_UPGRADE_RETRY (12)
     * uses field 13. Reading only field 5 is why a real sender's retry was logged
     * as "event=-1" and never answered.
     */
    public static int upgradeEvent(byte[] offlineFrame) {
        byte[] v1 = subMessage(offlineFrame, 2);
        if (v1 == null) return -1;
        long type = varintField(v1, 1, -1);
        int field;
        if (type == T_BANDWIDTH_UPGRADE) field = 5;
        else if (type == T_BANDWIDTH_UPGRADE_RETRY) field = 13;
        else return -1;
        byte[] neg = subMessage(v1, field);
        if (neg == null) return -1;
        return (int) varintField(neg, 1, -1);      // event_type = 1
    }

    /** True if this is any kind of bandwidth-upgrade frame. */
    public static boolean isBandwidthUpgrade(byte[] offlineFrame) {
        byte[] v1 = subMessage(offlineFrame, 2);
        if (v1 == null) return false;
        long type = varintField(v1, 1, -1);
        return type == T_BANDWIDTH_UPGRADE || type == T_BANDWIDTH_UPGRADE_RETRY;
    }

    /**
     * A bandwidth-upgrade failure reply. Stock senders solicit an upgrade as soon
     * as the encrypted channel is up; if we cannot stand up Wi-Fi Direct we must
     * say so, otherwise the sender stalls waiting for an answer.
     */
    public static byte[] upgradeFailure(int medium) {
        byte[] info = new Proto.Writer().int32Always(1, medium).toByteArray();   // UpgradePathInfo.medium
        byte[] neg = new Proto.Writer()
                .int32Always(1, UPGRADE_FAILURE)
                .message(2, info)                  // upgrade_path_info = 2
                .toByteArray();
        return offlineFrame(T_BANDWIDTH_UPGRADE, neg);
    }

    /** A keep-alive frame; Android expects one every 10 seconds. */
    public static byte[] keepAlive(boolean ack, long seq) {
        Proto.Writer w = new Proto.Writer();
        if (ack) w.bool(1, true);
        w.int32Always(2, (int) seq);
        return offlineFrame(5, w.toByteArray());
    }

    /** True if this offline frame is a keep-alive. */
    public static boolean isKeepAlive(byte[] offlineFrame) {
        byte[] v1 = subMessage(offlineFrame, 2);
        return v1 != null && varintField(v1, 1, -1) == 5;
    }

    /**
     * A paired-key-encryption frame. It is meaningless without Google's servers,
     * so the fields are random filler, exactly as the reference implementations do.
     */
    public static byte[] pairedKeyEncryption() {
        byte[] signedData = new byte[72];
        byte[] secretIdHash = new byte[6];
        RNG.nextBytes(signedData);
        RNG.nextBytes(secretIdHash);
        byte[] msg = new Proto.Writer().bytes(1, signedData).bytes(2, secretIdHash).toByteArray();
        return sharingFrame(S_PAIRED_KEY_ENCRYPTION, msg);
    }

    /**
     * A disconnection frame. Because we advertise safe_to_disconnect_version = 1,
     * One UI 7+ enforces the safe-disconnect handshake: an abrupt FIN mid-payload
     * makes the peer flag every in-flight payload as failed even when the bytes
     * already arrived. So we ask the peer to drain, and we acknowledge when asked.
     */
    /** ConnectionRequestFrame.Medium.WIFI_LAN */
    public static final int MEDIUM_WIFI_LAN = 5;

    /**
     * A connection request, as a stock Android client sends it. Bada's notes:
     * "Stock Quick Share closes the socket immediately if these fields are absent -
     * only endpoint_id and endpoint_info is not enough on Android 14+".
     */
    public static byte[] connectionRequest(String endpointId, String name, byte[] endpointInfo) {
        byte[] mediumMetadata = new Proto.Writer()
                .bool(1, true)                     // supports_5_ghz
                .bool(5, false)                    // mobile_radio
                .toByteArray();
        Proto.Writer cr = new Proto.Writer()
                .string(1, endpointId)
                .string(2, name)
                .int32Always(4, 0)                 // nonce
                .int32Always(5, MEDIUM_WIFI_LAN)   // mediums
                .bytes(6, endpointInfo)
                .message(7, mediumMetadata)
                .int32Always(8, KEEP_ALIVE_INTERVAL_MILLIS)
                .int32Always(9, KEEP_ALIVE_TIMEOUT_MILLIS);
        return offlineFrame(T_CONNECTION_REQUEST, cr.toByteArray());
    }

    // IntroductionFrame.SharingUseCase
    public static final int USE_CASE_NEARBY_SHARE = 1;

    /**
     * An IntroductionFrame offering files.
     *
     * Two details matter to Samsung receivers (from Bada's on-device notes):
     * `use_case` must be NEARBY_SHARE, and every FileMetadata needs a non-zero
     * `id` matching its `payload_id` - One UI keys its receive-side bookkeeping on
     * `id` and silently discards attachments left at the proto default of 0.
     */
    public static byte[] introduction(java.util.List<FileMeta> files) {
        Proto.Writer intro = new Proto.Writer();
        for (FileMeta m : files) {
            Proto.Writer fm = new Proto.Writer()
                    .string(1, m.name)
                    .int32Always(2, m.type == 0 ? fileTypeFor(m.mimeType) : m.type)
                    .int32Always(3, m.payloadId)
                    .int32Always(4, m.size)
                    .string(5, m.mimeType == null ? "application/octet-stream" : m.mimeType)
                    .int32Always(6, m.payloadId);                  // id must be non-zero
            intro.message(1, fm.toByteArray());
        }
        intro.int32Always(8, USE_CASE_NEARBY_SHARE);
        return sharingFrame(S_INTRODUCTION, intro.toByteArray());
    }

    /** The status of a sharing-layer RESPONSE frame, or -1. */
    public static int sharingResponseStatus(byte[] sharingFrame) {
        byte[] v1 = subMessage(sharingFrame, 2);
        if (v1 == null || varintField(v1, 1, -1) != S_RESPONSE) return -1;
        byte[] body = subMessage(v1, 3);            // connection_response = 3
        if (body == null) return -1;
        return (int) varintField(body, 1, -1);      // status = 1
    }

    public static byte[] disconnection(boolean requestSafe, boolean ackSafe) {
        Proto.Writer w = new Proto.Writer();
        if (requestSafe) w.bool(1, true);
        if (ackSafe) w.bool(2, true);
        return offlineFrame(T_DISCONNECTION, w.toByteArray());
    }

    /** The disconnection frame the peer sent us, or null. */
    public static Disconnection parseDisconnection(byte[] offlineFrame) {
        byte[] v1 = subMessage(offlineFrame, 2);
        if (v1 == null || varintField(v1, 1, -1) != T_DISCONNECTION) return null;
        byte[] body = subMessage(v1, 7);
        Disconnection d = new Disconnection();
        if (body != null) {
            d.requestSafe = varintField(body, 1, 0) != 0;
            d.ackSafe = varintField(body, 2, 0) != 0;
        }
        return d;
    }

    public static final class Disconnection {
        public boolean requestSafe, ackSafe;
    }

    // -------------------------------------------------------------- sharing

    /** A sharing Frame wrapped for the payload layer. */
    public static byte[] sharingFrame(int sharingType, byte[] subMessage) {
        int field;
        switch (sharingType) {
            case S_INTRODUCTION: field = 2; break;
            case S_RESPONSE: field = 3; break;
            case S_PAIRED_KEY_ENCRYPTION: field = 4; break;
            case S_PAIRED_KEY_RESULT: field = 5; break;
            default: field = 2;
        }
        byte[] v1 = new Proto.Writer().int32Always(1, sharingType).message(field, subMessage).toByteArray();
        return new Proto.Writer().int32Always(1, 1).message(2, v1).toByteArray();
    }

    /** The sharing layer's reply to an introduction: ACCEPT = 1, REJECT = 2. */
    public static byte[] sharingResponse(int status) { return sharingResponse(status, null); }

    /**
     * The sharing-layer response. On ACCEPT it echoes each offered file's
     * attachment hash back as an attachment detail, which is what stock
     * Nearby Connections does and what the sender expects to see.
     */
    public static byte[] sharingResponse(int status, java.util.List<FileMeta> files) {
        Proto.Writer body = new Proto.Writer().int32Always(1, status);
        if (status == 1 && files != null) {
            for (FileMeta m : files) {
                if (m.attachmentHash == 0) continue;   // only files the sender hashed
                byte[] entry = new Proto.Writer()
                        .int32(1, m.attachmentHash)
                        .message(2, attachmentDetails(m))
                        .toByteArray();
                body.message(2, entry);               // map<int64, AttachmentDetails>
            }
        }
        return sharingFrame(S_RESPONSE, body.toByteArray());
    }

    /**
     * AttachmentDetails { type = FILE, file_attachment_details = {
     *   receiver_existing_file_size = 0, attachment_hash_payloads = { hash -> payloads } } }
     */
    private static byte[] attachmentDetails(FileMeta m) {
        byte[] payloadDetails = new Proto.Writer().int32(1, m.payloadId).int32(3, m.size).toByteArray();
        byte[] payloadsDetails = new Proto.Writer().message(1, payloadDetails).toByteArray();
        byte[] mapEntry = new Proto.Writer().int32(1, m.attachmentHash)
                .message(2, payloadsDetails).toByteArray();
        byte[] fileDetails = new Proto.Writer().int32(1, 0).message(2, mapEntry).toByteArray();
        return new Proto.Writer().int32Always(1, 1).message(2, fileDetails).toByteArray();
    }

    /** The sharing layer's paired-key result: SUCCESS=1, FAIL=2, UNABLE=3. */
    public static byte[] pairedKeyResult(int status) {
        return sharingFrame(S_PAIRED_KEY_RESULT, new Proto.Writer().int32Always(1, status).toByteArray());
    }

    /** One file the sender is offering. */
    public static final class FileMeta {
        public String name;
        public long payloadId, size, attachmentHash;
        public String mimeType;
        public int type;      // FileMetadata.Type; 0 means "choose from the mime type"
    }

    /** FileMetadata.Type from a mime type. */
    public static int fileTypeFor(String mime) {
        if (mime == null) return 5;
        if (mime.startsWith("image/")) return 1;
        if (mime.startsWith("video/")) return 2;
        if (mime.startsWith("audio/")) return 4;
        if (mime.contains("vnd.android.package-archive")) return 3;
        if (mime.startsWith("text/vcard")) return 6;
        return 5;
    }

    /** Reads an IntroductionFrame out of a sharing Frame; null if it is not one. */
    public static java.util.List<FileMeta> parseIntroduction(byte[] sharingFrame) {
        byte[] v1 = subMessage(sharingFrame, 2);
        if (v1 == null) return null;
        long type = varintField(v1, 1, -1);
        if (type != S_INTRODUCTION) return null;
        byte[] intro = subMessage(v1, 2);
        if (intro == null) return new java.util.ArrayList<>();
        java.util.List<FileMeta> out = new java.util.ArrayList<>();
        Proto.Reader r = new Proto.Reader(intro);
        Proto.Reader.Field f;
        while ((f = r.next()) != null) {
            if (f.number == 1 && f.bytes != null) {
                FileMeta m = new FileMeta();
                m.name = stringField(f.bytes, 1);
                m.payloadId = varintField(f.bytes, 3, 0);
                m.size = varintField(f.bytes, 4, 0);
                m.mimeType = stringField(f.bytes, 5);
                m.attachmentHash = varintField(f.bytes, 8, 0);
                out.add(m);
            }
        }
        return out;
    }

    /** The sharing frame type, or -1. */
    public static int sharingType(byte[] sharingFrame) {
        byte[] v1 = subMessage(sharingFrame, 2);
        if (v1 == null) return -1;
        return (int) varintField(v1, 1, -1);
    }

    // ---------------------------------------------------------------- utils

    /** Reads a length-delimited field out of an encoded message. */
    public static byte[] subMessage(byte[] encoded, int field) {
        if (encoded == null) return null;
        Proto.Reader r = new Proto.Reader(encoded);
        Proto.Reader.Field f;
        while ((f = r.next()) != null) if (f.number == field) return f.bytes;
        return null;
    }

    public static long varintField(byte[] encoded, int field, long def) {
        if (encoded == null) return def;
        Proto.Reader r = new Proto.Reader(encoded);
        Proto.Reader.Field f;
        while ((f = r.next()) != null) if (f.number == field) return f.value;
        return def;
    }

    public static String stringField(byte[] encoded, int field) {
        byte[] b = subMessage(encoded, field);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private QsFrames() {}
}
