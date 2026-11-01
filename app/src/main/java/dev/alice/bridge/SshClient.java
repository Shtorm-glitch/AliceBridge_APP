package dev.alice.bridge;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Install the reviewed YAM package and configure a persistent SSH service for the speaker. */
public final class SshClient {
    public static int port(){return Station1Plan.matches(Session.stationInfo)?22:2222;}
    public static String probe(String host) throws IOException {
        RootShell.validateIpv4(host);
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(host,port()),2500);socket.setSoTimeout(2500);
            InputStream input=socket.getInputStream();ByteArrayOutputStream line=new ByteArrayOutputStream();
            for(int i=0;i<255;i++){int value=input.read();if(value<0||value==10)break;line.write(value);}
            String banner=new String(line.toByteArray(),StandardCharsets.US_ASCII).trim();
            if(!banner.startsWith("SSH-2.0-"))throw new IOException("Порт "+port()+" не ответил как SSH");
            return banner;
        }
    }
    public static void inspect(){
        try {Session.sshStatus=probe(Session.station)+" · порт "+port();Session.sshReady=true;}
        catch(IOException unavailable){Session.sshReady=false;Session.sshStatus="SSH на порту "+port()+" недоступен";}
    }
    public static void ensure(Context context,NativeAdb adb) throws Exception {
        if(Station1Plan.matches(Session.stationInfo)){ensureStation1(context,adb);return;}
        Session.sshReady=false;Session.sshStatus="Устанавливаем Dropbear";
        NativeAdb.Result existing=adb.shell("test -x /data/quasar/alicebridge-ssh/dropbear && test -f /data/quasar/alicebridge-ssh/configured");
        if(existing.code()!=0){
            File archive=new File(context.getCacheDir(),"dropbear.yap");
            URLConnection connection=new URL("https://raw.githubusercontent.com/nyakokitsu/yapkg/packages/packages/dropbear/2022.82.yap").openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(30000);
            byte[] data;try(InputStream in=connection.getInputStream()){data=Streams.readBounded(in,8*1024*1024);}
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(data);StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            if(!hex.toString().equals("59eb2c3e99596f0e39d550e0d80ee1194bd2cf4cfe85270b60c6e3dbc6bafb1c"))throw new IOException("Не совпал SHA256 Dropbear");
            try(OutputStream out=new FileOutputStream(archive)){out.write(data);}
            NativeAdb.Result prepare=adb.shell("mkdir -p /data/quasar/alicebridge-ssh/package");if(prepare.code()!=0)throw new IOException(prepare.output());
            NativeAdb.Result push=adb.push(archive,"/data/quasar/alicebridge-ssh/dropbear.yap");if(push.code()!=0)throw new IOException(push.output());
            NativeAdb.Result unpack=adb.shell("set -e\n[ \"$(sha256sum /data/quasar/alicebridge-ssh/dropbear.yap | awk '{print $1}')\" = 59eb2c3e99596f0e39d550e0d80ee1194bd2cf4cfe85270b60c6e3dbc6bafb1c ]\ngunzip -c /data/quasar/alicebridge-ssh/dropbear.yap | tar -xf - -C /data/quasar/alicebridge-ssh/package");if(unpack.code()!=0)throw new IOException(unpack.output());
        }
        String script;try(InputStream in=context.getAssets().open("scripts/install-ssh.sh")){script=new String(Streams.readBounded(in,65536),StandardCharsets.UTF_8);}
        Session.sshStatus="Настраиваем пароль и автозапуск SSH";
        NativeAdb.Result result=adb.shell(script);
        if(result.code()!=0)throw new IOException(result.output());
        String banner=probe(adb.target());
        Session.sshReady=true;Session.sshStatus="SSH готов · root · порт 2222 · пароль при установке: 1";
        Session.log(context,"SSH",adb.target(),result.output()+"\n"+banner,"Готово");
    }
    private static void ensureStation1(Context context,NativeAdb adb)throws Exception{
        Session.sshReady=false;Session.sshStatus="Передаём SSH для Станции 1 из APK";
        NativeAdb.Result directory=adb.shell("mkdir -p /data/local/tmp/alicebridge-ssh");if(directory.code()!=0)throw new IOException(directory.output());
        for(String name:new String[]{"dropbear","dropbearkey","ld-musl-aarch64.so.1","libc.musl-aarch64.so.1","libz.so.1"}){
            File file=new File(context.getCacheDir(),"station1-"+name);
            try{
                try(InputStream in=context.getAssets().open("station1/ssh/"+name);OutputStream out=new FileOutputStream(file)){out.write(Streams.readBounded(in,2*1024*1024));}
                NativeAdb.Result push=adb.push(file,"/data/local/tmp/alicebridge-ssh/"+name);if(push.code()!=0)throw new IOException(push.output());
            }finally{file.delete();}
        }
        String script;try(InputStream in=context.getAssets().open("scripts/install-ssh-station1.sh")){script=new String(Streams.readBounded(in,32768),StandardCharsets.UTF_8);}
        Session.sshStatus="Настраиваем SSH Станции 1 и автозапуск rc.local";
        NativeAdb.Result result=adb.shell(script);if(result.code()!=0)throw new IOException(result.output());
        String banner=probe(adb.target());Session.sshReady=true;Session.sshStatus="SSH готов · root · порт 22 · начальный пароль 1";
        Session.log(context,"SSH",adb.target(),result.output()+"\n"+banner,"Готово");
    }

}
