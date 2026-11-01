package dev.alicebridge.bridge;

import android.content.Context;
import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Loopback-only authenticated SOCKS5 bridge. Every outbound socket is pinned to the physical
 * network.
 */
public final class HotspotSocks implements AutoCloseable {
    private final Context context;
    private volatile Network physical;
    private final String resolver, user, password;
    private final ServerSocket server;
    private final Set<Closeable> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(0, 64, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    // Never schedule a relay's work back into the pool blocked by its control connection.
    private final ThreadPoolExecutor pumps =
            new ThreadPoolExecutor(0, 64, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final ThreadPoolExecutor datagrams =
            new ThreadPoolExecutor(8, 32, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private final Map<Closeable, Network> outbound = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong
            rejectedSessions = new java.util.concurrent.atomic.AtomicLong(),
            rejectedUdp = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;
    private long lastError;

    private void error(Exception e) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastError < 3000) return;
            lastError = now;
        }
        BridgeEvents.log(
                context,
                "NETWORK",
                "Shizuku SOCKS",
                e.getClass().getSimpleName() + ": " + e.getMessage(),
                "Ошибка пересылки");
    }

    public HotspotSocks(
            Context context, Network physical, String resolver, String user, String password)
            throws IOException {
        this.context = context;
        this.physical = physical;
        this.resolver = resolver;
        this.user = user;
        this.password = password;
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
    }

    public int port() {
        return server.getLocalPort();
    }

    public synchronized void updatePhysical(Network network) throws IOException {
        if (network == null || closed) throw new IOException("Нет исходящей интернет-сети");
        physical = network;
        for (Map.Entry<Closeable, Network> e : outbound.entrySet())
            if (!network.equals(e.getValue()))
                try {
                    e.getKey().close();
                } catch (IOException ignored) {
                }
    }

    public void start() {
        new Thread(
                        () -> {
                            while (!closed)
                                try {
                                    Socket s = server.accept();
                                    sockets.add(s);
                                    try {
                                        workers.execute(() -> handle(s));
                                    } catch (RejectedExecutionException e) {
                                        rejectedSessions.incrementAndGet();
                                        error(
                                                new IOException(
                                                        "Лимит одновременных SOCKS-соединений", e));
                                        sockets.remove(s);
                                        s.close();
                                    }
                                } catch (IOException e) {
                                    if (!closed) break;
                                }
                        },
                        "shizuku-socks")
                .start();
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "active_sessions",
                workers.getActiveCount(),
                "active_stream_pumps",
                pumps.getActiveCount(),
                "udp_active",
                datagrams.getActiveCount(),
                "udp_queued",
                datagrams.getQueue().size(),
                "rejected_sessions",
                rejectedSessions.get(),
                "rejected_udp",
                rejectedUdp.get());
    }

    private static byte[] read(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        new DataInputStream(in).readFully(b);
        return b;
    }

    private static int octet(InputStream in) throws IOException {
        int n = in.read();
        if (n < 0) throw new EOFException();
        return n;
    }

    private void handle(Socket client) {
        String stage = "SOCKS handshake";
        try (client) {
            client.setSoTimeout(10000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            if (octet(in) != 5) throw new IOException("SOCKS version");
            byte[] methods = read(in, octet(in));
            boolean auth = false;
            for (byte m : methods) auth |= m == 2;
            out.write(new byte[] {5, (byte) (auth ? 2 : 255)});
            out.flush();
            if (!auth) {
                error(new IOException("HEV не предложил SOCKS-аутентификацию"));
                return;
            }
            if (octet(in) != 1) return;
            String u = new String(read(in, octet(in)), StandardCharsets.UTF_8),
                    p = new String(read(in, octet(in)), StandardCharsets.UTF_8);
            boolean valid =
                    MessageDigest.isEqual(
                                    u.getBytes(StandardCharsets.UTF_8),
                                    user.getBytes(StandardCharsets.UTF_8))
                            & MessageDigest.isEqual(
                                    p.getBytes(StandardCharsets.UTF_8),
                                    password.getBytes(StandardCharsets.UTF_8));
            out.write(new byte[] {1, (byte) (valid ? 0 : 1)});
            out.flush();
            if (!valid) {
                error(new IOException("SOCKS-аутентификация не прошла"));
                return;
            }
            byte[] head = read(in, 4);
            if (head[0] != 5 || head[2] != 0) return;
            InetAddress address = address(in, head[3] & 255);
            int port = (octet(in) << 8) | octet(in);
            stage =
                    "SOCKS command="
                            + head[1]
                            + " destination="
                            + address.getHostAddress()
                            + ":"
                            + port;
            if (head[1] == 1) {
                try (Socket remote = new Socket()) {
                    sockets.add(remote);
                    try {
                        Network selected = physical;
                        if (selected != null) {
                            outbound.put(remote, selected);
                            selected.bindSocket(remote);
                        }
                        remote.connect(
                                new InetSocketAddress(
                                        port == 53 ? InetAddress.getByName(resolver) : address,
                                        port),
                                10000);
                        remote.setSoTimeout(300000);
                        client.setSoTimeout(300000);
                        reply(out, 0, remote.getLocalPort());
                        if (port == 53) {
                            dnsTcp(in, out, remote);
                            return;
                        }
                        Future<?> upstream =
                                pumps.submit(() -> copyTraffic(in, remote, "TCP request"));
                        copyTraffic(remote.getInputStream(), client, "TCP response");
                        upstream.cancel(true);
                    } finally {
                        outbound.remove(remote);
                        sockets.remove(remote);
                    }
                }
            } else if (head[1] == 3) {
                udp(client, in, out);
            } else reply(out, 7, 0);
        } catch (Exception e) {
            if (!closed && !(e instanceof EOFException))
                error(new IOException(stage + ": " + e, e));
        } finally {
            sockets.remove(client);
        }
    }

    private static InetAddress address(InputStream in, int type) throws IOException {
        if (type == 1) return InetAddress.getByAddress(read(in, 4));
        if (type == 4) return InetAddress.getByAddress(read(in, 16));
        // HEV supplies IP addresses. Avoid resolving hostnames through an unintended system DNS.
        throw new IOException("Numeric SOCKS addresses required");
    }

    private static void reply(OutputStream out, int code, int port) throws IOException {
        out.write(new byte[] {5, (byte) code, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
        out.flush();
    }

    private void copyTraffic(InputStream in, Socket destination, String direction) {
        try {
            byte[] buffer = new byte[16384];
            int count;
            boolean first = true;
            while ((count = in.read(buffer)) != -1) {
                if (count == 0) continue;
                destination.getOutputStream().write(buffer, 0, count);
                destination.getOutputStream().flush();
                if (first && context != null) {
                    first = false;
                    BridgeEvents.log(
                            context,
                            "NETWORK",
                            "Shizuku SOCKS",
                            direction + " · " + count + " bytes",
                            "Трафик");
                }
            }
            destination.shutdownOutput();
        } catch (IOException e) {
            if (!closed) error(new IOException(direction + ": " + e, e));
        }
    }

    private void dnsTcp(InputStream in, OutputStream out, Socket remote) throws IOException {
        while (!closed) {
            byte[] query;
            try {
                query = DnsWire.readFrame(in);
            } catch (EOFException e) {
                return;
            }
            long start = System.nanoTime();
            DnsWire.writeFrame(remote.getOutputStream(), query);
            byte[] response = DnsWire.readFrame(remote.getInputStream());
            validate(query, response);
            DnsWire.writeFrame(out, response);
            logDns(query, response, start);
        }
    }

    private void udp(Socket control, InputStream in, OutputStream out) throws IOException {
        try (DatagramSocket relay = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            sockets.add(relay);
            try {
                reply(out, 0, relay.getLocalPort());
                control.setSoTimeout(0);
                relay.setSoTimeout(1000);
                Future<?> reader =
                        pumps.submit(
                                () -> {
                                    SocketAddress peer = null;
                                    while (!closed && !relay.isClosed())
                                        try {
                                            DatagramPacket packet =
                                                    new DatagramPacket(new byte[65535], 65535);
                                            relay.receive(packet);
                                            if (!packet.getAddress().isLoopbackAddress()) continue;
                                            if (peer == null) peer = packet.getSocketAddress();
                                            if (!peer.equals(packet.getSocketAddress())) continue;
                                            byte[] bytes =
                                                    Arrays.copyOf(
                                                            packet.getData(), packet.getLength());
                                            SocketAddress destination = peer;
                                            try {
                                                datagrams.execute(
                                                        () ->
                                                                udpExchange(
                                                                        relay, destination, bytes));
                                            } catch (RejectedExecutionException busy) {
                                                rejectedUdp.incrementAndGet();
                                                error(
                                                        new IOException(
                                                                "Очередь UDP заполнена", busy));
                                            }
                                        } catch (SocketTimeoutException ignored) {
                                        } catch (IOException e) {
                                            break;
                                        }
                                });
                while (!closed && in.read() != -1) {}
                reader.cancel(true);
            } finally {
                sockets.remove(relay);
            }
        }
    }

    private void udpExchange(DatagramSocket relay, SocketAddress peer, byte[] packet) {
        String stage = "UDP header";
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(packet);
            byte[] head = read(in, 4);
            if (head[0] != 0 || head[1] != 0 || head[2] != 0) return;
            InetAddress address = address(in, head[3] & 255);
            if (address.isMulticastAddress()) return;
            int port = octet(in) * 256 + octet(in);
            int header = packet.length - in.available();
            stage = "UDP " + address.getHostAddress() + ":" + port;
            byte[] body = Streams.readBounded(in, 65507);
            long start = System.nanoTime();
            try (DatagramSocket remote = new DatagramSocket(null)) {
                sockets.add(remote);
                try {
                    Network selected = physical;
                    if (selected != null) {
                        outbound.put(remote, selected);
                        selected.bindSocket(remote);
                    }
                    remote.bind(new InetSocketAddress(0));
                    remote.connect(port == 53 ? InetAddress.getByName(resolver) : address, port);
                    remote.setSoTimeout(10000);
                    remote.send(new DatagramPacket(body, body.length));
                    DatagramPacket result = new DatagramPacket(new byte[65507], 65507);
                    remote.receive(result);
                    byte[] response = Arrays.copyOf(result.getData(), result.getLength());
                    if (port == 53) {
                        validate(body, response);
                        logDns(body, response, start);
                    }
                    byte[] framed = new byte[header + response.length];
                    System.arraycopy(packet, 0, framed, 0, header);
                    System.arraycopy(response, 0, framed, header, response.length);
                    relay.send(new DatagramPacket(framed, framed.length, peer));
                } finally {
                    outbound.remove(remote);
                    sockets.remove(remote);
                }
            }
        } catch (Exception e) {
            if (!closed) error(new IOException(stage + ": " + e, e));
        }
    }

    private static void validate(byte[] q, byte[] r) throws IOException {
        if (q.length < 12 || r.length < 12 || q[0] != r[0] || q[1] != r[1])
            throw new IOException("DNS ID mismatch");
        byte[] echo = r.clone();
        echo[2] &= 0x7f;
        DnsWire.Question actual = DnsWire.question(echo), expected = DnsWire.question(q);
        if (!actual.name().equals(expected.name()) || actual.type() != expected.type())
            throw new IOException("DNS question mismatch");
    }

    private void logDns(byte[] query, byte[] response, long start) {
        if (context == null) return;
        try {
            DnsWire.Question q = DnsWire.question(query);
            String status = DnsWire.status(response);
            BridgeEvents.log(
                    context,
                    "DNS",
                    "Клиент хотспота",
                    q.name()
                            + " · TYPE"
                            + q.type()
                            + " → "
                            + resolver
                            + " · "
                            + ((System.nanoTime() - start) / 1000000)
                            + " мс"
                            + (q.type() == 1
                                    ? " · ответ DNS: " + DnsWire.ipv4Answers(response)
                                    : ""),
                    status);

        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        for (Closeable c : sockets)
            try {
                c.close();
            } catch (IOException ignored) {
            }
        sockets.clear();
        outbound.clear();
        workers.shutdownNow();
        pumps.shutdownNow();
        datagrams.shutdownNow();
    }
}
