package dev.alicebridge.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** DNS wire format, independently implemented from RFC 1035. No Android dependency. */
public final class DnsWire {
    public record Question(String name, int type, int end) {}

    public static int u16(byte[] p, int off) throws IOException {
        if (off < 0 || off + 2 > p.length) throw new IOException("Truncated DNS packet");
        return ((p[off] & 255) << 8) | (p[off + 1] & 255);
    }

    public static Question question(byte[] packet) throws IOException {
        if (packet.length < 12 || u16(packet, 4) != 1 || (packet[2] & 128) != 0)
            throw new IOException("Expected one DNS question");
        StringBuilder name = new StringBuilder();
        int i = 12;
        while (true) {
            if (i >= packet.length) throw new IOException("Missing DNS name");
            int len = packet[i++] & 255;
            if (len == 0) break;
            if (len > 63 || i + len > packet.length || name.length() + len > 253)
                throw new IOException("Invalid DNS label");
            if (name.length() > 0) name.append('.');
            name.append(new String(packet, i, len, StandardCharsets.US_ASCII));
            i += len;
        }
        int type = u16(packet, i);
        if (u16(packet, i + 2) != 1) throw new IOException("Unsupported DNS class");
        return new Question(name.toString().toLowerCase(Locale.ROOT), type, i + 4);
    }

    public static byte[] servfail(byte[] q, Question question) {
        byte[] result = Arrays.copyOf(q, question.end());
        result[2] = (byte) (0x80 | (q[2] & 0x79));
        result[3] = (byte) (0x82 | (q[3] & 0x10));
        Arrays.fill(result, 6, 12, (byte) 0);
        return result;
    }

    public static String status(byte[] answer) throws IOException {
        if (answer.length < 12 || (answer[2] & 128) == 0)
            throw new IOException("Invalid DNS response");
        int code = answer[3] & 15;
        return switch (code) {
            case 0 -> "NOERROR · " + u16(answer, 6) + " ответов";
            case 1 -> "FORMERR";
            case 2 -> "SERVFAIL";
            case 3 -> "NXDOMAIN";
            case 4 -> "NOTIMP";
            case 5 -> "REFUSED";
            default -> "RCODE " + code;
        };
    }

    public static boolean target(String domain) {
        return Set.of("quasar.yandex.net", "quasar.yandex.ru", "scbh.yandex.net").contains(domain);
    }

    public static byte[] query(String domain, int id) throws IOException {
        return query(domain, 1, id);
    }

    public static byte[] query(String domain, int type, int id) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeShort(id);
        data.writeShort(0x0100);
        data.writeShort(1);
        data.write(new byte[6]);
        for (String label : domain.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            data.writeByte(b.length);
            data.write(b);
        }
        data.writeByte(0);
        data.writeShort(type);
        data.writeShort(1);
        return out.toByteArray();
    }

    private static int nameEnd(byte[] p, int offset) throws IOException {
        for (int n = 0; n < 128; n++) {
            if (offset >= p.length) throw new IOException("Truncated DNS name");
            int len = p[offset++] & 255;
            if (len == 0) return offset;
            if ((len & 0xc0) == 0xc0) {
                u16(p, offset - 1);
                return offset + 1;
            }
            if (len > 63 || offset + len > p.length) throw new IOException("Invalid DNS name");
            offset += len;
        }
        throw new IOException("DNS name too long");
    }

    public static List<String> ipv4Answers(byte[] p) throws IOException {
        if (p.length < 12) throw new IOException("Short DNS packet");
        int offset = 12;
        for (int n = 0; n < u16(p, 4); n++) {
            offset = nameEnd(p, offset);
            u16(p, offset + 2);
            offset += 4;
        }
        List<String> result = new ArrayList<>();
        for (int n = 0; n < u16(p, 6); n++) {
            offset = nameEnd(p, offset);
            int type = u16(p, offset), clazz = u16(p, offset + 2), len = u16(p, offset + 8);
            offset += 10;
            if (offset + len > p.length) throw new IOException("Truncated DNS answer");
            if (type == 1 && clazz == 1 && len == 4)
                result.add(
                        (p[offset] & 255)
                                + "."
                                + (p[offset + 1] & 255)
                                + "."
                                + (p[offset + 2] & 255)
                                + "."
                                + (p[offset + 3] & 255));
            offset += len;
        }
        return result;
    }

    public static List<String> txtAnswers(byte[] packet) throws IOException {
        int offset = 12;
        for (int i = 0; i < u16(packet, 4); i++) {
            offset = nameEnd(packet, offset);
            u16(packet, offset + 2);
            offset += 4;
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < u16(packet, 6); i++) {
            offset = nameEnd(packet, offset);
            int type = u16(packet, offset),
                    clazz = u16(packet, offset + 2),
                    length = u16(packet, offset + 8);
            offset += 10;
            int end = offset + length;
            if (end > packet.length) throw new IOException("Truncated DNS TXT response");
            if (type == 16 && clazz == 1) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                int position = offset;
                while (position < end) {
                    int count = packet[position++] & 255;
                    if (position + count > end) throw new IOException("Truncated DNS TXT string");
                    text.write(packet, position, count);
                    position += count;
                }
                result.add(new String(text.toByteArray(), StandardCharsets.UTF_8));
            }
            offset = end;
        }
        return result;
    }

    public static byte[] localAnswer(byte[] q, Question question, String gateway)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = Arrays.copyOf(q, question.end());
        header[2] = (byte) (0x80 | (q[2] & 1));
        header[3] = (byte) 0x80;
        Arrays.fill(header, 6, 12, (byte) 0);
        if (question.type() == 1) header[7] = 1;
        out.write(header);
        if (question.type() == 1) {
            DataOutputStream data = new DataOutputStream(out);
            data.writeShort(0xc00c);
            data.writeShort(1);
            data.writeShort(1);
            data.writeInt(0);
            data.writeShort(4);
            for (String octet : gateway.split("\\.")) data.writeByte(Integer.parseInt(octet));
        }
        return out.toByteArray();
    }

    public static byte[] readFrame(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int len = data.readUnsignedShort();
        if (len < 12) throw new IOException("Short DNS frame");
        byte[] packet = new byte[len];
        data.readFully(packet);
        return packet;
    }

    public static void writeFrame(OutputStream out, byte[] packet) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeShort(packet.length);
        data.write(packet);
        data.flush();
    }
}
