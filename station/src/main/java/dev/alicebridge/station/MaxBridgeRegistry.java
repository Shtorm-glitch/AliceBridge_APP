package dev.alicebridge.station;

import java.io.IOException;

/** Worker ownership is local to one selected station session. */
final class MaxBridgeRegistry {
    private MaxRootBridge active;
    private boolean cacheGuard, permissive, adbSettings;

    synchronized boolean ready(String host) {
        return active != null && active.host.equals(host) && active.alive();
    }

    synchronized MaxRootBridge current(String host) throws IOException {
        if (!ready(host))
            throw new IOException(
                    "Root-сессия Макс недоступна; повторный exploit на той же загрузке не"
                            + " запускается");
        return active;
    }

    synchronized boolean complete(String host) {
        return ready(host) && cacheGuard && permissive && adbSettings;
    }

    synchronized boolean cacheGuard(String host) {
        return ready(host) && cacheGuard;
    }

    synchronized void confirmCacheGuard() {
        cacheGuard = true;
    }

    synchronized void confirmPermissive() {
        permissive = true;
    }

    synchronized void confirmAdbSettings() {
        adbSettings = true;
    }

    synchronized boolean shellReady(String host) {
        return ready(host) && permissive;
    }

    synchronized void activate(MaxRootBridge worker) {
        if (active != null && active != worker) active.close();
        active = worker;
        cacheGuard = false;
        permissive = false;
        adbSettings = false;
    }

    synchronized void stop() {
        if (active != null) active.close();
        active = null;
        cacheGuard = false;
        permissive = false;
        adbSettings = false;
    }
}
