package dev.alice.bridge;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

public final class DnsProxy implements AutoCloseable {
    private final Context context;
    private final LocalNetwork network;
    private final InetAddress upstream;
    private final String gateway;
    private final DatagramSocket udp;
    private final ServerSocket tcp;
    // Limit live sockets and queued queries to keep a malfunctioning client from exhausting the phone.
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    public DnsProxy(Context c, LocalNetwork network, String resolver) throws IOException {
        context = c; this.network = network; this.gateway = network.gateway(); upstream = InetAddress.getByName(resolver);
        InetAddress local = InetAddress.getByName(this.gateway);
        udp = new DatagramSocket(null);
        tcp = new ServerSocket();
        try {
            udp.bind(new InetSocketAddress(local, 5354));
            tcp.bind(new InetSocketAddress(local, 5354));
        } catch (IOException e) { udp.close(); tcp.close(); throw e; }
    }
    public void start() {
        new Thread(() -> {
            while (!closed) {
                try {
                    DatagramPacket p = new DatagramPacket(new byte[65535], 65535);
                    udp.receive(p);
                    String source = p.getAddress().getHostAddress();
                    if (!network.client(source)) continue;
                    byte[] query = Arrays.copyOf(p.getData(), p.getLength());
                    try { workers.execute(() -> {
                        try {
                            byte[] response = exchange(query, false, source);
                            udp.send(new DatagramPacket(response, response.length, p.getSocketAddress()));
                        } catch (IOException e) { error(e); }
                    }); } catch (RejectedExecutionException e) { error(new IOException("Очередь DNS заполнена")); }
                } catch (IOException e) { if (!closed) { error(e); break; } }
            }
        }, "dns-udp").start();
        new Thread(() -> {
            while (!closed) {
                try {
                    Socket client = tcp.accept();
                    String source = client.getInetAddress().getHostAddress();
                    if (!network.client(source)) { client.close(); continue; }
                    try { workers.execute(() -> {
                        try (client) {
                            client.setSoTimeout(5000);
                            while (!closed) {
                                byte[] q;
                                try { q = DnsWire.readFrame(client.getInputStream()); }
                                catch (EOFException end) { break; }
                                DnsWire.writeFrame(client.getOutputStream(), exchange(q, true, source));
                            }
                        } catch (IOException e) { if (!closed) error(e); }
                    }); } catch (RejectedExecutionException e) { client.close(); error(new IOException("Очередь DNS заполнена")); }
                } catch (IOException e) { if (!closed) { error(e); break; } }
            }
        }, "dns-tcp").start();
    }
    private byte[] exchange(byte[] query, boolean useTcp, String source) throws IOException {
        DnsWire.Question question = DnsWire.question(query);
        synchronized (Session.class) {
            if (Session.flowActive) Session.followStation(context, Session.tracker.observe(source, System.nanoTime() / 1_000_000));
        }
        long started = System.nanoTime();
        byte[] response;
        String status;
        String answerDetail = "";
        try {
            if (useTcp) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(upstream, 53), 4000); s.setSoTimeout(4000);
                    DnsWire.writeFrame(s.getOutputStream(), query);
                    response = DnsWire.readFrame(s.getInputStream());
                }
            } else {
                try (DatagramSocket s = new DatagramSocket()) {
                    s.connect(upstream, 53); s.setSoTimeout(4000);
                    s.send(new DatagramPacket(query, query.length));
                    DatagramPacket result = new DatagramPacket(new byte[65535], 65535); s.receive(result);
                    response = Arrays.copyOf(result.getData(), result.getLength());
                }
            }
            if (response.length < 12 || response[0] != query[0] || response[1] != query[1])
                throw new IOException("DNS ID ответа не совпал");
            // Validate echoed question as well as ID; DNS replies have the response bit set.
            byte[] echoed = response.clone(); echoed[2] &= 0x7f;
            DnsWire.Question actual = DnsWire.question(echoed);
            if (!actual.name().equals(question.name()) || actual.type() != question.type())
                throw new IOException("DNS question ответа не совпал");
            status = DnsWire.status(response);
            if (question.type() == 1) answerDetail = " · ответ DNS: " + DnsWire.ipv4Answers(response);

        } catch (IOException e) {
            response = DnsWire.servfail(query, question); status = "SERVFAIL · " + e.getMessage();
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        Session.log(context, "DNS", source, question.name() + " · " + type(question.type()) +
            " → " + upstream.getHostAddress() + " · " + (useTcp ? "TCP" : "UDP") + " · " + ms + " мс" + answerDetail, status);
        synchronized (Session.class) {
            Session.domains.put(question.name(), status);
            if (Session.domains.size() > 100) Session.domains.remove(Session.domains.keySet().iterator().next());
        }
        return response;
    }
    private String type(int type) { return type == 1 ? "A" : type == 28 ? "AAAA" : "TYPE" + type; }
    private void error(IOException e) { if (!closed) Session.log(context, "ERROR", "DNS", e.getMessage(), "Ошибка"); }
    @Override public void close() {
        closed = true; udp.close();
        try { tcp.close(); } catch (IOException e) { android.util.Log.w("AliceBridge", "DNS TCP close", e); }
        workers.shutdownNow();
    }
}
