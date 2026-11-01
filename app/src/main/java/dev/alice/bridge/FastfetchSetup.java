package dev.alice.bridge;
import android.content.Context;
import java.io.*;
import java.util.*;
/** Optional automatic package stage, separate from mandatory root/OTA setup. */
final class FastfetchSetup {
 private static final String ID="fastfetch";
 static void set(String status,String output){List<StationSetup.Step> steps=new ArrayList<>();for(StationSetup.Step s:Session.setupSteps)if(!s.id().equals(ID))steps.add(s);steps.add(new StationSetup.Step(ID,"Fastfetch",status,output));Session.setupSteps=List.copyOf(steps);}
 static boolean done(Context c,String key){return c.getSharedPreferences("fastfetch",0).contains(key);}
 static String key(){return DeviceNames.identifier(Session.stationInfo)+":"+Session.stationInfo.getOrDefault("MAC",Session.station);}
 static void skip(Context c){c.getSharedPreferences("fastfetch",0).edit().putBoolean(key(),false).apply();set("Пропущено","Установку fastfetch пропустили. Настройка колонки продолжится.");}
 static void ensure(Context context,NativeAdb adb)throws InterruptedException {
  String key=key();if(done(context,key)){set(context.getSharedPreferences("fastfetch",0).getBoolean(key,false)?"Готово":"Пропущено","");return;}
  set("Выполняется","Передаём fastfetch из APK. Если установка не получится, этот шаг можно пропустить.");File file=null;
  try{
   boolean android=!Session.stationInfo.getOrDefault("SDK","").isEmpty();String base=android?"/data/local/bin":"/usr/local/bin";
   NativeAdb.Result available=MaxCommands.shell(context,adb,"test -x "+base+"/fastfetch && "+base+"/fastfetch --version");
   if(available.code()!=0){
    file=File.createTempFile("fastfetch-", ".bin",context.getCacheDir());try(InputStream in=context.getAssets().open("packages/fastfetch");OutputStream out=new FileOutputStream(file)){out.write(Streams.readBounded(in,8*1024*1024));}
    String staging="/data/local/tmp/alicebridge-fastfetch";require(adb.push(file,staging));
    String install="set -e\n"+(android?"":"""
        ab_ro=0
        case ",$(awk '$2=="/"{print $4;exit}' /proc/mounts)," in *,ro,*) ab_ro=1; mount -o remount,rw /;; esac
        trap '[ "$ab_ro" != 1 ] || mount -o remount,ro /' EXIT
        """)+"mkdir -p "+base+"\ncp "+staging+" "+base+"/fastfetch\nchmod 755 "+base+"/fastfetch\nrm -f "+staging+"\n"+base+"/fastfetch --version";
    require(MaxCommands.shell(context,adb,install));
   }
   context.getSharedPreferences("fastfetch",0).edit().putBoolean(key,true).apply();set("Готово","fastfetch установлен. Запуск: fastfetch.");Session.log(context,"SETUP","Fastfetch","Пакет передан из APK и проверен запуском --version","Готово");
  }catch(InterruptedException e){throw e;}catch(Exception e){set("Ошибка",e.getMessage());Session.log(context,"SETUP","Fastfetch",e.toString(),"Можно пропустить");}
  finally{if(file!=null)file.delete();}
 }
 private static void require(NativeAdb.Result r)throws IOException{if(r.code()!=0)throw new IOException(r.output());}
}
