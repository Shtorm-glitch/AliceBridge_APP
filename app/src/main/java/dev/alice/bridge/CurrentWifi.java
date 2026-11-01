package dev.alice.bridge;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Credentials stay in memory: no RootShell output files, preferences or logs. */
final class CurrentWifi {
    record Result(String ssid,String password) {}
    static Result read(Context context)throws Exception{
        if(ShizukuNetwork.selected(context))return new Result("", "");
        String ssid=WifiProfile.currentSsid(readRoot("cmd wifi status"));
        String hotspot=context.getSharedPreferences("config",Context.MODE_PRIVATE).getString("hotspot_ssid","AliceBridge");
        if(ssid.isEmpty()||ssid.equals(hotspot))return new Result("","");
        String[] files={"/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml","/data/misc/wifi/WifiConfigStore.xml","/data/misc_ce/0/wifi/WifiConfigStore.xml","/data/misc_ce/0/apexdata/com.android.wifi/WifiConfigStore.xml"};
        String password="";
        for(String file:files){
            String xml=readRoot("if [ -f "+file+" ]; then cat "+file+"; fi");
            if(xml.isEmpty())continue;
            try{WifiProfile.Credentials value=WifiProfile.find(ssid,xml);if(value!=null){password=value.password();break;}}catch(Exception ignored){}
        }
        // The association may change while reading the stores.
        if(!ssid.equals(WifiProfile.currentSsid(readRoot("cmd wifi status"))))return new Result("","");
        return new Result(ssid,password);
    }
    private static String readRoot(String command)throws Exception{
        Process process=new ProcessBuilder("su","-c",command).redirectError(ProcessBuilder.Redirect.to(new File("/dev/null"))).start();
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try{
            Future<byte[]> output=reader.submit(()->{try(InputStream in=process.getInputStream()){return Streams.readBounded(in,2*1024*1024);}});
            byte[] bytes=output.get(5,TimeUnit.SECONDS);
            if(!process.waitFor(1,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Wi-Fi credentials unavailable");
            return new String(bytes,StandardCharsets.UTF_8).trim();
        }finally{process.destroyForcibly();reader.shutdownNow();}
    }
}
