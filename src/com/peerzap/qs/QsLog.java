package com.peerzap.qs;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * A small in-memory log of what the receiver is doing, so a failure can be
 * diagnosed from the app itself rather than from adb. Deliberately free of
 * Android imports so the whole protocol core still runs on a plain JVM.
 */
public final class QsLog {

    /** Lets the Android layer mirror these lines into logcat. */
    public interface Sink { void log(String tag, String message); }

    private static final int MAX = 600;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile Sink sink;

    public static void setSink(Sink s) { sink = s; }

    public static synchronized void add(String tag, String message) {
        String line = FMT.format(new Date()) + "  " + tag + "  " + message;
        LINES.addLast(line);
        while (LINES.size() > MAX) LINES.removeFirst();
        Sink s = sink;
        if (s != null) { try { s.log(tag, line); } catch (Throwable ignored) {} }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() { LINES.clear(); }

    /** A short hex preview, for raw bytes that did not parse. */
    public static String hex(byte[] data, int max) {
        if (data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(data.length, max);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", data[i]));
        if (data.length > max) sb.append("...(").append(data.length).append(" bytes)");
        return sb.toString();
    }

    private QsLog() {}
}
