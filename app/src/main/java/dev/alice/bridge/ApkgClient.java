package dev.alice.bridge;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** User-requested apkg installation from initialfox/yandex-station-repo; never automatic. */
final class ApkgClient {
 static void install(Context context,NativeAdb adb)throws Exception{
  Session.apkgStatus="Передаём apkg из APK…";File local=File.createTempFile("apkg-", ".bin",context.getCacheDir());
  try{
   try(InputStream in=context.getAssets().open("packages/apkg");OutputStream out=new FileOutputStream(local)){out.write(Streams.readBounded(in,16*1024*1024));}
   NativeAdb.Result push=adb.push(local,"/data/local/tmp/alicebridge-apkg");require(push);
   boolean android=!Session.stationInfo.getOrDefault("SDK","").isEmpty();String base=android?"/data/local/bin":"/usr/local/bin";
   String script="set -e\n[ \"$(id -u)\" = 0 ] || { echo 'Нужен root колонки'; exit 1; }\n"+(android?"":"""
       ab_ro=0
       case ",$(awk '$2=="/"{print $4;exit}' /proc/mounts)," in *,ro,*) ab_ro=1; mount -o remount,rw /;; esac
       trap '[ "$ab_ro" != 1 ] || mount -o remount,ro /' EXIT
       """)+"mkdir -p "+base+"\ncp /data/local/tmp/alicebridge-apkg "+base+"/apkg\nchmod 755 "+base+"/apkg\nrm -f /data/local/tmp/alicebridge-apkg\nexport ANDROID_DATA=/data ANDROID_ROOT=/system\n"+base+"/apkg paths";
   NativeAdb.Result result=MaxCommands.shell(context,adb,script);require(result);Session.apkgStatus="apkg установлен · "+base+"/apkg. Первый запуск: apkg update";
   Session.log(context,"APKG","initialfox/yandex-station-repo",result.output(),"Установлено");
  }finally{local.delete();}
 }
 private static void require(NativeAdb.Result r)throws IOException{if(r.code()!=0)throw new IOException(r.output());}
}
