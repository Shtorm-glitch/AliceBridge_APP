import static org.junit.Assert.*;

import dev.alicebridge.station.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class RetailCaPlanTest {
    private Map<String, String> mini() {
        return Map.of(
                "UID",
                "0",
                "BUILDNAME",
                "bergamot",
                "VERSION",
                "0.6.1.4.123.20241111.factory-6",
                "CMDLINE",
                "rootfstype=ramfs ubi.mtd=system",
                "MAC",
                "aa:bb:cc:dd:ee:ff",
                "BOOT",
                "boot-a");
    }

    @Test
    public void selectedModelAndFactoryAreRequired() {
        RetailCaPlan.requireFactory("mini3", mini());
        for (String key : List.of("UID", "BUILDNAME", "VERSION", "CMDLINE", "MAC", "BOOT")) {
            Map<String, String> bad = new HashMap<>(mini());
            bad.put(key, "");
            try {
                RetailCaPlan.requireFactory("mini3", bad);
                fail(key);
            } catch (IllegalArgumentException expected) {
            }
        }
        try {
            RetailCaPlan.requireFactory("lite2", mini());
            fail("Wrong model");
        } catch (IllegalArgumentException expected) {
        }
        var refreshed = new HashMap<>(mini());
        refreshed.put("VERSION", "0.369.1.50.4794652521.20261021.117");
        try {
            RetailCaPlan.requireFactory("mini3", refreshed);
            fail("Updated recovery");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void trustBundleIsPinnedAndMini3RetainsOriginalBytes() throws Exception {
        byte[] bundle = Files.readAllBytes(Path.of("src/main/assets/retail-ca/lite2-bundle.pem"));
        byte[] aaa = Files.readAllBytes(Path.of("src/main/assets/retail-ca/aaa-root.pem"));
        assertEquals(
                RetailCaPlan.LITE2_HASH,
                RetailCaPlan.sha(RetailCaPlan.patched("lite2", bundle, bundle, aaa)));
        String full = new String(bundle, StandardCharsets.UTF_8),
                root = new String(aaa, StandardCharsets.UTF_8);
        assertTrue(full.contains(root.trim()));
        byte[] original =
                full.replace(root.trim(), "")
                        .stripTrailing()
                        .concat("\n")
                        .getBytes(StandardCharsets.UTF_8);
        byte[] next = RetailCaPlan.patched("mini3", original, bundle, aaa);
        assertArrayEquals(original, Arrays.copyOf(next, original.length));
        assertEquals(original.length + aaa.length, next.length);
        assertArrayEquals(next, RetailCaPlan.patched("mini3", next, bundle, aaa));
        byte[] wrong = aaa.clone();
        wrong[200] ^= 1;
        try {
            RetailCaPlan.patched("mini3", original, bundle, wrong);
            fail("Changed root");
        } catch (Exception expected) {
        }
    }

    @Test
    public void scriptsNeverErasePartitionsAndMountUsesDetectedLayout() throws Exception {
        String mount = RetailCaPlan.mountScript(),
                write = RetailCaPlan.writeScript(RetailCaPlan.LITE2_HASH, true),
                back = RetailCaPlan.returnScript();
        assertFalse(mount.contains("-m 3"));
        assertTrue(mount.contains("mtd_num"));
        assertTrue(mount.contains("cleanup_failed_mount"));
        for (String forbidden : List.of("ubiformat", "flash_erase", "dd if=", "/dev/mtdblock"))
            assertFalse(forbidden, (mount + write + back).contains(forbidden));
        assertTrue(write.contains("$target.orig"));
        assertTrue(write.contains("activate_adb_quasmodrom"));
        assertTrue(write.contains("update_config=0"));
        assertTrue(back.contains("boot_to_recovery 0"));
        Path output = Path.of("build/retail-ca-tests");
        Files.createDirectories(output);
        Files.write(output.resolve("mount.sh"), mount.getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("write.sh"), write.getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("return.sh"), back.getBytes(StandardCharsets.UTF_8));
    }
}
