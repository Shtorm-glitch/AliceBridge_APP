package dev.alice.bridge;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class Station1Wifi {
    private static final String BASE="/data/quasar/alicebridge-home-wifi",HELPER=BASE+"/helper.jar";
    private static String prepared="";
    private static void require(NativeAdb.Result result)throws IOException{if(result.code()!=0)throw new IOException(result.output());}
    private static synchronized void prepare(Context context,NativeAdb adb)throws Exception{
        String key=adb.target()+"|"+Session.stationInfo.getOrDefault("BOOT","");if(key.equals(prepared))return;
        require(adb.shell("mkdir -p "+BASE+" && chmod 700 "+BASE));
        File jar=File.createTempFile("station1-wifi-",".jar",context.getCacheDir());
        try{
            try(InputStream in=context.getAssets().open("station1/wifi-helper.jar");OutputStream out=new FileOutputStream(jar)){out.write(Streams.readBounded(in,256*1024));}
            require(adb.push(jar,HELPER));prepared=key;
        }finally{jar.delete();}
    }
    static String readSsid(Context context,NativeAdb adb)throws Exception{
        prepare(context,adb);NativeAdb.Result probe=adb.shell("CLASSPATH="+HELPER+" /system/bin/app_process /system/bin dev.alice.station.StationWifi probe");require(probe);
        for(String line:probe.output().split("\\R"))if(line.startsWith("SSID_HEX=")){
            String hex=line.substring(9);if(!hex.matches("(?:[0-9a-f]{2}){0,32}"))throw new IOException("Неверный SSID от Android Wi-Fi");
            byte[] bytes=new byte[hex.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return new String(bytes,StandardCharsets.UTF_8);
        }
        throw new IOException("Android Wi-Fi не сообщил SSID");
    }
    static NativeAdb.Result switchNetwork(Context context,NativeAdb adb,String ssid,String password)throws Exception{
        String credentials=HomeWifi.credentials(ssid,password);
        prepare(context,adb);
        require(adb.shell("set -e\nmkdir -p "+BASE+"\nchmod 700 "+BASE+"\nif [ -f "+BASE+"/pid ]; then p=$(cat "+BASE+"/pid); case \"$p\" in ''|*[!0-9]*) :;; *) if kill -0 \"$p\" 2>/dev/null && grep -q dev.alice.station.StationWifi /proc/$p/cmdline; then echo 'Переключение Wi-Fi уже выполняется'; exit 1; fi;; esac; fi"));
        File secret=File.createTempFile("station1-network-",".txt",context.getCacheDir());
        String remote=BASE+"/credentials-"+UUID.randomUUID().toString().replace("-","");
        try{
            require(adb.shell("CLASSPATH="+HELPER+" /system/bin/app_process /system/bin dev.alice.station.StationWifi probe"));
            try(OutputStream out=new FileOutputStream(secret)){out.write(credentials.getBytes(StandardCharsets.UTF_8));}
            require(adb.push(secret,remote));
            NativeAdb.Result result=adb.shell("set -e\numask 077\nchmod 600 "+remote+"\ncat > "+BASE+"/switch.sh <<'AB_STATION_WIFI'\n#!/system/bin/sh\nexport CLASSPATH="+HELPER+"\nexec /system/bin/app_process /system/bin dev.alice.station.StationWifi switch "+remote+" > /data/quasar/alicebridge-wifi-switch.log 2>&1\nAB_STATION_WIFI\n/system/bin/busybox-smp start-stop-daemon --start --background --make-pidfile --pidfile "+BASE+"/pid --startas /system/bin/sh -- "+BASE+"/switch.sh\necho 'Сеть передана Android Wi-Fi. При неудаче восстановим прежнее подключение.'");
            if(result.code()!=0)require(adb.shell("rm -f "+remote));
            return result;
        }finally{secret.delete();}
    }
}
