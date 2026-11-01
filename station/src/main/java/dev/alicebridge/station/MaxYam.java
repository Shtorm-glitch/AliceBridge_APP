package dev.alicebridge.station;

import dev.alicebridge.bridge.*;

/** Android Max lacks Buildroot start-stop-daemon and rc.local. */
public final class MaxYam {
    static String start(String base) {
        if (!base.equals("/data/quazar/yam") && !base.equals("/data/yam"))
            throw new IllegalArgumentException("Неизвестный путь YAM Макс");
        String binary = base + (base.equals("/data/yam") ? "/yamd" : "/bin/yamd");
        return "set -eu\n[ \"$(id -u)\" = 0 ]\n[ \"$(getenforce)\" = Permissive ]\nbase='"
                + base
                + "'\nbinary='"
                + binary
                + "'\n"
                + "if [ -f \"$base/yamd.pid\" ]; then\n"
                + " p=$(cat \"$base/yamd.pid\");case \"$p\" in ''|*[!0-9]*) echo 'Некорректный PID"
                + " YAM';exit 1;;esac\n"
                + " exe=$(readlink \"/proc/$p/exe\" 2>/dev/null || true)\n"
                + " if [ \"$exe\" = \"$binary\" ]; then exit 0;fi\n"
                + " rm -f \"$base/yamd.pid\"\n"
                + "fi\n"
                + "mkdir -p \"$base/data\"\n"
                + "chmod 755 \"$binary\"\n"
                + "nohup \"$binary\" --data-dir \"$base/data\" --listen 127.0.0.1:8099 </dev/null"
                + " >\"$base/startup.log\" 2>&1 &\n"
                + "p=$!;echo \"$p\" > \"$base/yamd.pid\"\n"
                + "sleep 1\n"
                + "kill -0 \"$p\"\n";
    }
}
