import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Shipped shell helpers must stay syntactically valid and keep their boot hooks. */
public class ToolsAssetsTest {
    private static Path scripts() {
        Path local = Path.of("src/main/assets/scripts");
        return Files.isDirectory(local) ? local : Path.of("station/src/main/assets/scripts");
    }

    private static String read(Path root, String name) throws Exception {
        return new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
    }

    private static void syntax(Path file) throws Exception {
        Process process =
                new ProcessBuilder("sh", "-n", file.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new AssertionError("shell syntax " + file.getFileName() + ": " + output);
    }

    @org.junit.Test
    public void shippedScriptsStayValidAndAutostart() throws Exception {
        Path root = scripts();
        for (String name :
                List.of(
                        "install-ssh.sh",
                        "install-ssh-armv7.sh",
                        "install-ssh-station1.sh",
                        "yandex-noupdate.sh"))
            if (!Files.isRegularFile(root.resolve(name)))
                throw new AssertionError("missing asset " + name);
        for (String name :
                List.of(
                        "install-ssh.sh",
                        "install-ssh-armv7.sh",
                        "install-ssh-station1.sh",
                        "yandex-noupdate.sh")) syntax(root.resolve(name));
        for (String name : List.of("install-ssh.sh", "install-ssh-armv7.sh")) {
            String script = read(root, name);
            if (!script.contains("/etc/init.d/S99zalicebridge-ssh")
                    || !script.contains("# AliceBridge SSH")
                    || !script.contains("/etc/rc.local"))
                throw new AssertionError(name + " lost the SSH boot hook");
            if (!script.contains("S99zalicebridge-ssh start"))
                throw new AssertionError(name + " does not start SSH after boot");
        }
        String station1 = read(root, "install-ssh-station1.sh");
        if (!station1.contains("# AliceBridge SSH")
                || !station1.contains("/data/dropbear/start.sh")
                || !station1.contains("/system/etc/rc.local"))
            throw new AssertionError("Station 1 lost the rc.local SSH autostart");
        String noupdate = read(root, "yandex-noupdate.sh");
        for (String token :
                List.of(
                        "--updatesd",
                        "restore",
                        "status",
                        "device_ota_update.sh",
                        "ota_script.pub"))
            if (!noupdate.contains(token))
                throw new AssertionError("noupdate helper lost " + token);
        System.out.println("PASS: shipped scripts parse and keep SSH/OTA autostart hooks");
    }
}
