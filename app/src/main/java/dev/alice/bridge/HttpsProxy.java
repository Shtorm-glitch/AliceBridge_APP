package dev.alice.bridge;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;

/** Allowlisted transparent HTTPS relay. No command payloads are generated locally. */
public final class HttpsProxy implements AutoCloseable {
    private final Context context;
    private final String resolver;
    private final LocalNetwork network;
    private final SSLServerSocket listener;
    private final ServerSocket plain;
    private final SSLSocketFactory outgoing;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 12, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private static final AtomicLong EXCHANGES = new AtomicLong();

    public HttpsProxy(Context context, LocalNetwork network, String resolver, File identity) throws Exception {
        this.context = context; this.network = network; this.resolver = resolver;
        String gateway = network.gateway();
        TlsIdentity.Contexts tls = TlsIdentity.load(identity, new File(context.getFilesDir(), "origin-pins")); outgoing = tls.upstream().getSocketFactory();
        listener = (SSLServerSocket) tls.server().getServerSocketFactory().createServerSocket();
        plain = new ServerSocket();
        listener.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        SSLParameters params = listener.getSSLParameters(); params.setApplicationProtocols(new String[]{"http/1.1"}); listener.setSSLParameters(params);
        try { listener.bind(new InetSocketAddress(gateway, 8443)); plain.bind(new InetSocketAddress(gateway, 8080)); }
        catch (IOException e) { listener.close(); plain.close(); throw e; }
    }
    public void start() {
        new Thread(() -> {
            while (!closed) {
                try {
                    SSLSocket client = (SSLSocket) listener.accept();
                    if (!selected(client)) { client.close(); continue; }
                    sockets.add(client);
                    try { workers.execute(() -> relay(client, true)); }
                    catch (RejectedExecutionException busy) { sockets.remove(client); client.close(); report("Лимит HTTPS-соединений", "Занято"); }
                } catch (IOException e) { if (!closed) { report(e.toString(), "Ошибка listener"); break; } }
            }
        }, "https-listener").start();
        new Thread(() -> {
            while (!closed) try {
                Socket client = plain.accept();
                if (!selected(client)) { client.close(); continue; }
                sockets.add(client);
                try { workers.execute(() -> relay(client, false)); }
                catch (RejectedExecutionException e) { sockets.remove(client); client.close(); report("Лимит HTTP-соединений", "Занято"); }
            } catch (IOException e) { if (!closed) { report(e.toString(), "Ошибка HTTP listener"); break; } }
        }, "http-listener").start();
    }
    private void relay(Socket client, boolean secure) {
        long id = EXCHANGES.incrementAndGet(), started = System.nanoTime();
        String phase = "Колонка → телефон · " + (secure ? "TLS" : "HTTP"), destination = Session.station;
        try (client) {
            client.setSoTimeout(30000); if (secure) ((SSLSocket) client).startHandshake();
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = client.getOutputStream();
            HttpWire.Head request = HttpWire.head(input);
            String[] first = request.first().split(" ", 3);
            if (first.length != 3 || !first[2].equals("HTTP/1.1") || !first[1].startsWith("/")) throw new IOException("Ожидается HTTP/1.1 origin-form");
            String host = request.get("host").toLowerCase(Locale.ROOT);
            String suffix = secure ? ":443" : ":80";
            if (host.endsWith(suffix)) host = host.substring(0, host.length() - suffix.length());
            if (!DnsWire.target(host)) throw new IOException("Host вне списка проксирования");
            String path = first[1].split("\\?", 2)[0]; // Query tokens and request bodies are never logged.
            destination = host;
            Session.log(context, "REQUEST", host, "#" + id + " " + first[0] + " " + path + " · DNS " + resolver, "Запрос");
            phase = "Телефон → внешний DNS";
            String origin = resolve(host);
            phase = "Телефон → внешний сервер " + origin + " · TLS";
            Socket raw = new Socket(); sockets.add(raw);
            try (raw) {
                // Like the reference nginx proxy_pass https://$host: relay the original request over
                // TLS to the external DNS server, including when the speaker itself used HTTP.
                raw.connect(new InetSocketAddress(origin, 443), 5000); raw.setSoTimeout(60000);
                try (SSLSocket remote = (SSLSocket) outgoing.createSocket(raw, host, 443, true)) {
                        SSLSocket tls = remote;
                        SSLParameters params = tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");
                        params.setApplicationProtocols(new String[]{"http/1.1"}); tls.setSSLParameters(params); tls.startHandshake();
                        TlsIdentity.verifyHostname((java.security.cert.X509Certificate) tls.getSession().getPeerCertificates()[0], host);
                    BufferedInputStream responseInput = new BufferedInputStream(remote.getInputStream()); OutputStream requestOutput = remote.getOutputStream();
                    boolean websocket = request.get("upgrade").equalsIgnoreCase("websocket");
                    if (request.get("expect").equalsIgnoreCase("100-continue")) {
                        output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); output.flush(); request.remove("expect");
                    }
                    if (!websocket) request.set("connection", "close");
                    request.set("host", host);
                    request.set("accept-encoding", "identity");
                    request.write(requestOutput); HttpWire.body(input, requestOutput, request, false);
                    HttpWire.Head response; int code;
                    do {
                        response = HttpWire.head(responseInput); String[] status = response.first().split(" ", 3);
                        if (status.length < 2) throw new IOException("Некорректный HTTP-ответ"); code = Integer.parseInt(status[1]);
                        if (code < 100 || code > 599) throw new IOException("Некорректный HTTP-код");
                    } while (code >= 100 && code < 200 && code != 101);
                    Session.httpEvidence(context, client.getInetAddress().getHostAddress() + " · " + host, path, code);
                    Session.log(context, secure ? "TLS" : "HTTP", host, first[0] + " " + path + " · внешний сервер · " +
                        remote.getSession().getProtocol(), "HTTP " + code);
                    // This relay terminates HTTP/1.1; advertising an origin's QUIC endpoint would bypass observation.
                    response.remove("alt-svc");
                    if (code == 101 && websocket) {
                        response.write(output);
                        Thread upload = new Thread(() -> {
                            try { Streams.copy(input, requestOutput); requestOutput.flush(); remote.shutdownOutput(); }
                            catch (IOException e) { if (!closed) report("WebSocket upload: " + e.getClass().getSimpleName(), "Закрытие"); }
                        }, "websocket-upload"); upload.start();
                        try { Streams.copy(responseInput, output); output.flush(); }
                        finally { client.close(); remote.close(); upload.interrupt(); }
                    } else {
                        response.set("connection", "close"); response.write(output);
                        boolean hasBody = !first[0].equals("HEAD") && code != 204 && code != 304 && code >= 200;
                        ResponsePreview preview = new ResponsePreview();
                        if (hasBody) HttpWire.body(responseInput, output, response, true, preview);
                        long ms = (System.nanoTime() - started) / 1_000_000;
                        Session.log(context, "RESPONSE", host, "#" + id + " " + first[0] + " " + path + " → " + origin + " · " + ms + " мс\n" +
                            preview.describe(response, hasBody), "HTTP " + code);
                    }
                }
            } finally { sockets.remove(raw); }
        } catch (Exception e) {
            if (!closed) {
                String message = String.valueOf(e.getMessage()).split("\n", 2)[0];
                if (message.length() > 350) message = message.substring(0, 350);
                Session.log(context, "ERROR", destination, "#" + id + " " + phase + "\n" + e.getClass().getSimpleName() + ": " + message, "Ответ не получен");
            }
        } finally { sockets.remove(client); }
    }
    private String resolve(String host) throws IOException {
        int id = new SecureRandom().nextInt(65536); byte[] q = DnsWire.query(host, id), answer;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(resolver), 53); socket.setSoTimeout(4000);
            socket.send(new DatagramPacket(q, q.length)); DatagramPacket r = new DatagramPacket(new byte[65535], 65535); socket.receive(r);
            answer = Arrays.copyOf(r.getData(), r.getLength());
        }
        if (DnsWire.u16(answer, 0) != id || !DnsWire.status(answer).startsWith("NOERROR")) throw new IOException("Нет корректного DNS-ответа origin");
        if ((answer[2] & 2) != 0) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(resolver, 53), 4000); socket.setSoTimeout(4000);
                DnsWire.writeFrame(socket.getOutputStream(), q); answer = DnsWire.readFrame(socket.getInputStream());
                if (DnsWire.u16(answer, 0) != id) throw new IOException("DNS ID origin не совпал");
            }
        }
        List<String> ips = DnsWire.ipv4Answers(answer); if (ips.isEmpty()) throw new IOException("Внешний DNS не вернул IPv4 для " + host);
        if (!ips.contains(resolver)) throw new IOException("Для командного домена DNS вернул другой адрес: " + ips + ". Разрешён только внешний сервер " + resolver);
        return resolver;
    }
    private boolean selected(Socket client) {
        String ip = client.getInetAddress().getHostAddress();
        return network.client(ip) && !Session.station.isEmpty() && Session.station.equals(ip);
    }
    private void report(String detail, String status) { Session.log(context, "TLS", Session.station, detail, status); }
    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException e) { android.util.Log.w("AliceBridge", "TLS listener close", e); }
        try { plain.close(); } catch (IOException e) { android.util.Log.w("AliceBridge", "HTTP listener close", e); }
        for (Socket s : sockets) try { s.close(); } catch (IOException e) { android.util.Log.w("AliceBridge", "TLS socket close", e); }
        workers.shutdownNow();
    }
}
