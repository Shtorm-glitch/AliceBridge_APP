package dev.alice.bridge;

import java.util.*;

/** Exact, reviewed Station 1 image and partition; never used for Mini/Lite/Max. */
public final class Station1Plan {
    public static final String ROOT_HASH="5bb6c43f37ae658112120604dc84eeb358c742587a427e85c77c7d9d19559478";
    public static final String STOCK_HASH="e1dbdbb6bcd5e3589fc4c0ca6220856c6d242c40376d406d47720e2fbd8ad8dd";
    public static final int IMAGE_SIZE=16777216;
    public static boolean matches(Map<String,String> info){
        String platform=info.getOrDefault("PLATFORM",""),device=info.getOrDefault("DEVICE","");
        if(device.equals("yandexstation_2"))return false;
        if(!info.getOrDefault("BOARD", "").equals("tulip"))return false;
        return platform.equals("yandexstation") || info.getOrDefault("BUILDNAME", "").equals("yandexstation") || device.equals("yandexstation") ||
            (device.equals("tulip-d1")&&info.getOrDefault("BOARD","").equals("tulip")&&info.getOrDefault("SDK","").equals("23"));
    }
    public static List<StationSetup.Step> pending(){return List.of(
        new StationSetup.Step("boot","Root boot Станции 1","Ожидание",""),
        new StationSetup.Step("reboot","Перезагрузка Станции 1","Ожидание",""),
        new StationSetup.Step("verify","Root-доступ после перезагрузки","Ожидание",""));}
    public static String asRoot(String script){return "su 0 /system/bin/sh -c '"+script.replace("'","'\\''")+"'";}
    /** Retail su has UID 0 without CAP_DAC_OVERRIDE; shell must create its own backup. */
    public static String backupScript(String destination){
        if(!destination.equals("/data/local/tmp/alicebridge-station1/boot-before.img"))throw new IllegalArgumentException("Unexpected backup path");
        return "set -e\numask 077\nBB=/system/bin/busybox-smp\n"+
            "$BB mkdir -p /data/local/tmp/alicebridge-station1\n"+
            "su 0 /system/bin/busybox-smp dd if=/dev/block/mmcblk0p6 bs=4096 count=4096 > "+destination+"\n"+
            "$BB chmod 600 "+destination+"\n";
    }
    public static String writeScript(){return "set -e\nBB=/system/bin/busybox-smp\n[ \"$(getprop ro.board.platform)\" = tulip ]\n[ \"$(cat /sys/class/block/mmcblk0p6/size)\" = 32768 ]\n"+
        "[ \"$($BB sha256sum /data/local/tmp/boot-root.img | $BB awk '{print $1}')\" = "+ROOT_HASH+" ]\n"+
        "$BB dd if=/data/local/tmp/boot-root.img of=/dev/block/mmcblk0p6 bs=4096 count=4096\nsync\n"+
        "[ \"$($BB dd if=/dev/block/mmcblk0p6 bs=4096 count=4096 2>/dev/null | $BB sha256sum | $BB awk '{print $1}')\" = "+ROOT_HASH+" ]\necho "+ROOT_HASH;}
    /** Called only after an independent phone backup and the active root shell are verified. */
    public static String cleanupScript(){return cleanupScript(STOCK_HASH);}
    public static String cleanupScript(String backupHash){
        if(backupHash==null||!backupHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid backup hash");
        return "set -e\nBB=/system/bin/busybox-smp\n"+
        "[ \"$(getprop ro.board.platform)\" = tulip ]\n[ \"$(id -u)\" = 0 ]\n"+
        "[ \"$($BB dd if=/dev/block/mmcblk0p6 bs=4096 count=4096 2>/dev/null | $BB sha256sum | $BB awk '{print $1}')\" = "+ROOT_HASH+" ]\n"+
        "check_image() { [ ! -e \"$1\" ] || { [ ! -L \"$1\" ] && [ \"$($BB sha256sum \"$1\" | $BB awk '{print $1}')\" = \"$2\" ]; }; }\n"+
        "check_image /data/local/tmp/alicebridge-station1/boot-before.img "+backupHash+"\n"+
        "check_image /data/local/tmp/boot-root.img "+ROOT_HASH+"\n"+
        "$BB rm -f /data/local/tmp/alicebridge-station1/boot-before.img /data/local/tmp/boot-root.img\n"+
        "echo AB_BOOT_TEMP_CLEANED\n";}
    public static final String PROBE="""
        set -e
        [ "$(getprop ro.board.platform)" = tulip ]
        [ "$(cat /sys/class/block/mmcblk0p6/size)" = 32768 ]
        /system/bin/busybox-smp dd if=/dev/block/mmcblk0p6 bs=4096 count=4096 2>/dev/null | /system/bin/busybox-smp sha256sum
        """;
}
