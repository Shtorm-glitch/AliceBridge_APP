package dev.alicebridge.bridge;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootShell {
    public record Result(int code, String output) {
        public void requireSuccess() throws IOException {
            if (code != 0) throw new IOException("su exit=" + code + ": " + output);
        }
    }

    public static Result run(Context c, String command, int seconds) throws Exception {
        File log = File.createTempFile("shell-", ".log", c.getCacheDir());
        Process p = null;
        try {
            p =
                    new ProcessBuilder("su", "-c", command)
                            .redirectErrorStream(true)
                            .redirectOutput(log)
                            .start();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Тайм-аут root-команды (" + seconds + " с)");
            }
            try (InputStream in = new FileInputStream(log)) {
                return new Result(
                        p.exitValue(),
                        new String(Streams.readBounded(in, 64000), StandardCharsets.UTF_8).trim());
            }
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
            if (!log.delete()) android.util.Log.w("AliceBridge", "Temporary root log not removed");
        }
    }

    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static void validateIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Нужен IPv4-адрес");
        for (String p : parts) {
            if (!p.matches("[0-9]{1,3}") || Integer.parseInt(p) > 255)
                throw new IllegalArgumentException("Неверный IPv4-адрес");
        }
    }

    public static void validateInterface(String iface) {
        if (!iface.matches("[a-zA-Z0-9_.-]{1,15}"))
            throw new IllegalArgumentException("Неверный сетевой интерфейс");
    }

    public static String cleanup() {
        // Deterministic app-owned chains can be recovered after a killed process or interrupted
        // start.
        return "set -e\n"
                + "command -v iptables >/dev/null\n"
                + "iptables -w 5 -t nat -S PREROUTING >/dev/null\n"
                + "iptables -w 5 -S INPUT >/dev/null\n"
                + "if iptables -w 5 -t nat -S ALICE_DNS >/dev/null 2>&1; then\n"
                + " while iptables -w 5 -t nat -C PREROUTING -j ALICE_DNS 2>/dev/null; do"
                + " iptables -w 5 -t nat -D PREROUTING -j ALICE_DNS; done\n"
                + " iptables -w 5 -t nat -F ALICE_DNS\n"
                + " iptables -w 5 -t nat -X ALICE_DNS\n"
                + "fi\n"
                + "if iptables -w 5 -t filter -S ALICE_DNS_IN >/dev/null 2>&1; then\n"
                + " while iptables -w 5 -t filter -C INPUT -j ALICE_DNS_IN 2>/dev/null; do"
                + " iptables -w 5 -t filter -D INPUT -j ALICE_DNS_IN; done\n"
                + " iptables -w 5 -t filter -F ALICE_DNS_IN\n"
                + " iptables -w 5 -t filter -X ALICE_DNS_IN\n"
                + "fi\n"
                + "if command -v ip6tables >/dev/null && ip6tables -w 5 -S ALICE_DNS6 >/dev/null"
                + " 2>&1; then\n"
                + " for chain in INPUT FORWARD; do while ip6tables -w 5 -C $chain -j ALICE_DNS6"
                + " 2>/dev/null; do ip6tables -w 5 -D $chain -j ALICE_DNS6; done; done\n"
                + " ip6tables -w 5 -F ALICE_DNS6\n"
                + " ip6tables -w 5 -X ALICE_DNS6\n"
                + "fi\n";
    }

    /** Prefer DHCP IPv4 DNS over silently forwarding client IPv6 DNS around the proxy. */
    public static String ipv6DnsGuard(String iface) {
        validateInterface(iface);
        return "set -e\ncommand -v ip6tables >/dev/null\nip6tables -w 5 -N ALICE_DNS6\n"
                + "ip6tables -w 5 -A ALICE_DNS6 -i "
                + quote(iface)
                + " -p udp --dport 53 -j REJECT\n"
                + "ip6tables -w 5 -A ALICE_DNS6 -i "
                + quote(iface)
                + " -p tcp --dport 53 -j REJECT\n"
                + "ip6tables -w 5 -I INPUT 1 -j ALICE_DNS6\n"
                + "ip6tables -w 5 -I FORWARD 1 -j ALICE_DNS6\n";
    }

    public static String routing(String iface, String station, String gateway) {
        validateInterface(iface);
        validateIpv4(gateway);
        String[] source = station.split("/", -1);
        validateIpv4(source[0]);
        if (source.length > 2
                || (source.length == 2
                        && (!source[1].matches("[0-9]{1,2}")
                                || Integer.parseInt(source[1]) < 1
                                || Integer.parseInt(source[1]) > 32)))
            throw new IllegalArgumentException("Неверная подсеть хотспота");
        String scope = " -i " + quote(iface) + " -s " + quote(station);
        StringBuilder s = new StringBuilder("set -e\n");
        s.append("iptables -w 5 -t nat -N ALICE_DNS\niptables -w 5 -N ALICE_DNS_IN\n");
        for (String protocol : new String[] {"udp", "tcp"}) {
            s.append("iptables -w 5 -t nat -A ALICE_DNS")
                    .append(scope)
                    .append(" -p ")
                    .append(protocol)
                    .append(" --dport 53 -j DNAT --to-destination ")
                    .append(quote(gateway + ":5354"))
                    .append('\n');
            s.append("iptables -w 5 -A ALICE_DNS_IN")
                    .append(scope)
                    .append(" -d ")
                    .append(quote(gateway))
                    .append(" -p ")
                    .append(protocol)
                    .append(" --dport 5354 -j ACCEPT\n");
        }
        s.append(
                "iptables -w 5 -I INPUT 1 -j ALICE_DNS_IN\n"
                        + "iptables -w 5 -t nat -I PREROUTING 1 -j ALICE_DNS\n");
        return s.toString();
    }
}
