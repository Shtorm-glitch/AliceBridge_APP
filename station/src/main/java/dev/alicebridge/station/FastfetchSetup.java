package dev.alicebridge.station;

import android.content.Context;
import dev.alicebridge.bridge.*;
import java.io.*;
import java.util.*;

/** Optional automatic package stage, separate from mandatory root/OTA setup. */
public final class FastfetchSetup {
    private final StationState scopeState;

    public FastfetchSetup(StationState state) {
        this.scopeState = state;
    }

    private static final String ID = "fastfetch";

    void set(String status, String output) {
        List<StationSetup.Step> steps = new ArrayList<>();
        for (StationSetup.Step s : scopeState.setupSteps) if (!s.id().equals(ID)) steps.add(s);
        steps.add(new StationSetup.Step(ID, "Fastfetch", status, output));
        scopeState.setupSteps = List.copyOf(steps);
    }

    boolean done(Context c, String key) {
        return c.getSharedPreferences("fastfetch", 0).contains(key);
    }

    String key() {
        return DeviceNames.identifier(scopeState.stationInfo)
                + ":"
                + scopeState.stationInfo.getOrDefault("MAC", scopeState.station);
    }

    void skip(Context c) {
        c.getSharedPreferences("fastfetch", 0).edit().putBoolean(key(), false).apply();
        set("Пропущено", "Установку fastfetch пропустили. Настройка колонки продолжится.");
    }

    void refreshAppearance(Context context, NativeAdb adb) throws InterruptedException {
        String key = key();
        android.content.SharedPreferences prefs = context.getSharedPreferences("fastfetch", 0);
        if (!prefs.getBoolean(key, false) || prefs.getBoolean(key + ":appearance-v1", false))
            return;
        try {
            boolean android = !scopeState.stationInfo.getOrDefault("SDK", "").isEmpty();
            String base = android ? "/data/local/bin" : "/usr/local/bin";
            require(MaxCommands.shell(context, adb, "test -x " + base + "/fastfetch"));
            FastfetchAppearance.apply(context, adb, android);
            prefs.edit().putBoolean(key + ":appearance-v1", true).apply();
            set("Готово", "Логотип Алисы и адаптивная ширина терминала настроены.");
            scopeState.log(
                    context,
                    "SETUP",
                    "Fastfetch",
                    "Обновлено только оформление установленного fastfetch; бинарник не"
                            + " переустанавливался.",
                    "Готово");
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            set("Ошибка", e.getMessage());
            scopeState.log(
                    context, "SETUP", "Fastfetch", e.toString(), "Не удалось обновить оформление");
        }
    }

    void ensure(Context context, NativeAdb adb) throws InterruptedException {
        String arch = scopeState.stationInfo.getOrDefault("ARCH", "");
        if (!arch.isEmpty() && !arch.equals("aarch64") && !arch.equals("arm64")) {
            set(
                    "Ошибка",
                    "Встроенный Fastfetch рассчитан на ARM64. На этой модели шаг можно пропустить;"
                            + " установите подходящую версию через Entware.");
            return;
        }
        String key = key();
        if (done(context, key)) {
            if (!context.getSharedPreferences("fastfetch", 0).getBoolean(key, false)) {
                set("Пропущено", "");
                return;
            }
            if (context.getSharedPreferences("fastfetch", 0)
                    .getBoolean(key + ":appearance-v1", false)) {
                set("Готово", "");
                return;
            }
        }
        set(
                "Выполняется",
                "Передаём fastfetch из APK. Если установка не получится, этот шаг можно"
                        + " пропустить.");
        File file = null;
        try {
            boolean android = !scopeState.stationInfo.getOrDefault("SDK", "").isEmpty();
            String base = android ? "/data/local/bin" : "/usr/local/bin";
            NativeAdb.Result available =
                    MaxCommands.shell(
                            context,
                            adb,
                            "test -x " + base + "/fastfetch && " + base + "/fastfetch --version");
            if (available.code() != 0) {
                file = File.createTempFile("fastfetch-", ".bin", context.getCacheDir());
                try (InputStream in = context.getAssets().open("packages/fastfetch");
                        OutputStream out = new FileOutputStream(file)) {
                    out.write(Streams.readBounded(in, 8 * 1024 * 1024));
                }
                String staging = "/data/local/tmp/alicebridge-fastfetch";
                require(adb.push(file, staging));
                String install =
                        "set -e\n"
                                + (android
                                        ? ""
                                        : """
ab_ro=0
case ",$(awk '$2=="/"{print $4;exit}' /proc/mounts)," in *,ro,*) ab_ro=1; mount -o remount,rw /;; esac
trap '[ "$ab_ro" != 1 ] || mount -o remount,ro /' EXIT
""")
                                + "mkdir -p "
                                + base
                                + "\ncp "
                                + staging
                                + " "
                                + base
                                + "/fastfetch\nchmod 755 "
                                + base
                                + "/fastfetch\nrm -f "
                                + staging
                                + "\n"
                                + base
                                + "/fastfetch --version";
                require(MaxCommands.shell(context, adb, install));
            }
            FastfetchAppearance.apply(context, adb, android);
            context.getSharedPreferences("fastfetch", 0)
                    .edit()
                    .putBoolean(key + ":appearance-v1", true)
                    .putBoolean(key, true)
                    .apply();
            set(
                    "Готово",
                    "fastfetch установлен: логотип Алисы и адаптивный вывод по ширине терминала."
                            + " Запуск: fastfetch.");
            scopeState.log(
                    context,
                    "SETUP",
                    "Fastfetch",
                    "Пакет передан из APK и проверен запуском --version",
                    "Готово");
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            set("Ошибка", e.getMessage());
            scopeState.log(context, "SETUP", "Fastfetch", e.toString(), "Можно пропустить");
        } finally {
            if (file != null) file.delete();
        }
    }

    private void require(NativeAdb.Result r) throws IOException {
        if (r.code() != 0) throw new IOException(r.output());
    }
}
