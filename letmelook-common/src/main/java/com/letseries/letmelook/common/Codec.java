package com.letseries.letmelook.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Gson + gzip helpers for the plugin-message transport. */
public final class Codec {
    private static final Gson GSON = new GsonBuilder().create();

    private Codec() {}

    public static Gson gson() {
        return GSON;
    }

    public static byte[] encode(Object o) {
        return GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    public static <T> T decode(byte[] b, Class<T> type) {
        return GSON.fromJson(new String(b, StandardCharsets.UTF_8), type);
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
