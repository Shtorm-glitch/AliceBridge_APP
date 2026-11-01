package dev.alice.bridge;

/** IPv4 subnet belonging to the Wi-Fi tethering interface. */
public record LocalNetwork(String gateway, int prefix) {
    public LocalNetwork {
        number(gateway);
        if (prefix < 1 || prefix > 32) throw new IllegalArgumentException("Invalid tethering prefix");
    }
    private static long number(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4");
        long result = 0;
        for (String p : parts) {
            if (!p.matches("[0-9]{1,3}") || Integer.parseInt(p) > 255) throw new IllegalArgumentException("Invalid IPv4");
            result = (result << 8) | Integer.parseInt(p);
        }
        return result;
    }
    public String cidr() {
        long value = number(gateway) & mask();
        return ((value >> 24) & 255) + "." + ((value >> 16) & 255) + "." + ((value >> 8) & 255) + "." + (value & 255) + "/" + prefix;
    }
    private long mask() { return (0xffffffffL << (32 - prefix)) & 0xffffffffL; }
    public boolean client(String ip) {
        try {
            long value = number(ip), local = number(gateway), network = local & mask();
            return value != local && value != network && value != (network | (~mask() & 0xffffffffL)) && (value & mask()) == network;
        } catch (IllegalArgumentException e) { return false; }
    }
}
