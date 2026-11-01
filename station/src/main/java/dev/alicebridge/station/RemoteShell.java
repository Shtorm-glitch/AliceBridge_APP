package dev.alicebridge.station;

import dev.alicebridge.bridge.*;

/** Legacy ADB can return 0 for a failed remote command; require our explicit completion marker. */
public final class RemoteShell {
    public record Result(int code, String output) {}

    public static String wrap(String script, String marker) {
        return "printf '\\n"
                + marker
                + "BEGIN\\n'\n(\n"
                + script
                + "\n)\nab_remote_rc=$?\nprintf '\\n"
                + marker
                + "%s\\n' \"$ab_remote_rc\"\n";
    }

    public static Result parse(String output, String marker) {
        int offset = output.lastIndexOf(marker);
        if (offset < 0) return new Result(-1, output + "\nНет подтверждения завершения команды");
        try {
            String body = output.substring(0, offset);
            String begin = marker + "BEGIN";
            int start = body.lastIndexOf(begin);
            if (start >= 0) body = body.substring(start + begin.length());
            return new Result(
                    Integer.parseInt(output.substring(offset + marker.length()).trim()),
                    body.trim());
        } catch (NumberFormatException error) {
            return new Result(-1, output);
        }
    }
}
