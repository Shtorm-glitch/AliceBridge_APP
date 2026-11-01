package dev.alicebridge.bridge;

import android.content.Context;
import android.os.Looper;
import java.lang.reflect.*;
import java.util.concurrent.Executor;

/** Runs only via su + app_process, outside the untrusted app's hidden API restrictions.
 * Uses the same root-side TetheringManager strategy as VPNHotspot's TetheringCommands.
 * Android 12L's startTethering(int, Executor, StartTetheringCallback) is verified in AOSP.
 */
public final class TetheringShell {
    @android.annotation.SuppressLint("WrongConstant") // Hidden service name, executed in the root shell only.
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object thread = activityThread.getMethod("systemMain").invoke(null);
                Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
                Object manager = context.getSystemService("tethering");
                if (manager == null) throw new IllegalStateException("TetheringManager недоступен");
                Class<?> clazz = Class.forName("android.net.TetheringManager");
                if (args.length > 0 && "interfaces".equals(args[0])) {
                    String[] interfaces = (String[]) clazz.getMethod("getTetheredIfaces").invoke(manager);
                    for (String iface : interfaces) System.out.println("TETHERED_IFACE=" + iface);
                    System.exit(0);
                }
                if (args.length > 0 && "stop".equals(args[0])) {
                    clazz.getMethod("stopTethering", int.class).invoke(manager, 0);
                    long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
                    boolean active;
                    do {
                        android.os.SystemClock.sleep(250);
                        active = false;
                        for (String name : (String[]) clazz.getMethod("getTetheredIfaces").invoke(manager))
                            active |= name.matches("(?i).*(wlan|wifi|swlan|ap).*");
                    } while (active && android.os.SystemClock.elapsedRealtime() < deadline);
                    if (active) throw new IllegalStateException("Android ещё не остановил точку доступа");
                    System.out.println("TETHERING_STOPPED"); System.exit(0);
                }
                if (args.length >= 3) {
                    Class<?> configuration = Class.forName("android.net.wifi.SoftApConfiguration");
                    Class<?> wifiClass = Class.forName("android.net.wifi.WifiManager");
                    Object wifi = context.getSystemService(Context.WIFI_SERVICE);
                    // Recreate the AP even for the same SSID: live hostapd security may differ
                    // from the framework's stored WPA2 configuration after a mixed-mode session.
                    // Clear a previous root-process request before issuing a new one on Android 16.
                    clazz.getMethod("stopTethering", int.class).invoke(manager, 0);
                    long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
                    boolean active;
                    do {
                        android.os.SystemClock.sleep(250); active = false;
                        for (String name : (String[]) clazz.getMethod("getTetheredIfaces").invoke(manager))
                            active |= name.matches("(?i).*(wlan|wifi|swlan|ap).*");
                    } while (active && android.os.SystemClock.elapsedRealtime() < deadline);
                    if (active) throw new IllegalStateException("Не завершилась предыдущая сессия хотспота");
                    Class<?> builderClass = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
                    Object builder = builderClass.getConstructor().newInstance();
                    builderClass.getMethod("setSsid", String.class).invoke(builder, args[1]);
                    builderClass.getMethod("setPassphrase", String.class, int.class).invoke(builder, args[2], configuration.getField("SECURITY_TYPE_WPA2_PSK").getInt(null));
                    builderClass.getMethod("setBand", int.class).invoke(builder, configuration.getField("BAND_2GHZ").getInt(null));
                    Object config = builderClass.getMethod("build").invoke(builder);
                    Object applied = wifiClass.getMethod("setSoftApConfiguration", configuration).invoke(wifi, config);
                    if (!Boolean.TRUE.equals(applied)) throw new IllegalStateException("Android отказал в настройке SSID/пароля");
                    System.out.println("HOTSPOT_CONFIGURED_2GHZ");
                }
                Class<?> callback = Class.forName("android.net.TetheringManager$StartTetheringCallback");
                Object proxy = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "onTetheringStarted": System.out.println("TETHERING_STARTED"); System.exit(0); break;
                        case "onTetheringFailed": System.err.println("TETHERING_FAILED code=" + a[0]); System.exit(2); break;
                        case "toString": return "AliceBridgeTetheringCallback";
                        case "hashCode": return System.identityHashCode(p);
                        case "equals": return p == a[0];
                    }
                    return null;
                });
                clazz.getMethod("startTethering", int.class, Executor.class, callback)
                    .invoke(manager, 0, (Executor) Runnable::run, proxy);
        } catch (Exception e) {
            e.printStackTrace(System.err); System.exit(3);
        }
        Looper.loop();
    }
}
