package dev.alicebridge.station;

import dev.alicebridge.bridge.*;
import java.util.*;

/** Conservative classification: only transport failures are eligible for automatic retry. */
public final class SetupRecovery {
    public static boolean transport(String message) {
        String s = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return s.contains("время ожидания")
                || s.contains("timed out")
                || s.contains("timeout")
                || s.contains("no route to host")
                || s.contains("device offline")
                || s.contains("connection reset")
                || s.contains("нет подключения adb")
                || s.contains("error: closed");
    }

    public static boolean interrupted(List<StationSetup.Step> steps) {
        return steps.stream().anyMatch(s -> s.status().equals("Ошибка") && transport(s.output()));
    }
}
