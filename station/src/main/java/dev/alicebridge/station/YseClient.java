package dev.alicebridge.station;

import android.content.Context;
import dev.alicebridge.bridge.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Calls the station's YSE CLI over app-owned ADB; long jobs survive UI reconnection. */
public final class YseClient {
    private final StationState scopeState;

    public YseClient(StationState state) {
        this.scopeState = state;
    }

    private static final String BIN = "/data/opt/bin/yse", JOBS = "/data/quasar/alicebridge-yse";
    private String checkedCli = "";

    public void inspect(NativeAdb adb) throws IOException, InterruptedException {
        NativeAdb.Result state =
                adb.shell(
                        "test -x "
                                + BIN
                                + " && test -x /opt/bin/curl && test -x /opt/bin/opkg &&"
                                + " /opt/bin/opkg list-installed | grep -q '^libpthread '");
        scopeState.yseReady = state.code() == 0;
        scopeState.yseStatus =
                scopeState.yseReady
                        ? "YSE и Entware установлены"
                        : "YSE и Entware не установлены · установка по кнопке";
    }

    public void ensure(Context context, NativeAdb adb) throws Exception {
        scopeState.yseStatus = "Устанавливаем YSE и Entware";
        scopeState.yseReady = false;
        if (Station1Plan.matches(scopeState.stationInfo)) {
            try (InputStream in = context.getAssets().open("scripts/station1-environment.sh")) {
                require(
                        adb.shell(
                                new String(
                                        Streams.readBounded(in, 32768), StandardCharsets.UTF_8)));
            }
        }
        File file = new File(context.getCacheDir(), "yse");
        try (InputStream in = context.getAssets().open("yse/yse");
                OutputStream out = new FileOutputStream(file)) {
            out.write(Streams.readBounded(in, 131072));
        }
        require(
                adb.shell(
                        """
                        set -eu
                        mkdir -p /data/opt/bin /data/quasar/alicebridge-yse
                        """));
        require(adb.push(file, BIN + ".new"));
        require(adb.shell("chmod 755 " + BIN + ".new && mv " + BIN + ".new " + BIN));
        String offline = scopeState.entware.prepare(context, adb);
        execute(context, adb, "bootstrap", null, offline);
        require(
                adb.shell(
                        "set -e\n"
                                + "/opt/bin/opkg list-installed | grep -q '^libpthread '\n"
                                + "[ -x /opt/bin/curl ]\n"
                                + BIN
                                + " version"));
        scopeState.yseReady = true;
        scopeState.yseStatus = "Entware готов · YSE";
        scopeState.log(context, "YSE", adb.target(), "YSE установлен; Entware проверен", "Готово");
    }

    String cleanOtaCache(Context context, NativeAdb adb) throws Exception {
        NativeAdb.Result result = adb.shell(OtaCacheCleanup.script());
        require(result);
        var info = StationSetup.parseInfo(result.output());
        String count = info.getOrDefault("REMOVED", "0"), free = info.getOrDefault("FREE_KIB", "?");
        String message =
                (count.equals("0")
                                ? "Скачанные образы OTA не найдены."
                                : "Удалено образов OTA: " + count + ".")
                        + " Свободно в /data: "
                        + free
                        + " КБ.";
        scopeState.log(context, "МЕСТО", "OTA-кэш", message, "Готово");
        return message;
    }

    public String command(Context context, String verb, String name) throws Exception {
        if (!scopeState.adbConnected || !scopeState.yseReady)
            throw new IOException("Дождитесь подключения YSE");
        NativeAdb adb = new NativeAdb(context);
        NativeAdb.Check check = adb.check(scopeState.station);
        if (!check.connected()) throw new IOException(check.status());
        refreshCli(context, adb);
        return execute(context, adb, verb, name);
    }

    /**
     * Upgrade our CLI fixes for existing installations without reinstalling Entware or packages.
     */
    private synchronized void refreshCli(Context context, NativeAdb adb) throws Exception {
        byte[] bytes;
        try (InputStream in = context.getAssets().open("yse/yse")) {
            bytes = Streams.readBounded(in, 131072);
        }
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sum = new StringBuilder();
        for (byte value : digest) sum.append(String.format(Locale.ROOT, "%02x", value & 255));
        String key =
                adb.target() + ":" + scopeState.stationInfo.getOrDefault("BOOT", "") + ":" + sum;
        if (key.equals(checkedCli)) return;
        NativeAdb.Result same =
                adb.shell(
                        "test -x "
                                + BIN
                                + " && [ \"$(sha256sum "
                                + BIN
                                + " | awk '{print $1}')\" = "
                                + sum
                                + " ]");
        if (same.code() != 0) {
            File file = File.createTempFile("yse-cli-", ".sh", context.getCacheDir());
            try {
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
                require(adb.push(file, BIN + ".new"));
                require(
                        adb.shell(
                                "set -e\n[ \"$(sha256sum "
                                        + BIN
                                        + ".new | awk '{print $1}')\" = "
                                        + sum
                                        + " ]\nchmod 755 "
                                        + BIN
                                        + ".new\nmv "
                                        + BIN
                                        + ".new "
                                        + BIN));
            } finally {
                file.delete();
            }
            scopeState.log(
                    context,
                    "YSE",
                    adb.target(),
                    "Обновлён встроенный установщик YAP; пакеты и Entware сохранены.",
                    "Исправления установщика применены");
        }
        checkedCli = key;
    }

    void installZsh(Context context, NativeAdb adb) throws Exception {
        if (!scopeState.yseReady)
            throw new IOException("Сначала установите YSE и Entware отдельной кнопкой");
        scopeState.zshStatus = "Проверяем место для Zsh";
        scopeState.zshStatus = "Передаём Zsh из APK";
        EntwareBootstrap.Payload payload = scopeState.entware.stage(context, adb, "zsh");
        scopeState.zshStatus = "Устанавливаем Zsh и настраиваем тему adam1";
        execute(
                context,
                adb,
                "zsh",
                null,
                "sh " + payload.base() + "/install.sh " + payload.base() + " " + payload.arch());
        inspectZsh(adb);
        if (!scopeState.zshReady) throw new IOException("Не удалось подтвердить оболочку Zsh");
        scopeState.zshRevision++;
    }

    void inspectZsh(NativeAdb adb) throws IOException, InterruptedException {
        NativeAdb.Result state =
                adb.shell(
                        """
test -x /opt/bin/zsh && test -x /data/opt/bin/alicebridge-login && test -f /data/quasar/alicebridge-zsh/configured || exit 1
ab_shell=$(awk -F: '$1=="root"{print $7}' /etc/passwd)
case "$ab_shell" in /usr/bin/alicebridge-login|/system/bin/alicebridge-login|/data/opt/bin/alicebridge-login) test -x "$ab_shell";; *) exit 1;; esac
""");
        scopeState.zshReady = state.code() == 0;
        if (scopeState.zshReady) {
            NativeAdb.Result version =
                    adb.shell(
                            "test -f /data/quasar/alicebridge-zsh/environment-version && test"
                                + " \"$(cat /data/quasar/alicebridge-zsh/environment-version)\" ="
                                + " 2");
            if (version.code() != 0) {
                try (InputStream in =
                        scopeState.context.getAssets().open("scripts/repair-zsh-environment.sh")) {
                    require(
                            adb.shell(
                                    new String(
                                            Streams.readBounded(in, 32768),
                                            StandardCharsets.UTF_8)));
                    scopeState.zshRevision++;
                    scopeState.log(
                            scopeState.context,
                            "ZSH",
                            "Окружение",
                            "Восстановлены PATH и POSIX-обработка служебных команд; настройки Zsh и"
                                    + " пакеты сохранены.",
                            "Исправлено");
                }
            }
        }
        scopeState.zshStatus =
                scopeState.zshReady
                        ? "Zsh готов · тема adam1 · оболочка по умолчанию"
                        : "Zsh · установка по кнопке";
    }

    private String execute(Context context, NativeAdb adb, String verb, String name)
            throws Exception {
        return execute(context, adb, verb, name, "");
    }

    private String execute(Context context, NativeAdb adb, String verb, String name, String offline)
            throws Exception {
        if (Set.of("bootstrap", "update", "catalog", "install", "info", "ent-update", "ent-catalog")
                .contains(verb)) scopeState.stationDns.ensure(context, adb);
        if (!Set.of(
                        "bootstrap",
                        "update",
                        "list",
                        "catalog",
                        "info",
                        "install",
                        "remove",
                        "zsh",
                        "ent-update",
                        "ent-catalog",
                        "ent-list")
                .contains(verb)) throw new IOException("Неизвестная команда YSE");
        if (Set.of("info", "install", "remove").contains(verb)
                && (name == null
                        || !name.matches("(?:(?:ent|yap):)?[a-zA-Z0-9][a-zA-Z0-9+_.-]{0,100}")
                        || name.contains(".."))) throw new IOException("Недопустимое имя пакета");
        String id = UUID.randomUUID().toString().replace("-", ""), base = JOBS + "/" + id;
        String command =
                BIN
                        + " "
                        + (verb.equals("catalog") ? "list -a" : verb)
                        + (name == null ? "" : " " + RootShell.quote(name));
        if (verb.equals("ent-update")) command = "/opt/bin/opkg update";
        if (verb.equals("ent-catalog")) command = "/opt/bin/opkg list";
        if (verb.equals("ent-list")) command = "/opt/bin/opkg list-installed";
        if (verb.equals("bootstrap")) command = offline + "YSE_OFFLINE=1 " + command;
        if (verb.equals("zsh")) command = offline;
        String job =
                "#!/bin/sh\nexec > "
                        + base
                        + ".log 2>&1\nexport HOME=/data/opt/home\nmkdir -p \"$HOME\"\n"
                        + "if ! mkdir "
                        + JOBS
                        + "/lock 2>/dev/null; then echo 'Другая операция YSE ещё выполняется'; echo"
                        + " 75 > "
                        + base
                        + ".exit; exit; fi\n"
                        + "printf '%s\n' \"$$\" > "
                        + JOBS
                        + "/lock/pid\ncat /proc/sys/kernel/random/boot_id > "
                        + JOBS
                        + "/lock/boot\n"
                        + "trap 'rm -f "
                        + JOBS
                        + "/lock/pid "
                        + JOBS
                        + "/lock/boot; rmdir "
                        + JOBS
                        + "/lock' EXIT\n"
                        + command
                        + "\nrc=$?\nprintf '%s\n' \"$rc\" > "
                        + base
                        + ".exit.new\nmv "
                        + base
                        + ".exit.new "
                        + base
                        + ".exit\n";
        require(
                adb.shell(
                        "mkdir -p "
                                + JOBS
                                + "\ncat > "
                                + base
                                + ".sh <<'AB_YSE_JOB'\n"
                                + job
                                + "AB_YSE_JOB\n"
                                + "if command -v start-stop-daemon >/dev/null 2>&1; then\n"
                                + "start-stop-daemon --start --background --make-pidfile --pidfile "
                                + base
                                + ".pid --startas /bin/sh -- "
                                + base
                                + ".sh\n"
                                + "elif command -v setsid >/dev/null 2>&1; then\n"
                                + "setsid /bin/sh "
                                + base
                                + ".sh < /dev/null > /dev/null 2>&1 &\necho $! > "
                                + base
                                + ".pid\n"
                                + "else echo 'Нет start-stop-daemon или setsid для фоновой задачи';"
                                + " exit 1\n"
                                + "fi\n"));
        long deadline = System.currentTimeMillis() + 15 * 60 * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            NativeAdb.Result state =
                    adb.shell(
                            "if [ -f "
                                    + base
                                    + ".exit ]; then cat "
                                    + base
                                    + ".exit; else echo RUNNING; fi\ntail -c 900 "
                                    + base
                                    + ".log 2>/dev/null || true");
            require(state);
            YseJob.State task = YseJob.parse(state.output());
            scopeState.yseOperation = task.log().isEmpty() ? "Выполняем команду YSE…" : task.log();
            if (!task.running()) {
                File output = File.createTempFile("yse-result-", ".log", context.getCacheDir());
                String text;
                try {
                    require(adb.pull(base + ".log", output));
                    try (InputStream in = new FileInputStream(output)) {
                        text =
                                new String(
                                        Streams.readBounded(in, 4 * 1024 * 1024),
                                        StandardCharsets.UTF_8);
                    }
                } finally {
                    output.delete();
                }
                if (task.code() != 0
                        || Set.of("update", "catalog").contains(verb)
                                && text.contains("[yse] ошибка:"))
                    throw new IOException(
                            text.isBlank() ? "YSE завершился с ошибкой " + task.code() : text);
                scopeState.log(
                        context,
                        "YSE",
                        verb + (name == null ? "" : " " + name),
                        text.length() > 5000 ? text.substring(text.length() - 5000) : text,
                        "Выполнено");
                return text;
            }
            Thread.sleep(700);
        }
        throw new IOException(
                "Операция YSE ещё выполняется на колонке. Проверьте журнал перед повтором.");
    }

    private void require(NativeAdb.Result r) throws IOException {
        if (r.code() != 0) throw new IOException(r.output());
    }
}
