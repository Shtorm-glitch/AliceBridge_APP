package dev.alicebridge.bridge;
import android.os.ParcelFileDescriptor;
import android.os.IBinder;
interface IShizukuNetwork {
    ParcelFileDescriptor prepare(String resolver, IBinder owner) = 0;
    String startHotspot(String ssid, String password) = 1;
    String status() = 2;
    String neighbors(String iface) = 3;
    void stop() = 4;
    String probe() = 5;
    String restartHome() = 6;
    void destroy() = 16777114;
}
