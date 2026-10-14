package com.peerzap.qs;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny protobuf wire-format codec.
 *
 * Quick Share is protobuf all the way down, and the app ships without any
 * protobuf runtime, so this implements the small subset the protocol needs:
 * varints, length-delimited fields, fixed32 and the repeated/message helpers.
 * It is plain Java with no Android dependency, which is what lets the protocol
 * be unit-tested on the JVM.
 */
public final class Proto {

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer varint(long v) {
            while (true) {
                if ((v & ~0x7FL) == 0) { out.write((int) v); return this; }
                out.write(((int) (v & 0x7F)) | 0x80);
                v >>>= 7;
            }
        }

        private Writer key(int field, int wire) { return varint(((long) field << 3) | wire); }

        /** int32 / int64 / enum */
        public Writer int32(int field, long value) {
            if (value == 0) return this;
            key(field, 0); varint(value); return this;
        }
        /** bool / enum, always written (callers pass required fields) */
        public Writer int32Always(int field, long value) { key(field, 0); varint(value); return this; }
        public Writer bool(int field, boolean v) { return v ? int32Always(field, 1) : this; }
        public Writer bytes(int field, byte[] value) {
            if (value == null) return this;
            key(field, 2); varint(value.length); out.write(value, 0, value.length); return this;
        }
        public Writer string(int field, String s) {
            if (s == null) return this;
            return bytes(field, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        /** a nested message, serialized first */
        public Writer message(int field, byte[] encoded) { return bytes(field, encoded); }
        public Writer fixed32(int field, int v) {
            key(field, 5);
            out.write(v & 0xFF); out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF); out.write((v >>> 24) & 0xFF);
            return this;
        }
        public byte[] toByteArray() { return out.toByteArray(); }
    }

    public static final class Reader {
        private final byte[] buf;
        private int pos;
        public Reader(byte[] b) { buf = b; pos = 0; }
        public Reader(byte[] b, int off, int len) { buf = b; pos = off; }

        public boolean hasMore() { return pos < buf.length; }
        public int position() { return pos; }

        public long varint() {
            long result = 0; int shift = 0;
            while (true) {
                int b = buf[pos++] & 0xFF;
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift > 63) throw new IllegalStateException("varint too long");
            }
        }
        private int readTag() { return (int) varint(); }
        private static int fieldOf(int tag) { return tag >>> 3; }
        private static int wireOf(int tag) { return tag & 7; }

        public byte[] readBytes() {
            int len = (int) varint();
            byte[] b = new byte[len];
            System.arraycopy(buf, pos, b, 0, len);
            pos += len;
            return b;
        }
        private void skip(int wire) {
            switch (wire) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: pos += (int) varint(); break;
                case 5: pos += 4; break;
                default: throw new IllegalStateException("bad wire type " + wire);
            }
        }

        /** One decoded field. */
        public static final class Field {
            public final int number;
            public final int wire;
            public final long value;      // varint / fixed32
            public final byte[] bytes;    // length-delimited
            Field(int n, int w, long v, byte[] b) { number = n; wire = w; value = v; bytes = b; }
            public String asString() {
                return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        public Field next() {
            if (!hasMore()) return null;
            int tag = readTag();
            int n = fieldOf(tag), w = wireOf(tag);
            switch (w) {
                case 0: return new Field(n, w, varint(), null);
                case 1: { long v = 0; for (int i = 0; i < 8; i++) v |= ((long) (buf[pos++] & 0xFF)) << (8 * i); return new Field(n, w, v, null); }
                case 2: return new Field(n, w, 0, readBytes());
                case 5: { long v = 0; for (int i = 0; i < 4; i++) v |= ((long) (buf[pos++] & 0xFF)) << (8 * i); return new Field(n, w, v, null); }
                default: throw new IllegalStateException("bad wire type " + w);
            }
        }

        public List<Field> all() {
            List<Field> out = new ArrayList<>();
            Field f;
            while ((f = next()) != null) out.add(f);
            return out;
        }
    }

    public static byte[] varintBytes(long v) { return new Writer().varint(v).toByteArray(); }

    private Proto() {}
}
