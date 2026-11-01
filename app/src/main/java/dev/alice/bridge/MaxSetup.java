package dev.alice.bridge;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** Original Max: exact build gate, reviewed process root, cache guard, runtime permissive. */
final class MaxSetup {
 private static final Set<String> attempted=new HashSet<>();
 private static void stages(int current,String status,String detail){
  List<StationSetup.Step> steps=new ArrayList<>(MaxPlan.pending());
  for(int i=0;i<steps.size();i++){StationSetup.Step s=steps.get(i);steps.set(i,new StationSetup.Step(s.id(),s.title(),i<current?"Готово":i==current?status:"Ожидание",i==current?detail:""));}Session.setupSteps=List.copyOf(steps);
 }
 static void ensure(Context context,NativeAdb adb,Map<String,String> info,boolean apply)throws Exception {
  if(MaxRootBridge.ready(adb.target())){Session.setupSteps=MaxPlan.pending().stream().map(s->new StationSetup.Step(s.id(),s.title(),"Готово","")).collect(java.util.stream.Collectors.toList());return;}
  MaxPlan.require(info);
  if(!apply){stages(0,"Не применено","Нажмите «Получить root Макс», чтобы запустить отдельный сценарий для YNDX-0008. Root и Permissive действуют до перезагрузки.");return;}
  String key=MaxPlan.attemptKey(info);synchronized(attempted){
   android.content.SharedPreferences history=context.getSharedPreferences("max-attempts",0);
   if(history.getBoolean(key,false)||attempted.contains(key))throw new IOException("Попытка root на этой загрузке Макс уже выполнялась. Перезапуск приложения и смена IP не разрешают повторный exploit. Перезагрузите колонку перед новой попыткой.");
   if(!history.edit().putBoolean(key,true).commit())throw new IOException("Не удалось сохранить ограничение повторного root Макс");
   attempted.add(key);
  }
  MaxCommands wire=new MaxCommands(context,adb.target());MaxRootBridge bridge=null;int stage=0;
  try {
   stages(0,"Выполняется","Передаём проверенные бинарники root. Это процессный root: boot и разделы прошивки не записываются. Запуск может занять до 6 минут.");
   NativeAdb.Result previous=wire.rawShell("pidof root-bridge 2>/dev/null");if(previous.code()==0&&!previous.output().trim().isEmpty())throw new IOException("На Макс уже работает root-bridge. Второй экземпляр не запускаем; перезагрузите колонку перед новым проходом.");
   require(wire.rawShell("mkdir -p "+MaxPlan.BASE));
   for(String name:List.of("root-bridge","permissive")){
    File local=File.createTempFile("max-", ".bin",context.getCacheDir());
    try{byte[] data;try(InputStream in=context.getAssets().open("max/"+name)){data=Streams.readBounded(in,2*1024*1024);}StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))hash.append(String.format(Locale.ROOT,"%02x",b&255));if(!hash.toString().equals(MaxPlan.HASHES.get(name)))throw new IOException("Повреждён бинарник Макс "+name);try(OutputStream out=new FileOutputStream(local)){out.write(data);}require(adb.push(local,MaxPlan.BASE+"/"+name));}
    finally{local.delete();}
   }
   StringBuilder verify=new StringBuilder("set -e\n");for(String name:List.of("root-bridge","permissive"))verify.append("[ \"$(sha256sum ").append(MaxPlan.BASE).append('/').append(name).append(" | cut -d ' ' -f1)\" = ").append(MaxPlan.HASHES.get(name)).append(" ]\nchmod 755 ").append(MaxPlan.BASE).append('/').append(name).append('\n');require(wire.rawShell(verify.toString()));
   bridge=new MaxRootBridge(wire.openBridge(),adb.target(),info.get("BOOT"),line->Session.log(context,"MAX_ROOT","Макс",line,"Root bridge"));bridge.awaitReady();
   stage=1;stages(stage,"Выполняется","Root worker запущен. Проверяем защиту cache/recovery. Это дополнительная защита, полное отключение OTA этим методом не подтверждено.");
   bridge.builtin("\\chown /cache/recovery 0 0","\\chown: ok uid=0 gid=0");
   bridge.builtin("\\chmod /cache/recovery 0750","\\chmod: ok mode=0750 uid=0 gid=0");
   bridge.builtin("\\chmod /cache 1770","\\chmod: ok mode=1770");
   bridge.builtin("\\stat /cache/recovery","\\stat: mode=0750 uid=0 gid=0");
   bridge.builtin("\\stat /cache","\\stat: mode=1770");
   stage=2;stages(stage,"Выполняется","Проверяем kernel layout и включаем runtime Permissive. После перезагрузки он сбросится; автоматической перезагрузки не будет.");
   String enforcing=wire.rawShell("getenforce").output().trim();
   if(!enforcing.equals("Permissive")){
    NativeAdb.Result check=permissive(wire,true);if(check.code()!=0||!check.output().contains("RESULT: PERMISSIVE CHECK OK value=1"))throw new IOException("Проверка Permissive не пройдена: "+check.output());
    NativeAdb.Result flip=permissive(wire,false);if(flip.code()!=0||!flip.output().contains("RESULT: PERMISSIVE OK"))throw new IOException("Permissive не подтверждён: "+flip.output());
   }
   NativeAdb.Result finalCheck=wire.rawShell("getenforce; cat /sys/fs/selinux/enforce");require(finalCheck);if(!finalCheck.output().contains("Permissive")||!finalCheck.output().matches("(?s).*\\b0\\s*$"))throw new IOException("SELinux не подтвердил Permissive: "+finalCheck.output());
   bridge.execute("id; cat /proc/self/status | grep CapEff; cat /proc/self/attr/current");
   MaxRootBridge.activate(bridge);bridge=null;
   NativeAdb.Result identity=MaxCommands.shell(context,adb,"id -u; cat /proc/self/status | grep CapEff; cat /proc/self/attr/current");require(identity);MaxPlan.requireIdentity(identity.output());
   Map<String,String> confirmed=new LinkedHashMap<>(info);confirmed.put("UID","0");confirmed.put("ROOT_TRANSPORT","Max root bridge · runtime");Session.stationInfo=Map.copyOf(confirmed);
   Session.setupSteps=MaxPlan.pending().stream().map(s->new StationSetup.Step(s.id(),s.title(),"Готово",s.id().equals("maxguard")?"Cache guard подтверждён. Полное отключение OTA не гарантируется.":"Root и Permissive временные, до перезагрузки колонки.")).collect(java.util.stream.Collectors.toList());
   Session.log(context,"SETUP","Макс YNDX-0008",identity.output()+"\n"+finalCheck.output(),"Runtime root подтверждён");
  }catch(Exception e){if(bridge!=null)bridge.close();MaxRootBridge.stop();stages(stage,"Ошибка",e.getMessage());throw e;}
 }
 private static NativeAdb.Result permissive(MaxCommands wire,boolean check)throws Exception {
  NativeAdb.Result r=wire.rawShell(MaxPlan.BASE+"/permissive esc-permissive"+(check?" check":""));
  if(r.code()!=0&&MaxPlan.readerRetry(r.output())){Thread.sleep(3000);r=wire.rawShell(MaxPlan.BASE+"/permissive esc-permissive"+(check?" check":""));}return r;
 }
 private static void require(NativeAdb.Result r)throws IOException {if(r.code()!=0)throw new IOException(r.output());}
}
