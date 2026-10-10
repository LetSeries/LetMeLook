package com.letseries.letmelook.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * W2 transport: adaptive gzip framing.
 *
 * <p>Payloads larger than {@link #COMPRESSION_THRESHOLD} are gzip-compressed
 * before sending. The receiver auto-detects compression via the gzip magic
 * header ({@code 0x1f 0x8b}), so W1 (plain JSON) payloads keep working.
 */
public final class Codec {
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().create();

    /** Payloads bigger than this are gzipped (32 KiB). */
    public static final int COMPRESSION_THRESHOLD = 32 * 1024;

    private Codec() {}

    public static com.google.gson.Gson gson() {
        return GSON;
    }

    public static byte[] encode(Object o) {
        return GSON.toJson(o).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static <T> T decode(byte[] b, Class<T> type) {
        return GSON.fromJson(new String(b, java.nio.charset.StandardCharsets.UTF_8), type);
    }

    /** Encode, gzipping when the JSON exceeds {@link #COMPRESSION_THRESHOLD}. */
    public static byte[] encodeAdaptive(Object o) throws IOException {
        byte[] raw = encode(o);
        if (raw.length >= COMPRESSION_THRESHOLD) {
            return compress(raw);
        }
        return raw;
    }

    /** Decode, transparently gunzipping when the gzip magic is present. */
    public static <T> T decodeAdaptive(byte[] b, Class<T> type) throws IOException {
        if (isCompressed(b)) {
            b = decompress(b);
        }
        return decode(b, type);
    }

    public static boolean isCompressed(byte[] b) {
        return b != null && b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }

    public static byte[] compress(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }

    public static byte[] decompress(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
