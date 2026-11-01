package dev.alicebridge.station;

import android.content.Context;
import dev.alicebridge.bridge.*;
import java.io.*;

/** User-requested apkg installation from initialfox/yandex-station-repo; never automatic. */
public final class ApkgClient {
    private final StationState scopeState;

    public ApkgClient(StationState state) {
        this.scopeState = state;
    }

    void install(Context context, NativeAdb adb) throws Exception {
        scopeState.apkgStatus = "Передаём apkg из APK…";
        File local = File.createTempFile("apkg-", ".bin", context.getCacheDir());
        try {
            try (InputStream in = context.getAssets().open("packages/apkg");
                    OutputStream out = new FileOutputStream(local)) {
                out.write(Streams.readBounded(in, 16 * 1024 * 1024));
            }
            NativeAdb.Result push = adb.push(local, "/data/local/tmp/alicebridge-apkg");
            require(push);
            boolean android = !scopeState.stationInfo.getOrDefault("SDK", "").isEmpty();
            String base = android ? "/data/local/bin" : "/usr/local/bin";
            String script =
                    "set -e\n[ \"$(id -u)\" = 0 ] || { echo 'Нужен root колонки'; exit 1; }\n"
                            + (android
                                    ? ""
                                    : """
ab_ro=0
case ",$(awk '$2=="/"{print $4;exit}' /proc/mounts)," in *,ro,*) ab_ro=1; mount -o remount,rw /;; esac
trap '[ "$ab_ro" != 1 ] || mount -o remount,ro /' EXIT
""")
                            + "mkdir -p "
                            + base
                            + "\ncp /data/local/tmp/alicebridge-apkg "
                            + base
                            + "/apkg\nchmod 755 "
                            + base
                            + "/apkg\n"
                            + "rm -f /data/local/tmp/alicebridge-apkg\n"
                            + "export ANDROID_DATA=/data ANDROID_ROOT=/system\n"
                            + base
                            + "/apkg paths";
            NativeAdb.Result result = MaxCommands.shell(context, adb, script);
            require(result);
            scopeState.apkgStatus =
                    "apkg установлен · " + base + "/apkg. Первый запуск: apkg update";
            scopeState.log(
                    context,
                    "APKG",
                    "initialfox/yandex-station-repo",
                    result.output(),
                    "Установлено");
        } finally {
            local.delete();
        }
    }

    private void require(NativeAdb.Result r) throws IOException {
        if (r.code() != 0) throw new IOException(r.output());
    }
}
