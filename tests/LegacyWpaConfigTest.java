import dev.alice.bridge.LegacyWpaConfig;

public final class LegacyWpaConfigTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        String original="interface=wlan1\nssid2=416c696365427269646765\nwpa_passphrase=a=b # password\nwpa=2\nwpa=2\nrsn_pairwise=CCMP\n# wpa=2\nieee80211w=1\nchannel=6\n";
        String patched=LegacyWpaConfig.mixed(original);
        check(patched.contains("wpa_passphrase=a=b # password\n"),"Preserve passphrase exactly");
        check(patched.contains("ssid2=416c696365427269646765\n"),"Preserve SSID encoding");
        check(patched.contains("# wpa=2\n")&&patched.contains("channel=6\n"),"Preserve unrelated settings");
        check(patched.lines().filter(l->l.startsWith("wpa=")).count()==1,"Remove duplicate security settings");
        check(LegacyWpaConfig.value(patched,"wpa").equals("3"),"Both WPA protocols enabled");
        check(LegacyWpaConfig.value(patched,"ieee80211w").equals("0"),"Do not require PMF for legacy clients");
        check(LegacyWpaConfig.isMixed("wpa=3\nwpa_pairwise_cipher=TKIP CCMP\nrsn_pairwise_cipher=CCMP\ngroup_cipher=CCMP\n"),"Accept confirmed mixed mode");
        check(!LegacyWpaConfig.isMixed("wpa=2\nwpa_pairwise_cipher=TKIP CCMP\nrsn_pairwise_cipher=CCMP\ngroup_cipher=CCMP\n"),"Never report plain WPA2 as mixed");
        check(!LegacyWpaConfig.isMixed("wpa=3\nwpa_pairwise_cipher=CCMP\nrsn_pairwise_cipher=TKIP\n"),"Require requested cipher support");
        check(!LegacyWpaConfig.isMixed("wpa=3\nwpa_pairwise_cipher=TKIP CCMP\nrsn_pairwise_cipher=CCMP\ngroup_cipher=TKIP\n"),"Reject stale TKIP group cipher despite mixed flags");
        System.out.println("PASS: legacy WPA configuration and verification");
    }
}
