package dev.alicebridge.station;

import dev.alicebridge.bridge.*;
import java.util.*;

/** Validate package paths before constructing a trusted interactive shell command. */
public final class YamPaths {
    static String id(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,100}"))
            throw new IllegalArgumentException("Недопустимый ID YAM");
        return value;
    }

    static String relative(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.contains("\u0000"))
            throw new IllegalArgumentException("Недопустимый путь YAM");
        for (String p : value.split("/", -1))
            if (p.equals("..") || p.isEmpty())
                throw new IllegalArgumentException("Недопустимый путь YAM");
        return value;
    }

    static String q(String s) {
        if (s.contains("\u0000")) throw new IllegalArgumentException("NUL");
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static String terminal(
            String id,
            String root,
            String path,
            String interpreter,
            List<String> args,
            List<String> libs) {
        id(id);
        if (!root.equals("/data/quazar/yam/data/plugins/" + id)
                && !root.equals("/data/yam/data/plugins/" + id))
            throw new IllegalArgumentException("Неизвестная папка пакета");
        relative(path);
        if (!interpreter.isEmpty() && !interpreter.equals("sh"))
            throw new IllegalArgumentException("Неизвестный интерпретатор");
        List<String> library = new ArrayList<>();
        for (String lib : libs) library.add(root + "/" + relative(lib));
        if (library.isEmpty()) {
            int last = path.lastIndexOf('/');
            int parent = last < 0 ? -1 : path.lastIndexOf('/', last - 1);
            if (last >= 0)
                library.add(root + "/" + (parent < 0 ? "" : path.substring(0, parent + 1)) + "lib");
        }
        StringBuilder suffix = new StringBuilder();
        for (String arg : args) suffix.append(" ").append(q(arg));
        String setup =
                library.isEmpty()
                        ? ""
                        : "export LD_LIBRARY_PATH="
                                + q(String.join(":", library))
                                + "\"${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\" && ";
        String launch = setup + (interpreter.equals("sh") ? "sh " : "") + q("./" + path) + suffix;
        if (interpreter.isEmpty()) {
            String legacy = q("./" + id + "/" + id + "-run");
            launch =
                    "if [ -f "
                            + legacy
                            + " ]; then sh "
                            + legacy
                            + suffix
                            + "; else "
                            + launch
                            + "; fi";
        }
        return "cd " + q(root) + " && " + launch;
    }
}
