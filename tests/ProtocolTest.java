import dev.alice.bridge.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class ProtocolTest {
    private static int checks;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action a, String label) throws Exception {
        try { a.run(); } catch (IOException expected) { checks++; return; } throw new AssertionError(label);
    }
    private static ByteArrayInputStream bytes(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1)); }
    public static void main(String[] args) throws Exception {
        LocalNetwork local = new LocalNetwork("192.168.43.1", 24);
        check(local.cidr().equals("192.168.43.0/24"), "Tethering source CIDR");
        check(local.client("192.168.43.42") && !local.client("192.168.3.28"), "Accept only tethered subnet clients");
        check(!local.client("192.168.43.1") && !local.client("192.168.43.0") && !local.client("192.168.43.255"), "Exclude phone, network, broadcast");
        check(!local.client("not-an-ip"), "Reject invalid peer address");
        byte[] query = DnsWire.query("quasar.yandex.net", 0x1234);
        DnsWire.Question question = DnsWire.question(query);
        check(question.name().equals("quasar.yandex.net") && question.type() == 1, "DNS question");
        check(DnsWire.target(question.name()) && !DnsWire.target("quasar.yandex.net.evil.test"), "Exact domain allowlist");
        byte[] response = DnsWire.localAnswer(query, question, "192.168.43.1");
        check(DnsWire.u16(response, 0) == 0x1234, "DNS transaction ID");
        check(DnsWire.ipv4Answers(response).equals(List.of("192.168.43.1")), "Compressed A answer");
        check(DnsWire.status(response).startsWith("NOERROR"), "DNS status");
        check(DnsWire.status(DnsWire.servfail(query, question)).equals("SERVFAIL"), "DNS SERVFAIL");
        byte[] v6 = query.clone(); v6[v6.length - 3] = 28;
        byte[] noV6 = DnsWire.localAnswer(v6, DnsWire.question(v6), "192.168.43.1");
        check(DnsWire.u16(noV6, 6) == 0 && DnsWire.status(noV6).startsWith("NOERROR"), "IPv6 NODATA in proxy mode");
        rejects(() -> DnsWire.question(new byte[11]), "Truncated DNS");
        rejects(() -> DnsWire.ipv4Answers(Arrays.copyOf(response, response.length - 1)), "Truncated A answer");
        ByteArrayOutputStream frame = new ByteArrayOutputStream(); DnsWire.writeFrame(frame, query);
        check(Arrays.equals(query, DnsWire.readFrame(new ByteArrayInputStream(frame.toByteArray()))), "DNS TCP framing");
        rejects(() -> DnsWire.readFrame(bytes("\0\1x")), "Invalid TCP frame");

        HttpWire.Head head = HttpWire.head(bytes("HTTP/1.1 200 OK\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nContent-Length: 2\r\n\r\n"));
        ByteArrayOutputStream headerOut = new ByteArrayOutputStream(); head.write(headerOut);
        check(headerOut.toString(StandardCharsets.ISO_8859_1).contains("set-cookie: a=1\r\nset-cookie: b=2\r\n"), "Separate Set-Cookie fields");
        ByteArrayOutputStream body = new ByteArrayOutputStream(); HttpWire.body(bytes("OK"), body, head, false);
        check(body.toString().equals("OK"), "Content-length body unchanged");
        HttpWire.Head chunked = HttpWire.head(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"));
        String chunks = "4;test=x\r\ntest\r\n0\r\nX-Trailer: yes\r\n\r\n";
        body.reset(); HttpWire.body(bytes(chunks), body, chunked, false);
        check(body.toString().equals(chunks), "Chunk boundaries/extensions/trailers unchanged");
        ResponsePreview preview = new ResponsePreview(); body.reset();
        HttpWire.body(bytes(chunks), body, chunked, false, preview);
        check(body.toString().equals(chunks) && preview.describe(chunked, true).contains("4 байт\ntest"), "Capture decoded chunk payload without changing wire bytes");
        HttpWire.Head json = HttpWire.head(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"));
        ResponsePreview jsonPreview = new ResponsePreview();
        jsonPreview.write("{\"hasUpdate\":false,\"token\":\"private-value\"}".getBytes(StandardCharsets.UTF_8));
        String shown = jsonPreview.describe(json, true);
        check(shown.contains("hasUpdate") && shown.contains("[скрыто]") && !shown.contains("private-value"), "Show command response and redact credential");
        check(preview.describe(chunked, false).contains("отсутствует по протоколу"), "HEAD/204 has no body");
        rejects(() -> HttpWire.head(bytes("POST / HTTP/1.1\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n")), "Reject ambiguous framing");
        rejects(() -> HttpWire.head(bytes("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n")), "Reject duplicate Host");
        rejects(() -> HttpWire.body(bytes("O"), new ByteArrayOutputStream(), head, false), "Reject truncated body");
        rejects(() -> HttpWire.line(bytes("bad\n"), 100), "Reject invalid CRLF");
        rejects(() -> HttpWire.body(bytes("z\r\n"), new ByteArrayOutputStream(), chunked, false), "Reject invalid chunk size");

        check(adb(0x4e584e43, true).contains("CNXN"), "ADB CNXN");
        check(adb(0x48545541, true).contains("AUTH"), "ADB AUTH is not connected");
        check(adb(0x534c5453, true).contains("STLS"), "ADB TLS is not connected");
        rejects(() -> adb(0x4e584e43, false), "Reject arbitrary open port");
        System.out.println("PASS: " + checks + " protocol checks");
    }
    private static String adb(int command, boolean valid) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream()); byte[] h = new byte[24]; in.readFully(h);
                    int size = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(12); in.readFully(new byte[size]);
                    s.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(command).putInt(0x01000000)
                        .putInt(4096).putInt(0).putInt(0).putInt(valid ? command ^ -1 : 0).array());
                } catch (IOException e) { throw new RuntimeException(e); }
            });
            try { return AdbProbe.check("127.0.0.1", server.getLocalPort()); }
            finally { peer.get(5, TimeUnit.SECONDS); }
        }
    }
}
