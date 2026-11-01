package dev.alicebridge.bridge;

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
    // Limit live sockets and queued queries to keep a malfunctioning client from exhausting the
    // phone.
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    4,
                    16,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    public DnsProxy(Context c, LocalNetwork network, String resolver) throws IOException {
        context = c;
        this.network = network;
        this.gateway = network.gateway();
        upstream = InetAddress.getByName(resolver);
        InetAddress local = InetAddress.getByName(this.gateway);
        udp = new DatagramSocket(null);
        tcp = new ServerSocket();
        try {
            udp.bind(new InetSocketAddress(local, 5354));
            tcp.bind(new InetSocketAddress(local, 5354));
        } catch (IOException e) {
            udp.close();
            tcp.close();
            throw e;
        }
    }

    public void start() {
        new Thread(
                        () -> {
                            while (!closed) {
                                try {
                                    DatagramPacket p = new DatagramPacket(new byte[65535], 65535);
                                    udp.receive(p);
                                    String source = p.getAddress().getHostAddress();
                                    if (!network.client(source) && !gateway.equals(source))
                                        continue;
                                    byte[] query = Arrays.copyOf(p.getData(), p.getLength());
                                    try {
                                        workers.execute(
                                                () -> {
                                                    try {
                                                        byte[] response =
                                                                exchange(query, false, source);
                                                        udp.send(
                                                                new DatagramPacket(
                                                                        response,
                                                                        response.length,
                                                                        p.getSocketAddress()));
                                                    } catch (IOException e) {
                                                        error(e);
                                                    }
                                                });
                                    } catch (RejectedExecutionException e) {
                                        error(new IOException("Очередь DNS заполнена"));
                                    }
                                } catch (IOException e) {
                                    if (!closed) {
                                        error(e);
                                        break;
                                    }
                                }
                            }
                        },
                        "dns-udp")
                .start();
        new Thread(
                        () -> {
                            while (!closed) {
                                try {
                                    Socket client = tcp.accept();
                                    String source = client.getInetAddress().getHostAddress();
                                    if (!network.client(source) && !gateway.equals(source)) {
                                        client.close();
                                        continue;
                                    }
                                    try {
                                        workers.execute(
                                                () -> {
                                                    try (client) {
                                                        client.setSoTimeout(5000);
                                                        while (!closed) {
                                                            byte[] q;
                                                            try {
                                                                q =
                                                                        DnsWire.readFrame(
                                                                                client
                                                                                        .getInputStream());
                                                            } catch (EOFException end) {
                                                                break;
                                                            }
                                                            DnsWire.writeFrame(
                                                                    client.getOutputStream(),
                                                                    exchange(q, true, source));
                                                        }
                                                    } catch (IOException e) {
                                                        if (!closed) error(e);
                                                    }
                                                });
                                    } catch (RejectedExecutionException e) {
                                        client.close();
                                        error(new IOException("Очередь DNS заполнена"));
                                    }
                                } catch (IOException e) {
                                    if (!closed) {
                                        error(e);
                                        break;
                                    }
                                }
                            }
                        },
                        "dns-tcp")
                .start();
    }

    private byte[] exchange(byte[] query, boolean useTcp, String source) throws IOException {
        DnsWire.Question question = DnsWire.question(query);
        BridgeEvents.client(source);
        long started = System.nanoTime();
        byte[] response = null;
        String status = "";
        String answerDetail = "";
        String transport = useTcp ? "TCP" : "UDP";
        IOException last = null;
        // A lossy UDP path must not turn a working query into SERVFAIL: retry the same
        // backend over TCP before giving up. TCP is also required for truncated replies.
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean tcpNow = useTcp || attempt == 1;
            try {
                response = tcpNow ? exchangeTcp(query) : exchangeUdp(query);
                if (response.length < 12 || response[0] != query[0] || response[1] != query[1])
                    throw new IOException("DNS ID ответа не совпал");
                byte[] echoed = response.clone();
                echoed[2] &= 0x7f;
                DnsWire.Question actual = DnsWire.question(echoed);
                if (!actual.name().equals(question.name()) || actual.type() != question.type())
                    throw new IOException("DNS question ответа не совпал");
                if (!tcpNow && (response[2] & 0x02) != 0)
                    throw new IOException("Ответ усечён; повторяем по TCP");
                status = DnsWire.status(response);
                if (question.type() == 1)
                    answerDetail = " · ответ DNS: " + DnsWire.ipv4Answers(response);
                transport = tcpNow ? "TCP" : "UDP";
                last = null;
                break;
            } catch (IOException error) {
                last = error;
                response = null;
            }
        }
        if (last != null) {
            response = DnsWire.servfail(query, question);
            status = "SERVFAIL · " + last.getMessage();
            transport = useTcp ? "TCP" : "UDP→TCP";
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        BridgeEvents.log(
                context,
                "DNS",
                source,
                question.name()
                        + " · "
                        + type(question.type())
                        + " → "
                        + upstream.getHostAddress()
                        + " · "
                        + transport
                        + " · "
                        + ms
                        + " мс"
                        + answerDetail,
                status);
        BridgeEvents.log(
                context,
                "VERBOSE",
                source,
                "DNS "
                        + transport
                        + " · "
                        + question.name()
                        + " · type="
                        + question.type()
                        + " · id="
                        + DnsWire.u16(query, 0)
                        + " · upstream="
                        + upstream.getHostAddress()
                        + ":53"
                        + " · query_bytes="
                        + query.length
                        + " · response_bytes="
                        + response.length
                        + " · flags=0x"
                        + Integer.toHexString(DnsWire.u16(response, 2))
                        + " · answers="
                        + DnsWire.u16(response, 6)
                        + " · authority="
                        + DnsWire.u16(response, 8)
                        + " · additional="
                        + DnsWire.u16(response, 10)
                        + " · elapsed_ms="
                        + ms
                        + answerDetail,
                status);
        return response;
    }

    private byte[] exchangeUdp(byte[] query) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(upstream, 53);
            socket.setSoTimeout(2500);
            socket.send(new DatagramPacket(query, query.length));
            DatagramPacket result = new DatagramPacket(new byte[65535], 65535);
            socket.receive(result);
            return Arrays.copyOf(result.getData(), result.getLength());
        }
    }

    private byte[] exchangeTcp(byte[] query) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(upstream, 53), 4000);
            socket.setSoTimeout(4000);
            DnsWire.writeFrame(socket.getOutputStream(), query);
            return DnsWire.readFrame(socket.getInputStream());
        }
    }

    private String type(int type) {
        return type == 1 ? "A" : type == 28 ? "AAAA" : "TYPE" + type;
    }

    private void error(IOException e) {
        if (!closed) BridgeEvents.log(context, "ERROR", "DNS", e.getMessage(), "Ошибка");
    }

    @Override
    public void close() {
        closed = true;
        udp.close();
        try {
            tcp.close();
        } catch (IOException e) {
            android.util.Log.w("AliceBridge", "DNS TCP close", e);
        }
        workers.shutdownNow();
    }
}
