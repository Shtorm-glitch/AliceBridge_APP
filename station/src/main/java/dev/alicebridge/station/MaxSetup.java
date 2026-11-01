package dev.alicebridge.station;
import dev.alicebridge.bridge.*;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** Original Max: exact build gate, reviewed process root, cache guard, runtime permissive. */
public final class MaxSetup {
    private final StationState scopeState;
    public MaxSetup(StationState state){this.scopeState=state;}

 private final Set<String> attempted=new HashSet<>();
 private void stages(int current,String status,String detail){
  List<StationSetup.Step> steps=new ArrayList<>(MaxPlan.pending());
  for(int i=0;i<steps.size();i++){StationSetup.Step s=steps.get(i);steps.set(i,new StationSetup.Step(s.id(),s.title(),i<current?"Готово":i==current?status:"Ожидание",i==current?detail:""));}scopeState.setupSteps=List.copyOf(steps);
 }
 void ensure(Context context,NativeAdb adb,Map<String,String> info,boolean apply)throws Exception {
  if(scopeState.maxBridge.complete(adb.target())){scopeState.setupSteps=MaxPlan.pending().stream().map(s->new StationSetup.Step(s.id(),s.title(),"Готово","")).collect(java.util.stream.Collectors.toList());return;}
  MaxPlan.require(info);
  if(!apply){stages(0,"Не применено","Нажмите «Получить root Макс», чтобы запустить отдельный сценарий для YNDX-0008. Root и Permissive действуют до перезагрузки.");return;}
  MaxCommands wire=new MaxCommands(context,adb.target());MaxRootBridge bridge=null;int stage=0;
  try {
   if(!scopeState.maxBridge.ready(adb.target())) {
  String key=MaxPlan.attemptKey(info);synchronized(attempted){
   android.content.SharedPreferences history=context.getSharedPreferences("max-attempts",0);
   if(history.getBoolean(key,false)||attempted.contains(key))throw new IOException("Попытка root на этой загрузке Макс уже выполнялась. Перезапуск приложения и смена IP не разрешают повторный exploit. Перезагрузите колонку перед новой попыткой.");
   if(!history.edit().putBoolean(key,true).commit())throw new IOException("Не удалось сохранить ограничение повторного root Макс");
   attempted.add(key);
  }
   stages(0,"Выполняется","Передаём проверенные бинарники root. Это процессный root: boot и разделы прошивки не записываются. Запуск может занять до 6 минут.");
   NativeAdb.Result previous=wire.rawShell("pidof root-bridge 2>/dev/null");if(previous.code()==0&&!previous.output().trim().isEmpty())throw new IOException("На Макс уже работает root-bridge. Второй экземпляр не запускаем; перезагрузите колонку перед новым проходом.");
   require(wire.rawShell("mkdir -p "+MaxPlan.BASE));
   for(String name:List.of("root-bridge","permissive")){
    File local=File.createTempFile("max-", ".bin",context.getCacheDir());
    try{byte[] data;try(InputStream in=context.getAssets().open("max/"+name)){data=Streams.readBounded(in,2*1024*1024);}StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))hash.append(String.format(Locale.ROOT,"%02x",b&255));if(!hash.toString().equals(MaxPlan.HASHES.get(name)))throw new IOException("Повреждён бинарник Макс "+name);try(OutputStream out=new FileOutputStream(local)){out.write(data);}require(adb.push(local,MaxPlan.BASE+"/"+name));}
    finally{local.delete();}
   }
   StringBuilder verify=new StringBuilder("set -e\n");for(String name:List.of("root-bridge","permissive"))verify.append("[ \"$(sha256sum ").append(MaxPlan.BASE).append('/').append(name).append(" | cut -d ' ' -f1)\" = ").append(MaxPlan.HASHES.get(name)).append(" ]\nchmod 755 ").append(MaxPlan.BASE).append('/').append(name).append('\n');require(wire.rawShell(verify.toString()));
   bridge=new MaxRootBridge(wire.openBridge(),adb.target(),info.get("BOOT"),line->scopeState.log(context,"MAX_ROOT","Макс",line,"Root bridge"));bridge.awaitReady();
   String workerStatus=bridge.builtin("\\cat /proc/self/status","nonvoluntary_ctxt_switches:");
   String workerContext=bridge.builtin("\\cat /proc/self/attr/current","u:r:init:s0");
   MaxPlan.requireWorkerIdentity(workerStatus,workerContext);
   scopeState.maxBridge.activate(bridge);bridge=null;
   scopeState.log(context,"MAX_ROOT","Макс","UID 0, capabilities и init-SID worker подтверждены. Проверка Permissive выполняется отдельно.","Root worker подтверждён");
   }
   bridge=scopeState.maxBridge.current(adb.target());
   stage=1;stages(stage,"Выполняется","Root worker запущен. Проверяем защиту cache/recovery. Это дополнительная защита, полное отключение OTA этим методом не подтверждено.");
   if(!scopeState.maxBridge.cacheGuard(adb.target())) {
   bridge.builtin("\\chown /cache/recovery 0 0","\\chown: ok uid=0 gid=0");
   bridge.builtin("\\chmod /cache/recovery 0750","\\chmod: ok mode=0750 uid=0 gid=0");
   bridge.builtin("\\chmod /cache 1770","\\chmod: ok mode=1770");
   bridge.builtin("\\stat /cache/recovery","\\stat: mode=0750 uid=0 gid=0");
   bridge.builtin("\\stat /cache","\\stat: mode=1770");
   scopeState.maxBridge.confirmCacheGuard();
   }
   stage=2;stages(stage,"Выполняется","Проверяем kernel layout и включаем runtime Permissive. После перезагрузки он сбросится; автоматической перезагрузки не будет.");
   String enforcing=wire.rawShell("getenforce").output().trim();
   if(!enforcing.equals("Permissive")){
    NativeAdb.Result check=permissive(wire,true);if(check.code()!=0||!check.output().contains("RESULT: PERMISSIVE CHECK OK value=1"))throw new IOException("Проверка Permissive не пройдена: "+check.output());
    NativeAdb.Result flip=permissive(wire,false);if(flip.code()!=0||!flip.output().contains("RESULT: PERMISSIVE OK"))throw new IOException("Permissive не подтверждён: "+flip.output());
   }
   NativeAdb.Result finalCheck=wire.rawShell("getenforce; cat /sys/fs/selinux/enforce");require(finalCheck);if(!finalCheck.output().contains("Permissive")||!finalCheck.output().matches("(?s).*\\b0\\s*$"))throw new IOException("SELinux не подтвердил Permissive: "+finalCheck.output());
   bridge.execute("id; cat /proc/self/status | grep CapEff; cat /proc/self/attr/current");
   NativeAdb.Result identity=MaxCommands.shell(context,adb,"id -u; cat /proc/self/status | grep CapEff; cat /proc/self/attr/current");require(identity);MaxPlan.requireIdentity(identity.output());
   scopeState.maxBridge.confirmPermissive();
   stage=3;stages(stage,"Выполняется","Сохраняем Android-настройки сетевого ADB :5555. Проверка подъёма после перезагрузки ещё потребуется; adbd сейчас не перезапускаем.");
   NativeAdb.Result adbSettings=MaxCommands.shell(context,adb,MaxAdbSettings.script());require(adbSettings);
   if(!adbSettings.output().contains("AB_MAX_ADB_SETTINGS_SAVED"))throw new IOException("Не подтверждены сохранённые настройки ADB Макс");
   scopeState.maxBridge.confirmAdbSettings();
   bridge=null;
   Map<String,String> confirmed=new LinkedHashMap<>(info);confirmed.put("UID","0");confirmed.put("ROOT_TRANSPORT","Max root bridge · runtime");scopeState.stationInfo=Map.copyOf(confirmed);
   scopeState.setupSteps=MaxPlan.pending().stream().map(s->new StationSetup.Step(s.id(),s.title(),"Готово",s.id().equals("maxguard")?"Cache guard подтверждён. Полное отключение OTA не гарантируется.":s.id().equals("maxadb")?"Настройки ADB сохранены и прочитаны обратно. Самостоятельный запуск после перезагрузки ещё не проверен.":"Root и Permissive временные, до перезагрузки колонки.")).collect(java.util.stream.Collectors.toList());
   scopeState.log(context,"SETUP","Макс YNDX-0008",identity.output()+"\n"+finalCheck.output(),"Runtime root подтверждён");
  }catch(Exception e){
   boolean retained=scopeState.maxBridge.ready(adb.target());
   if(!retained){if(bridge!=null)bridge.close();scopeState.maxBridge.stop();}
   String message=e.getMessage();
   if(retained){
    Map<String,String> partial=new LinkedHashMap<>(info);partial.put("UID","0");partial.put("ID","uid=0");partial.put("ROOT_TRANSPORT","Max root worker · Permissive не подтверждён");scopeState.stationInfo=Map.copyOf(partial);
    message="Root worker сохранён. Ошибка этапа «"+(stage==3?"Настройки ADB":stage==2?"SELinux Permissive":"Защита cache recovery")+"»: "+message+". Повторная проверка использует существующий worker; получение root заново не запускается.";
    scopeState.log(context,"MAX_ROOT","Макс",message,"Частичный успех");
   }
   stages(stage,"Ошибка",message);throw new IOException(message,e);
  }
 }
 private NativeAdb.Result permissive(MaxCommands wire,boolean check)throws Exception {
  NativeAdb.Result r=wire.rawShell(MaxPlan.BASE+"/permissive esc-permissive"+(check?" check":""));
  if(r.code()!=0&&MaxPlan.readerRetry(r.output())){Thread.sleep(3000);r=wire.rawShell(MaxPlan.BASE+"/permissive esc-permissive"+(check?" check":""));}return r;
 }
 private void require(NativeAdb.Result r)throws IOException {if(r.code()!=0)throw new IOException(r.output());}
}
