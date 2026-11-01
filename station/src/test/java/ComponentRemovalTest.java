import static org.junit.Assert.*;

import dev.alicebridge.station.ComponentRemoval;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class ComponentRemovalTest {
    private static final Map<String, String> LINUX = Map.of("ARCH", "aarch64");

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("ab-removal-").toRealPath();

        Fixture() throws Exception {
            write("data/quasar/.keep", "keep");
            write("proc/mounts", "root / rootfs rw 0 0\n");
        }

        Path path(String p) {
            return root.resolve(p);
        }

        void write(String p, String s) throws Exception {
            Files.createDirectories(path(p).getParent());
            Files.write(path(p), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        String read(String p) throws Exception {
            return new String(Files.readAllBytes(path(p)), java.nio.charset.StandardCharsets.UTF_8);
        }

        String run(String kind, String prefix) throws Exception {
            String s = ComponentRemoval.script(kind, LINUX);
            // Rewrite every absolute station filesystem prefix into the isolated fixture.
            s =
                    s.replaceAll(
                            "(?<![a-zA-Z0-9_/])/(data|etc|usr|opt|proc)(?=/|[^a-zA-Z0-9_.-]|$)",
                            java.util.regex.Matcher.quoteReplacement(root.toString()) + "/$1");
            Process proc =
                    new ProcessBuilder(
                                    "sh",
                                    "-c",
                                    "id(){ echo 0; }; mount(){ :; }; " + prefix + "\n" + s)
                            .redirectErrorStream(true)
                            .start();
            String out = new String(proc.getInputStream().readAllBytes());
            assertTrue(proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            return proc.exitValue() + ":" + out;
        }

        public void close() throws Exception {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    @Test
    public void allowlistAndModelGuards() throws Exception {
        for (String bad : List.of("", "../yam", "yam;reboot", "fastfetch"))
            try {
                ComponentRemoval.script(bad, LINUX);
                fail();
            } catch (IllegalArgumentException expected) {
            }
        try {
            ComponentRemoval.script("ssh", Map.of("SDK", "30", "PLATFORM", "unknown"));
            fail();
        } catch (java.io.IOException expected) {
        }
    }

    @Test
    public void apkgRequiresVerifiedEmptyInventory() throws Exception {
        for (String output : List.of("", "installed-package", "unrecognized header"))
            try (var f = new Fixture()) {
                f.write("usr/local/bin/apkg", "#!/bin/sh\nprintf '%s' '" + output + "'\n");
                f.path("usr/local/bin/apkg").toFile().setExecutable(true);
                String r = f.run("apkg", "");
                assertEquals(r, output.isEmpty(), r.startsWith("0:"));
                assertEquals(!output.isEmpty(), Files.exists(f.path("usr/local/bin/apkg")));
            }
    }

    @Test
    public void allScriptsHaveValidSyntax() throws Exception {
        for (String k : ComponentRemoval.KINDS) {
            Process p = new ProcessBuilder("sh", "-n").redirectErrorStream(true).start();
            p.getOutputStream().write(ComponentRemoval.script(k, LINUX).getBytes());
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes());
            assertEquals(out, 0, p.waitFor());
        }
    }

    @Test
    public void yseRemovalDeletesOwnedEntwarePreservesSharedHomeAndIsIdempotent() throws Exception {
        try (var f = new Fixture()) {
            f.write("data/opt/bin/yse", "cli");
            f.write("data/opt/home/.zsh_history", "private");
            f.write("data/opt/entware/bin/opkg", "manager");
            f.write("data/opt/entware/lib/opkg/status", "Package: opkg\n");
            Files.createSymbolicLink(f.path("opt"), f.path("data/opt/entware"));
            String first = f.run("yse", "");
            assertTrue(first, first.startsWith("0:"));
            assertFalse(Files.exists(f.path("data/opt/bin/yse")));
            assertEquals("private", f.read("data/opt/home/.zsh_history"));
            assertFalse(Files.exists(f.path("data/opt/entware")));
            String second = f.run("yse", "");
            assertTrue(second, second.startsWith("0:"));
            assertTrue(second.contains("AB_REMOVED=yse"));
        }
    }

    @Test
    public void dependenciesAndRunningJobsBlockBeforeDeletion() throws Exception {
        for (String block :
                List.of(
                        "data/opt/yap/test/.yse",
                        "data/quasar/alicebridge-zsh/configured",
                        "data/quasar/alicebridge-yse/lock/pid"))
            try (var f = new Fixture()) {
                f.write("data/opt/bin/yse", "cli");
                f.write(block, "installed");
                assertFalse(f.run("yse", "").startsWith("0:"));
                assertEquals("cli", f.read("data/opt/bin/yse"));
            }
    }

    @Test
    public void rejectsNonRootAndSymlinkedParents() throws Exception {
        try (var f = new Fixture()) {
            f.write("data/opt/bin/yse", "cli");
            assertFalse(f.run("yse", "id(){ echo 2000; };").startsWith("0:"));
            assertEquals("cli", f.read("data/opt/bin/yse"));
            Files.move(f.path("data/opt/bin"), f.path("data/opt/other"));
            Files.createSymbolicLink(f.path("data/opt/bin"), f.path("data/opt/other"));
            assertFalse(f.run("yse", "").startsWith("0:"));
            assertEquals("cli", f.read("data/opt/other/yse"));
        }
    }

    @Test
    public void yamPreservesUnrelatedBootHooksAndData() throws Exception {
        try (var f = new Fixture()) {
            f.write("data/quazar/yam/bin/yamd", "binary");
            f.write("data/quazar/yam/start.sh", "start");
            f.write("data/quazar/yam/data/settings.json", "private");
            f.write("data/quazar/yam/alicebridge-release", "1.0.0");
            // Fixture adapts script literals; its hook fixture must use the same paths.
            String unrelated = "# OTA protection\nkeep-ota\n# user service\nuser-start\n";
            f.write(
                    "etc/rc.local",
                    unrelated
                            + "# AliceBridge YAM\n[ ! -x "
                            + f.root
                            + "/data/quazar/yam/start.sh ] || sh "
                            + f.root
                            + "/data/quazar/yam/start.sh\nexit 0\n");
            String r = f.run("yam", "");
            assertTrue(r, r.startsWith("0:"));
            assertEquals(unrelated + "exit 0\n", f.read("etc/rc.local"));
            assertEquals("private", f.read("data/quazar/yam/data/settings.json"));
        }
    }

    @Test
    public void unknownYamInstallationIsNotDeleted() throws Exception {
        try (var f = new Fixture()) {
            f.write("data/quazar/yam/bin/yamd", "external-binary");
            String result = f.run("yam", "");
            assertFalse(result, result.startsWith("0:"));
            assertEquals("external-binary", f.read("data/quazar/yam/bin/yamd"));
        }
    }

    @Test
    public void zshRestoresShellBeforePackageRemovalAndKeepsPassword() throws Exception {
        try (var f = new Fixture()) {
            f.write(
                    "etc/passwd",
                    "root:current-password:0:0:root:/data/opt/home:/usr/bin/alicebridge-login\nother:x:12:12::/:/bin/sh\n"
                            .replace("/data", f.root + "/data")
                            .replace("/usr", f.root + "/usr"));
            f.write("usr/bin/alicebridge-login", "login");
            f.write("data/opt/bin/alicebridge-login", "login");
            f.write("data/quasar/alicebridge-zsh/configured", "yes");
            f.write("opt/bin/zsh", "zsh");
            f.path("opt/bin/zsh").toFile().setExecutable(true);
            f.write(
                    "opt/bin/opkg",
                    "#!/bin/sh\n[ \"$1\" != --noaction ] || exit 0\n! grep -q alicebridge-login '"
                            + f.path("etc/passwd")
                            + "' || exit 9\nrm '"
                            + f.path("opt/bin/zsh")
                            + "'\n");
            f.path("opt/bin/opkg").toFile().setExecutable(true);
            String r = f.run("zsh", "");
            assertTrue(r, r.startsWith("0:"));
            assertTrue(
                    f.read("etc/passwd")
                            .contains(
                                    "root:current-password:0:0:root:"
                                            + f.root
                                            + "/data/opt/home:/bin/sh"));
            assertTrue(f.read("etc/passwd").contains("other:x:12:12::/:/bin/sh"));
            assertFalse(Files.exists(f.path("data/quasar/alicebridge-zsh/configured")));
        }
    }

    @Test
    public void yamPluginsBlockAndSshNeverRestoresPasswords() throws Exception {
        try (var f = new Fixture()) {
            f.write("data/quazar/yam/bin/yamd", "binary");
            f.write("data/quazar/yam/data/plugins/test/plugin.toml", "plugin");
            assertFalse(f.run("yam", "").startsWith("0:"));
            assertEquals("binary", f.read("data/quazar/yam/bin/yamd"));
            f.write("etc/passwd", "root:changed:0:0::/home:/bin/sh\nother:x:12:12::/:/bin/sh\n");
            f.write("etc/shadow", "private-password");
            f.write("data/quasar/alicebridge-ssh/dropbear", "binary");
            f.write("data/quasar/alicebridge-ssh/host_ed25519", "key");
            String passwd = f.read("etc/passwd");
            String r = f.run("ssh", "");
            assertTrue(r, r.startsWith("0:"));
            assertEquals(passwd, f.read("etc/passwd"));
            assertEquals("private-password", f.read("etc/shadow"));
            assertEquals("key", f.read("data/quasar/alicebridge-ssh/host_ed25519"));
        }
    }
}
