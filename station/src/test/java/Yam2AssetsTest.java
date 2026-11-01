import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

/** The bundled YAM v2 installer must match the pinned upstream release and README setup. */
public class Yam2AssetsTest {
    private static Path assets() {
        Path local = Path.of("src/main/assets");
        return Files.isDirectory(local) ? local : Path.of("station/src/main/assets");
    }
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(assets().resolve(path)), StandardCharsets.UTF_8);
    }
    @Test public void installerFollowsYamV2Layout() throws Exception {
        String script = read("scripts/install-yam-v2.sh");
        for (String token : new String[]{"root=/data/yam", "$root/bin/yam-run", "$root/platforms",
                "registry add official https://repo.yse.wtf/v2", "ed25519:5Rz8F47uftWVU5kLgWYhprkEZ4rpP2kecUge/a7KQas=",
                "/etc/init.d/S99yam", "/usr/bin/yam", "registry list", "AB_YAM2=1"})
            assertTrue("missing " + token, script.contains(token));
        assertTrue(script.contains("rm -rf /data/quazar/yam"));
        assertFalse(script.contains("--listen 127.0.0.1:8099"));
    }
    @Test public void pinnedBinariesAndProfilesArePresent() throws Exception {
        JSONObject release = new JSONObject(read("yam/release-v2.json"));
        assertEquals("0.2.3", release.getString("version"));
        for (String asset : new String[]{"yam/yam-aarch64", "yam/yam-armv7"}) {
            Path path = assets().resolve(asset);
            assertTrue(path.toString(), Files.isRegularFile(path));
            String expected = release.getJSONObject("assets").getString(asset);
            assertEquals(asset, expected, sha256(path));
        }
        for (String platform : new String[]{"yandexmicro", "plum", "cucumber", "mango", "yandexstation", "yandexstation_2", "bergamot", "yandexmidi"})
            assertTrue(platform, Files.isRegularFile(assets().resolve("yam/platforms/" + platform + ".yaml")));
    }
    private static String sha256(Path path) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder out = new StringBuilder();
        for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
