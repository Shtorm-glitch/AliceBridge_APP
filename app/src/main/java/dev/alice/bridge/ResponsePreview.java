package dev.alice.bridge;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded diagnostic copy: never changes bytes forwarded to the speaker. */
public final class ResponsePreview extends OutputStream {
    private static final int LIMIT = 16384;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private long total;
    @Override public void write(int b) { total++; if (bytes.size() < LIMIT) bytes.write(b); }
    @Override public void write(byte[] b, int offset, int length) {
        total += length; int keep = Math.min(length, LIMIT - bytes.size()); if (keep > 0) bytes.write(b, offset, keep);
    }
    public String describe(HttpWire.Head response, boolean bodyAllowed) {
        StringBuilder result = new StringBuilder("Заголовки:\n");
        response.fields().forEach((name, values) -> {
            String value = name.equals("set-cookie") || name.equals("authorization") ? "[скрыто]" : String.join(", ", values);
            if (name.equals("location")) value = value.split("\\?", 2)[0];
            if (result.length() < 4096) result.append(name).append(": ").append(value).append('\n');
        });
        result.append("\nТело ответа: ");
        if (!bodyAllowed) return result.append("отсутствует по протоколу").toString();
        if (total == 0) return result.append("пустое (0 байт)").toString();
        String type = response.get("content-type").toLowerCase(Locale.ROOT);
        if (!(type.isEmpty() || type.startsWith("text/") || type.contains("json") || type.contains("xml")))
            return result.append("бинарные данные · ").append(total).append(" байт · ").append(type).toString();
        byte[] data = bytes.toByteArray();
        try {
            if (response.get("content-encoding").equalsIgnoreCase("gzip")) {
                if (total > LIMIT) return result.append("gzip · ").append(total).append(" байт; превью сжато и усечено").toString();
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) { data = Streams.readBounded(in, LIMIT); }
            } else if (!response.get("content-encoding").isEmpty() && !response.get("content-encoding").equalsIgnoreCase("identity"))
                return result.append("кодировка ").append(response.get("content-encoding")).append(" · ").append(total).append(" байт").toString();
            Charset charset = StandardCharsets.UTF_8;
            for (String part : type.split(";")) if (part.trim().startsWith("charset=")) {
                try { charset = Charset.forName(part.trim().substring(8).replace("\"", "")); } catch (IllegalArgumentException ignored) { charset = StandardCharsets.UTF_8; }
            }
            String body = new String(data, charset).replaceAll("(?i)(\"(?:access_token|refresh_token|token|password|oauth_token|authorization)\"\\s*:\\s*)\"[^\"]*\"", "$1\"[скрыто]\"");
            result.append(total).append(" байт\n").append(body);
            if (total > bytes.size() || data.length == LIMIT) result.append("\n[Превью ограничено 16 КБ]");
        } catch (IOException invalidEncoding) { result.append("не удалось декодировать · ").append(total).append(" байт"); }
        return result.toString();
    }
}
