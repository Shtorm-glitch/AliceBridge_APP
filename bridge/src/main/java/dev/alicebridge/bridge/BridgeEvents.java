package dev.alicebridge.bridge;

import android.content.Context;

/**
 * Transport callbacks only. The application service owns session state and subscription lifetime.
 */
public final class BridgeEvents {
    public interface Listener {
        void onLog(String kind, String source, String detail, String status);

        void onClient(String ip);
    }

    public static volatile Listener listener;

    public static void client(String ip) {
        Listener sink = listener;
        if (sink != null) sink.onClient(ip);
    }

    public static void log(
            Context unused, String kind, String source, String detail, String status) {
        Listener sink = listener;
        if (sink != null) sink.onLog(kind, source, detail, status);
    }
}
