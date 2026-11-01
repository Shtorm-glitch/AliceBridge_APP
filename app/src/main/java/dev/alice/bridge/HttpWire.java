package dev.alice.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** HTTP/1.1 framing, used after terminating TLS. Bodies are streamed unchanged. */
public final class HttpWire {
    public record Head(String first, LinkedHashMap<String, List<String>> fields) {
        public String get(String key) { return String.join(", ", fields.getOrDefault(key.toLowerCase(Locale.ROOT), List.of())); }
        public void set(String key, String value) { fields.put(key.toLowerCase(Locale.ROOT), new ArrayList<>(List.of(value))); }
        public void remove(String key) { fields.remove(key.toLowerCase(Locale.ROOT)); }
        public void write(OutputStream out) throws IOException {
            out.write((first + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            for (Map.Entry<String,List<String>> entry : fields.entrySet())
                for (String value : entry.getValue()) out.write((entry.getKey() + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(new byte[]{13,10}); out.flush();
        }
    }
    public static String line(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() <= limit) {
            int b = in.read(); if (b == -1) { if (out.size() == 0) return null; throw new EOFException("HTTP line truncated"); }
            if (b == 10) {
                byte[] value = out.toByteArray();
                if (value.length == 0 || value[value.length - 1] != 13) throw new IOException("HTTP needs CRLF");
                return new String(value, 0, value.length - 1, StandardCharsets.ISO_8859_1);
            }
            out.write(b);
        }
        throw new IOException("HTTP line too long");
    }
    public static Head head(InputStream in) throws IOException {
        String first = line(in, 8192); if (first == null) throw new EOFException();
        LinkedHashMap<String,List<String>> fields = new LinkedHashMap<>(); int size = first.length();
        for (int n = 0; n < 200; n++) {
            String line = line(in, 8192); if (line == null) throw new EOFException();
            size += line.length(); if (size > 65536) throw new IOException("HTTP headers too large");
            if (line.isEmpty()) {
                if (fields.containsKey("transfer-encoding") && fields.containsKey("content-length"))
                    throw new IOException("Ambiguous HTTP framing");
                return new Head(first, fields);
            }
            int colon = line.indexOf(':'); if (colon < 1 || line.charAt(0) == ' ') throw new IOException("Invalid HTTP header");
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            if (!key.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) throw new IOException("Invalid HTTP header name");
            if (fields.containsKey(key)) {
                if (key.equals("content-length") || key.equals("transfer-encoding") || key.equals("host")) throw new IOException("Duplicate framing/Host header");
            }
            fields.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        throw new IOException("Too many HTTP headers");
    }
    public static void body(InputStream in, OutputStream out, Head head, boolean untilEof) throws IOException {
        body(in, out, head, untilEof, null);
    }
    public static void body(InputStream in, OutputStream out, Head head, boolean untilEof, OutputStream preview) throws IOException {
        String encoding = head.get("transfer-encoding");
        if (!encoding.isEmpty()) {
            if (!encoding.equalsIgnoreCase("chunked")) throw new IOException("Unsupported transfer encoding");
            while (true) {
                String line = line(in, 1024); if (line == null) throw new EOFException();
                long n;
                try { n = Long.parseLong(line.split(";", 2)[0].trim(), 16); } catch (NumberFormatException e) { throw new IOException("Bad HTTP chunk", e); }
                if (n < 0) throw new IOException("Bad HTTP chunk length");
                out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                if (n == 0) {
                    int total = 0;
                    for (;;) {
                        String trailer = line(in, 8192); if (trailer == null) throw new EOFException(); total += trailer.length();
                        if (total > 65536) throw new IOException("HTTP trailers too large");
                        out.write((trailer + "\r\n").getBytes(StandardCharsets.ISO_8859_1)); if (trailer.isEmpty()) break;
                    }
                    break;
                }
                fixed(in, out, n, preview);
                if (!"".equals(line(in, 2))) throw new IOException("Missing chunk terminator");
                out.write(new byte[]{13,10}); out.flush();
            }
        } else if (!head.get("content-length").isEmpty()) {
            long n; try { n = Long.parseLong(head.get("content-length")); } catch (NumberFormatException e) { throw new IOException("Bad Content-Length", e); }
            if (n < 0) throw new IOException("Negative Content-Length"); fixed(in, out, n, preview);
        } else if (untilEof) {
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) != -1;) { out.write(b, 0, n); if (preview != null) preview.write(b, 0, n); }
        }
        out.flush();
    }
    private static void fixed(InputStream in, OutputStream out, long size, OutputStream preview) throws IOException {
        byte[] b = new byte[16384];
        while (size > 0) { int n = in.read(b, 0, (int) Math.min(size, b.length)); if (n == -1) throw new EOFException(); out.write(b, 0, n); if (preview != null) preview.write(b, 0, n); size -= n; }
    }
}
