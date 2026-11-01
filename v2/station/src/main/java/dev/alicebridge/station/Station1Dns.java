package dev.alicebridge.station;
import dev.alicebridge.bridge.*;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class Station1Dns {
    private final StationState scopeState;
    public Station1Dns(StationState state){this.scopeState=state;}

    void ensure(Context context,NativeAdb adb)throws Exception{
        if(!Station1Plan.matches(scopeState.stationInfo))return;
        NativeAdb.Result ready=adb.shell("test -x /data/opt/bin/alicebridge-dns.sh && test -s /etc/resolv.conf && /system/bin/sh /data/opt/bin/alicebridge-dns.sh");
        if(ready.code()==0)return;
        String script;try(InputStream in=context.getAssets().open("scripts/station1-dns.sh")){script=new String(Streams.readBounded(in,32768),StandardCharsets.UTF_8);}
        NativeAdb.Result result=adb.shell(script);if(result.code()!=0)throw new IOException("Не удалось настроить DNS Entware: "+result.output());
    }
}
