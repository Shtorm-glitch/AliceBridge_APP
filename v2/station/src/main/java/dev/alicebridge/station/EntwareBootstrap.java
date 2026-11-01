package dev.alicebridge.station;
import dev.alicebridge.bridge.*;

import android.content.Context;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

/** Offline base packages, copied from APK and checked before execution on the station. */
public final class EntwareBootstrap {
    private final StationState scopeState;
    public EntwareBootstrap(StationState state){this.scopeState=state;}

    record Payload(String base,String arch){}
    String prepare(Context context,NativeAdb adb) throws Exception {
        NativeAdb.Result ready=adb.shell("[ -x /opt/bin/curl ] && [ -x /opt/bin/opkg ] && /opt/bin/opkg list-installed | grep -q '^libpthread '");
        if(ready.code()==0)return "";
        Payload payload=stage(context,adb,"entware");
        return "sh "+payload.base()+"/install.sh "+payload.base()+" "+payload.arch()+" && ";
    }
    Payload stage(Context context,NativeAdb adb,String kind)throws Exception{
        if(!kind.equals("entware")&&!kind.equals("zsh"))throw new IllegalArgumentException("Unknown offline payload");
        String base=(Station1Plan.matches(scopeState.stationInfo)?"/data/local/tmp/alicebridge-offline/":"/tmp/alicebridge-offline/")+kind,prefix=kind.equals("entware")?"":"zsh-";
        NativeAdb.Result machine=adb.shell("uname -m");require(machine);
        String arch=switch(machine.output().trim()){case "aarch64","arm64"->"aarch64-k3.10";case "armv7l","armv6l","arm"->"armv7sf-k3.2";default->throw new IOException("Entware: неподдерживаемая архитектура "+machine.output());};
        if(kind.equals("entware"))scopeState.yseStatus="Передаём Entware из APK на колонку";
        String expected;
        try(InputStream in=context.getAssets().open("entware/"+prefix+arch+".json")){expected=new JSONObject(new String(Streams.readBounded(in,65536),java.nio.charset.StandardCharsets.UTF_8)).getString("archiveSha256");}
        if(!expected.matches("[0-9a-f]{64}"))throw new IOException("Некорректная контрольная сумма Entware");
        File bundle=new File(context.getCacheDir(),kind+"-offline.tar.gz"),script=new File(context.getCacheDir(),"install-"+kind+"-offline.sh");
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream in=context.getAssets().open("entware/"+prefix+arch+".bin");OutputStream out=new FileOutputStream(bundle)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){out.write(buffer,0,n);hash.update(buffer,0,n);}}
            StringBuilder actual=new StringBuilder();for(byte b:hash.digest())actual.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!actual.toString().equals(expected))throw new IOException("Повреждён офлайн-комплект Entware");
            try(InputStream in=context.getAssets().open("scripts/install-"+kind+"-offline.sh");OutputStream out=new FileOutputStream(script)){out.write(Streams.readBounded(in,32768));}
            require(adb.shell("mkdir -p "+base));require(adb.push(bundle,base+"/bundle.tar.gz"));require(adb.push(script,base+"/install.sh"));
            require(adb.shell("set -e\n[ \"$(sha256sum "+base+"/bundle.tar.gz | awk '{print $1}')\" = "+expected+" ]\ngunzip -c "+base+"/bundle.tar.gz | tar -xf - -C "+base+"\nrm "+base+"/bundle.tar.gz"));
            return new Payload(base,arch);
        }finally{bundle.delete();script.delete();}
    }
    private void require(NativeAdb.Result result)throws IOException{if(result.code()!=0)throw new IOException(result.output());}
}
