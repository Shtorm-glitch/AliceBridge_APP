package dev.alicebridge.bridge;

import java.io.*;

public final class Streams {
    public static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        while (out.size() < limit) {
            int n = in.read(b, 0, Math.min(b.length, limit - out.size()));
            if (n == -1) break;
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[16384];
        for (int n; (n = in.read(b)) != -1; ) out.write(b, 0, n);
    }
}
