package com.letseries.letmelook.common;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * W2/W3 transport: adaptive gzip framing plus message multiplexing.
 *
 * <p>Payloads larger than {@link #COMPRESSION_THRESHOLD} are gzip-compressed
 * before sending. The receiver auto-detects compression via the gzip magic
 * header ({@code 0x1f 0x8b}), so W1 (plain JSON) payloads keep working.
 *
 * <p>{@link #peekKind(byte[])} reads only the top-level {@code kind} field of an
 * already-unwrapped payload, which is how the client routes a shared-channel
 * payload to either {@link TimelineChunk} or {@link LookupStatus} without
 * parsing (or twice-decompressing) the whole message.
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
        return GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    public static <T> T decode(byte[] b, Class<T> type) {
        return GSON.fromJson(new String(b, StandardCharsets.UTF_8), type);
    }

    /** Encode, gzipping when the JSON exceeds {@link #COMPRESSION_THRESHOLD}. */
    public static byte[] encodeAdaptive(Object o) throws IOException {
        byte[] raw = encode(o);
        if (raw.length >= COMPRESSION_THRESHOLD) {
            return compress(raw);
        }
        return raw;
    }

    /**
     * Return the plain JSON bytes of a payload, gunzipping when the gzip magic
     * is present. Callers that must both route and decode a payload should use
     * this once instead of {@link #decodeAdaptive(byte[], Class)} so a large
     * page is only inflated a single time.
     */
    public static byte[] unwrap(byte[] payload) throws IOException {
        if (isCompressed(payload)) {
            return decompress(payload);
        }
        return payload;
    }

    /** Decode, transparently gunzipping when the gzip magic is present. */
    public static <T> T decodeAdaptive(byte[] b, Class<T> type) throws IOException {
        return decode(unwrap(b), type);
    }

    public static boolean isCompressed(byte[] b) {
        return b != null && b.length >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }

    /**
     * Read the top-level {@code kind} string from an <b>already unwrapped</b>
     * (plain JSON) payload. Parsing stops at that field, so routing a 256 KiB
     * page costs a fraction of a full decode.
     *
     * @return the kind, or {@code null} when absent/unparseable (a W1/W2
     *         timeline page, which callers must treat as {@code timeline}).
     */
    public static String peekKind(byte[] plainJson) {
        if (plainJson == null || plainJson.length == 0) {
            return null;
        }
        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new ByteArrayInputStream(plainJson), StandardCharsets.UTF_8))) {
            reader.setLenient(true);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                return null;
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("kind".equals(name)) {
                    if (reader.peek() == JsonToken.STRING) {
                        return reader.nextString();
                    }
                    return null;
                }
                reader.skipValue();
            }
        } catch (JsonParseException | IllegalStateException | IOException e) {
            return null;
        }
        return null;
    }

    /** Convenience for the server/tests: {@link #peekKind(byte[])} after {@link #unwrap(byte[])}. */
    public static String peekKindAdaptive(byte[] payload) throws IOException {
        return peekKind(unwrap(payload));
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
