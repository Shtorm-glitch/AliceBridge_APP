package dev.alicebridge.bridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Root-only opt-in bridge to a vendor hostapd control socket; no persistent Wi-Fi edits. */
public final class LegacyWpaShell {
    private static String stage="configuration";
    private static final List<String> ROOTS=List.of("/data/vendor/wifi/hostapd","/data/misc/wifi/hostapd");
    public static void main(String[] args) {
        try {
            if(android.os.Process.myUid()!=0 || args.length!=2)throw new IOException("Требуется root и интерфейс хотспота");
            RootShell.validateInterface(args[0]);
            apply(args[0],args[1]);
            System.out.println("HOTSPOT_SECURITY_VERIFIED=WPA_WPA2_PSK");
            System.exit(0);
        } catch(Exception e) {
            // Do not print config, GET_CONFIG responses or exception text containing vendor output.
            String reason=e.getClass().getSimpleName();
            for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof android.system.ErrnoException)reason+=" errno="+((android.system.ErrnoException)cause).errno;
            if(stage.equals("control-ping") || stage.equals("verify-security"))reason+=" "+String.valueOf(e.getMessage());
            System.err.println("WPA/WPA2 не включён: этап="+stage+", ошибка="+reason+". Выберите WPA2.");
            System.exit(2);
        }
    }
    private static void apply(String iface,String ssid)throws Exception {
        for(String root:ROOTS) {
            Path path=Paths.get(root,"hostapd_"+iface+".conf");
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>65536)continue;
            String original=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
            StringBuilder encoded=new StringBuilder();
            for(byte b:ssid.getBytes(StandardCharsets.UTF_8))encoded.append(String.format(Locale.ROOT,"%02x",b&255));
            String hex=encoded.toString();
            if(!LegacyWpaConfig.value(original,"interface").equals(iface) ||
                !(LegacyWpaConfig.value(original,"ssid2").equalsIgnoreCase(hex) || LegacyWpaConfig.value(original,"ssid").equals(ssid)))continue;
            String dir=LegacyWpaConfig.value(original,"ctrl_interface");
            if(!dir.startsWith(root+"/") || dir.contains("..") || dir.contains("\n"))continue;
            stage="control-connect";
            try(Control control=new Control(dir+"/"+iface)) {
                stage="control-ping";
                if(!control.request("PING").trim().equals("PONG"))continue;
                // Prove ownership before touching this AP. Raw GET_CONFIG may contain PSK: never log it.
                stage="read-security";String before=control.request("GET_CONFIG");
                if(!LegacyWpaConfig.value(before,"ssid").equals(ssid) || !List.of("2","3").contains(LegacyWpaConfig.value(before,"wpa")))continue;
                boolean success=false;
                try {
                    stage="write-runtime-config";Files.write(path,LegacyWpaConfig.mixed(original).getBytes(StandardCharsets.UTF_8),StandardOpenOption.TRUNCATE_EXISTING);
                    stage="reload";
                    for(String command:List.of("SET wpa 3","SET wpa_pairwise TKIP CCMP","SET rsn_pairwise CCMP","SET group_cipher CCMP","SET ieee80211w 0"))if(!control.request(command).trim().equals("OK"))throw new IOException("Security command rejected");
                    if(!control.request("RELOAD").trim().equals("OK"))throw new IOException("Reload rejected");
                    stage="verify-security";long until=SystemClock.elapsedRealtime()+4000;String last="";
                    do {
                        String config=control.request("GET_CONFIG"),status=control.request("STATUS");
                        last="wpa="+LegacyWpaConfig.value(config,"wpa")+", WPA cipher="+LegacyWpaConfig.value(config,"wpa_pairwise_cipher")+", RSN cipher="+LegacyWpaConfig.value(config,"rsn_pairwise_cipher")+", group cipher="+LegacyWpaConfig.value(config,"group_cipher")+", state="+LegacyWpaConfig.value(status,"state");
                        if(LegacyWpaConfig.isMixed(config) && LegacyWpaConfig.value(status,"state").equals("ENABLED")){success=true;break;}
                        SystemClock.sleep(200);
                    }while(SystemClock.elapsedRealtime()<until);
                    if(!success)throw new IOException(last);
                } finally {
                    // Runtime hostapd has its own parsed copy. Future reloads return to framework WPA2.
                    Files.write(path,original.getBytes(StandardCharsets.UTF_8),StandardOpenOption.TRUNCATE_EXISTING);
                    if(!success)try{control.request("RELOAD");}catch(Exception ignored){}
                }
                return;
            }
        }
        throw new IOException("No compatible hostapd");
    }
    private static final class Control implements AutoCloseable {
        private final LocalSocket socket;
        private final String client;
        Control(String endpoint)throws IOException {
            client=Paths.get(endpoint).getParent().getParent().resolve("sockets/ab-"+android.os.Process.myPid()+"-"+UUID.randomUUID().toString().substring(0,8)).toString();
            LocalSocket created=null;
            try {
                // Label only this thread's new socket as hostapd. Some enforcing vendor policies
                // reject daemon replies to a root/KSU-labelled UNIX datagram socket.
                String context=daemonContext();
                try {
                    Files.write(Paths.get("/proc/thread-self/attr/sockcreate"),(context+"\0").getBytes(StandardCharsets.US_ASCII));
                    created=new LocalSocket(LocalSocket.SOCKET_DGRAM);
                    created.bind(new LocalSocketAddress(client,LocalSocketAddress.Namespace.FILESYSTEM));
                } finally {
                    Files.write(Paths.get("/proc/thread-self/attr/sockcreate"),new byte[]{0});
                }
                socket=created;
                android.system.Os.chmod(client,0660);android.system.Os.chown(client,1010,1010);
                java.lang.Process restore=new ProcessBuilder("/system/bin/restorecon",client).redirectErrorStream(true).start();
                try{if(!restore.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)){restore.destroyForcibly();throw new IOException("Socket label timeout");}}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                socket.setSoTimeout(2000);
                socket.connect(new LocalSocketAddress(endpoint,LocalSocketAddress.Namespace.FILESYSTEM));
            }catch(Exception e){if(created!=null)created.close();new File(client).delete();throw new IOException(e);}
        }
        private static String daemonContext()throws Exception {
            java.lang.Process p=new ProcessBuilder("/system/bin/pidof","hostapd").start();
            if(!p.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("hostapd lookup timeout");}
            String pid=new String(Streams.readBounded(p.getInputStream(),65536),StandardCharsets.US_ASCII).trim().split("\\s+")[0];
            if(!pid.matches("[0-9]{1,9}"))throw new IOException("No hostapd process");
            String label=new String(Files.readAllBytes(Paths.get("/proc/"+pid+"/attr/current")),StandardCharsets.US_ASCII).trim().replace("\0","");
            if(!label.matches("u:r:[a-zA-Z0-9_]*hostapd[a-zA-Z0-9_]*:s0"))throw new IOException("Unknown hostapd domain");
            return label;
        }
        String request(String command)throws IOException {
            socket.getOutputStream().write(command.getBytes(StandardCharsets.US_ASCII));
            byte[] reply=new byte[16384];int count=socket.getInputStream().read(reply);
            if(count<=0 || count==reply.length)throw new IOException("Invalid control reply");
            return new String(reply,0,count,StandardCharsets.UTF_8);
        }
        public void close()throws IOException{try{socket.close();}finally{new File(client).delete();}}
    }
}
