import static org.junit.Assert.*;

import dev.alicebridge.station.NoUpdatePlan;
import dev.alicebridge.station.StationSetup;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/** Execute the actual generated script against regular files, never real system mounts. */
public class NoUpdatePlanTest {
    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("alicebridge-noupdate-");
        final Path q = Files.createDirectory(root.resolve("quasar"));
        final Path bk = root.resolve("backup"),
                mounts = root.resolve("mounts"),
                log = root.resolve("mount-log");

        Fixture() throws Exception {
            Utf8.write(mounts, "rootfs / rootfs ro,relatime 0 0\n");
            Utf8.write(q.resolve("ota_script.pub"), "original-key\n");
            Utf8.write(q.resolve("device_ota_update.sh"), "#!/bin/sh\necho original-applier\n");
            Utf8.write(
                    q.resolve("launch.json"),
                    "{\"tasks\":[{\"command\":\"maind --mediad\"},{\"command\":\"maind"
                            + " --platformd\"},{\"command\":\"maind --updatesd\"}]}\n");
        }

        String adapt(String script) {
            return script.replace("/system/vendor/quasar", q.toString())
                    .replace("/data/opt/backup", bk.toString())
                    .replace("/proc/mounts", mounts.toString());
        }

        Result run(String script, String extra) throws Exception {
            String prefix =
                    "id(){ echo 0; }; getprop(){ :; }; sync(){ :; }; jq(){ return 1; }; mount(){"
                            + " printf '%s\\n"
                            + "' \"$*\" >> '"
                            + log
                            + "'; };\n";
            Process p =
                    new ProcessBuilder("sh", "-c", prefix + extra + "\n" + adapt(script))
                            .redirectErrorStream(true)
                            .start();
            String out =
                    new String(
                            p.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertTrue("fixture timeout", p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
            return new Result(p.exitValue(), out);
        }

        public void close() throws Exception {
            // All targets are created under the verified temporary fixture directory.
            assertTrue(
                    root.toAbsolutePath()
                            .getFileName()
                            .toString()
                            .startsWith("alicebridge-noupdate-"));
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private record Result(int code, String output) {}

    @Test
    public void appliesRecipeBacksUpOnceAndReadsBackBothProtections() throws Exception {
        try (var f = new Fixture()) {
            String originalJson = read(f.q.resolve("launch.json"));
            Result first = f.run(NoUpdatePlan.script(), "");
            assertEquals(first.output, 0, first.code);
            assertTrue(first.output.contains("AB_NOUPDATE_VERIFIED=1"));
            assertEquals("original-key\n", read(f.bk.resolve("ota_script.pub.orig")));
            assertTrue(
                    read(f.bk.resolve("device_ota_update.sh.orig")).contains("original-applier"));
            assertEquals(originalJson, read(f.q.resolve("launch.json")));
            assertEquals(originalJson, read(f.bk.resolve("launch.json.orig")));
            Result proof = f.run(NoUpdatePlan.inspect(), "");
            assertTrue(proof.output, proof.output.contains("AB_OTA=1"));
            Result second = f.run(NoUpdatePlan.script(), "");
            assertEquals(second.output, 0, second.code);
            assertEquals("original-key\n", read(f.bk.resolve("ota_script.pub.orig")));
            assertTrue(read(f.log).contains("remount,ro /"));
            Utf8.write(f.q.resolve("device_ota_update.sh"), "#!/bin/sh\necho drift\n");
            Result drift = f.run(NoUpdatePlan.inspect(), "");
            assertTrue(drift.output, drift.output.contains("AB_OTA=0"));
        }
    }

    @Test
    public void missingLayoutFailsBeforeAnySystemWriteOrRemount() throws Exception {
        try (var f = new Fixture()) {
            Files.delete(f.q.resolve("ota_script.pub"));
            String applier = read(f.q.resolve("device_ota_update.sh"));
            Result r = f.run(NoUpdatePlan.script(), "");
            assertNotEquals(r.output, 0, r.code);
            assertEquals(applier, read(f.q.resolve("device_ota_update.sh")));
            assertFalse(Files.exists(f.log));
        }
    }

    @Test
    public void rejectsSymlinkTargetAndSymlinkBackup() throws Exception {
        for (boolean backup : List.of(false, true))
            try (var f = new Fixture()) {
                Path outside = f.root.resolve("unrelated");
                Utf8.write(outside, "do-not-touch\n");
                Path target;
                if (backup) {
                    Files.createDirectories(f.bk);
                    target = f.bk.resolve("ota_script.pub.orig");
                } else {
                    target = f.q.resolve("ota_script.pub");
                    Files.delete(target);
                }
                Files.createSymbolicLink(target, outside);
                Result r = f.run(NoUpdatePlan.script(), "");
                assertNotEquals(r.output, 0, r.code);
                assertEquals("do-not-touch\n", read(outside));
                assertFalse(Files.exists(f.log));
            }
    }

    @Test
    public void writeFailureIsNotSuccessAndRestoresReadonlyMount() throws Exception {
        try (var f = new Fixture()) {
            Result r = f.run(NoUpdatePlan.script(), "chmod(){ return 1; };");
            assertNotEquals(r.output, 0, r.code);
            assertFalse(r.output.contains("AB_NOUPDATE_VERIFIED=1"));
            assertTrue(read(f.log).contains("remount,ro /"));
        }
    }

    @Test
    public void originallyWritableMountStaysWritable() throws Exception {
        try (var f = new Fixture()) {
            Utf8.write(f.mounts, "rootfs / rootfs rw,relatime 0 0\n");
            Result r = f.run(NoUpdatePlan.script(), "");
            assertEquals(r.output, 0, r.code);
            assertFalse(Files.exists(f.log));
        }
    }

    @Test
    public void androidRefusedBeforeBackupAndWrites() throws Exception {
        try (var f = new Fixture()) {
            Result r = f.run(NoUpdatePlan.script(), "getprop(){ echo 28; };");
            assertNotEquals(r.output, 0, r.code);
            assertFalse(Files.exists(f.bk));
            assertFalse(Files.exists(f.log));
            assertEquals("original-key\n", read(f.q.resolve("ota_script.pub")));
        }
    }

    @Test
    public void launchEditRequiresValidJsonAndKeepsOtherDaemons() throws Exception {
        for (boolean updaterFirst : List.of(true, false))
            try (var f = new Fixture()) {
                String updates = "{\n\"command\":\"maind --updatesd\"\n}";
                String media =
                        "{\n"
                                + "\"command\":\"maind --mediad\"\n"
                                + "},\n"
                                + "{\n"
                                + "\"command\":\"maind --platformd\"\n"
                                + "}";
                String original =
                        "{\n\"tasks\":[\n"
                                + (updaterFirst ? updates + ",\n" + media : media + ",\n" + updates)
                                + "\n]\n}\n";
                Utf8.write(f.q.resolve("launch.json"), original);
                Result r =
                        f.run(
                                NoUpdatePlan.script(),
                                "jq(){ python3 -c 'import json,sys;json.load(open(sys.argv[-1]))'"
                                        + " \"$@\"; };");
                assertEquals(r.output, 0, r.code);
                String after = read(f.q.resolve("launch.json"));
                assertTrue(after.contains("--mediad"));
                assertTrue(after.contains("--platformd"));
                if (updaterFirst) assertFalse(after.contains("--updatesd"));
                else
                    assertEquals(
                            "Invalid trailing-comma candidate must not replace launch.json",
                            original,
                            after);
                assertTrue(f.run(NoUpdatePlan.inspect(), "").output.contains("AB_OTA=1"));
            }
    }

    @Test
    public void publicKeyExactlyMatchesPinnedUpstreamRecipe() throws Exception {
        String upstream = read(Path.of("src/main/assets/scripts/yandex-noupdate.sh"));
        String key =
                upstream.substring(
                        upstream.indexOf("DUMMY_PUB='") + 11,
                        upstream.indexOf("'", upstream.indexOf("DUMMY_PUB='") + 11));
        assertTrue(NoUpdatePlan.script().contains(key));
        assertTrue(NoUpdatePlan.inspect().contains(key));
    }

    @Test
    public void mandatoryReadinessIgnoresOptionalFailuresButNotMissingProofs() {
        for (String optional : List.of("Ошибка", "Ожидание", "Пропущено", "Не применено")) {
            var steps =
                    List.of(
                            new StationSetup.Step("ota", "OTA", "Готово", ""),
                            new StationSetup.Step("adb", "ADB", "Готово", ""),
                            new StationSetup.Step("analytics", "Analytics", optional, ""),
                            new StationSetup.Step("fastfetch", "Fastfetch", optional, ""));
            assertTrue(optional, StationSetup.complete(steps));
        }
        for (String required : List.of("ota", "adb"))
            for (String status : List.of("Ошибка", "Ожидание", "Пропущено")) {
                var steps =
                        List.of(
                                new StationSetup.Step(
                                        "ota",
                                        "OTA",
                                        required.equals("ota") ? status : "Готово",
                                        ""),
                                new StationSetup.Step(
                                        "adb",
                                        "ADB",
                                        required.equals("adb") ? status : "Готово",
                                        ""));
                assertFalse(required + status, StationSetup.complete(steps));
            }
        assertFalse(StationSetup.complete(List.of()));
        assertFalse(
                StationSetup.complete(
                        List.of(
                                new StationSetup.Step("analytics", "Analytics", "Готово", ""),
                                new StationSetup.Step("fastfetch", "Fastfetch", "Готово", ""),
                                new StationSetup.Step("ssh", "SSH", "Готово", ""))));
        var proof = Map.of("OTA", "1", "ADB", "1", "ANALYTICS", "0");
        assertTrue(StationSetup.alreadyConfigured(proof));
        var inspected = StationSetup.inspectedSteps(proof);
        assertTrue(StationSetup.complete(inspected));
        assertEquals("Не применено", inspected.get(2).status());
    }
}
