package dev.alice.bridge;

import android.content.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

/** Station 1 only: exact image, independent backup, read-back verification and reboot evidence. */
final class Station1Setup {
    private static long lastRootRequest,lastSearch;
    private static final String IMAGE="/data/local/tmp/boot-root.img",BACKUP="/data/local/tmp/alicebridge-station1/boot-before.img";
    static boolean pending(Context context){return context.getSharedPreferences("station1",Context.MODE_PRIVATE).getBoolean("waiting",false);}
    static boolean otaProtected(Context context){String mac=Session.stationInfo.getOrDefault("MAC",""),boot=Session.stationInfo.getOrDefault("BOOT","");return !mac.isEmpty()&&!boot.isEmpty()&&(mac+":"+boot).equals(context.getSharedPreferences("station1",Context.MODE_PRIVATE).getString("ota_protected_boot",""));}
    private static String root(Map<String,String> info,String command){return "0".equals(info.get("UID"))?command:Station1Plan.asRoot(command);}
    private static String hash(String text)throws IOException{String value=text.trim().split("\\s+",2)[0];if(!value.matches("[0-9a-f]{64}"))throw new IOException("Станция не вернула SHA-256 boot");return value;}
    private static void require(NativeAdb.Result result)throws IOException{if(result.code()!=0)throw new IOException(result.output());}
    private static void steps(String boot,String reboot,String verify,String detail){int active=!boot.equals("Готово")?0:!reboot.equals("Готово")?1:2;Session.setupSteps=List.of(new StationSetup.Step("boot","Root boot Станции 1",boot,active==0?detail:""),new StationSetup.Step("reboot","Перезагрузка Станции 1",reboot,active==1?detail:""),new StationSetup.Step("verify","Root и защита OTA после перезагрузки",verify,active==2?detail:""));}
    static boolean advance(Context context,NativeAdb adb,Map<String,String> info,boolean apply)throws Exception{
        if(!Station1Plan.matches(info))throw new IOException("Образ предназначен только для yandexstation / tulip-d1");
        SharedPreferences state=context.getSharedPreferences("station1",Context.MODE_PRIVATE);
        String mac=info.getOrDefault("MAC",""),boot=info.getOrDefault("BOOT","");
        if(state.getBoolean("waiting",false)&&!mac.equals(state.getString("mac",""))){
            steps("Готово","Выполняется","Ожидание","Ждём прежнюю Станцию 1 по MAC. Другая колонка не изменяется.");
            Session.adbConnected=false;findReturning(context,adb);return false;
        }
        boolean waiting=state.getBoolean("waiting",false);
        if(waiting&&boot.equals(state.getString("old_boot",""))){
            steps("Готово","Выполняется","Ожидание","Станция перезагружается после записи root boot. Не отключайте питание. После загрузки найдём её снова, даже если IP изменится.");
            if(System.currentTimeMillis()-state.getLong("time",0)>180000)steps("Готово","Ошибка","Ожидание","Перезагрузка пока не подтверждена. Проверьте питание колонки. Повторная запись boot автоматически не выполняется.");
            return false;
        }
        if(waiting&&!"0".equals(info.get("UID"))&&!"1".equals(info.get("DEBUGGABLE"))){
            steps("Готово","Готово","Выполняется","Колонка загрузилась. Запрашиваем adb root.");requestRoot(context,adb);return false;
        }
        NativeAdb.Result probe=adb.shell(root(info,Station1Plan.PROBE));
        if(probe.code()!=0){
            if(apply){steps("Выполняется",waiting?"Готово":"Ожидание","Выполняется","Проверяем root ADB и доступ к boot.");requestRoot(context,adb);return false;}
            steps("Не применено","Ожидание","Не применено","Для Станции 1 доступен отдельный сценарий boot. Запустите настройку станции по шагам.");return true;
        }
        String current=hash(probe.output());
        if(current.equals(Station1Plan.ROOT_HASH)){
            if("1".equals(info.get("DEBUGGABLE"))&&!"0".equals(info.get("UID"))){
                NativeAdb.Result identity=adb.shell("id -u");require(identity);
                if(!identity.output().trim().equals("0"))throw new IOException("Root boot записан, но root-оболочка пока не готова");
                Map<String,String> confirmed=new LinkedHashMap<>(info);confirmed.put("UID","0");
                info=Map.copyOf(confirmed);Session.stationInfo=info;
            }
            if(!"1".equals(info.get("DEBUGGABLE"))&&apply){
                if(waiting)throw new IOException("Колонка перезагрузилась, но root boot не стал активным. Повторная прошивка и перезагрузка не выполняются.");
                if(!state.edit().putBoolean("waiting",true).putString("mac",mac).putString("old_boot",boot).putLong("time",System.currentTimeMillis()).commit())throw new IOException("Не сохранён этап перезагрузки");
                steps("Готово","Выполняется","Ожидание","Сейчас Станция перезагрузится для активации root boot. Не отключайте питание; ADB временно пропадёт, затем найдём колонку автоматически.");Session.log(context,"REBOOT","Станция 1","Активация проверенного root boot. Ожидаем отключение ADB и новый идентификатор загрузки.","Сейчас колонка перезагрузится");Session.adbConnected=false;adb.rebootStation();return false;
            }
            if(!"0".equals(info.get("UID"))){
                if(!apply&&!waiting){steps("Готово","Готово","Не применено","Root boot уже записан; запустите настройку, чтобы проверить root ADB.");return true;}
                steps("Готово","Готово","Выполняется","Образ root подтверждён. Запрашиваем adb root.");requestRoot(context,adb);return false;
            }
            steps("Готово","Готово","Выполняется","Root подтверждён. Блокируем запуск OTA перед очисткой временных образов.");
            try(InputStream in=context.getAssets().open("scripts/station1-disable-ota.sh")){
                NativeAdb.Result protection=adb.shell(new String(Streams.readBounded(in,32768),java.nio.charset.StandardCharsets.UTF_8));
                if(protection.code()!=0||!protection.output().contains("AB_STATION1_OTA_BLOCKED")){
                    steps("Готово","Готово","Ошибка","Root есть, но блокировка OTA не подтверждена. Временные образы сохранены. "+protection.output());
                    throw new IOException("Не подтверждена защита Станции 1 от OTA: "+protection.output());
                }
            }
            if(!state.edit().putString("ota_protected_boot",mac+":"+boot).commit())throw new IOException("Не удалось сохранить подтверждение блокировки OTA");
            state.edit().putBoolean("waiting",false).apply();steps("Готово","Готово","Готово","SHA-256 boot и root подтверждены. OTA заблокированы отдельным способом Станции 1; исходный скрипт сохранён.");
            cleanup(context,adb,state,mac,boot);
            Session.log(context,"SETUP","Станция 1",current+"\nUID 0 · boot "+boot,"Boot и ADB проверены");return true;
        }
        if(!apply&&!waiting){steps("Не применено","Ожидание","Не применено","Заводской boot. Для установки root boot нажмите «Настройка станции по шагам».");return true;}
        if(waiting)throw new IOException("После перезагрузки вернулся заводской boot. Автоматическая повторная запись отключена.");
        if(mac.isEmpty()||boot.isEmpty())throw new IOException("Не получены MAC и идентификатор загрузки Станции 1");
        steps("Выполняется","Ожидание","Ожидание","Сохраняем заводской boot на телефон перед записью.");
        require(adb.shell(Station1Plan.backupScript(BACKUP)));
        File directory=new File(context.getFilesDir(),"station1-backups");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось создать резервную папку boot");
        File backup=new File(directory,mac.replace(":","")+"-"+System.currentTimeMillis()+"-boot.img");require(adb.pull(BACKUP,backup));
        if(backup.length()!=Station1Plan.IMAGE_SIZE||!sha(backup).equals(current))throw new IOException("Резервная копия boot не совпала; запись отменена");
        if(!state.edit().putString("backup_path",backup.getAbsolutePath()).putString("backup_mac",mac).putString("backup_sha256",current).commit())throw new IOException("Не сохранён путь резервной копии boot; запись отменена");
        Session.log(context,"BACKUP","Станция 1",backup.getName()+"\nSHA-256 "+current,"Boot сохранён на телефоне");
        steps("Выполняется","Ожидание","Ожидание","Загружаем boot-root.img с сервера и проверяем SHA-256.");
        File image=download(context);require(adb.push(image,IMAGE));
        steps("Выполняется","Ожидание","Ожидание","Записываем root boot и сверяем весь раздел. После проверки Станция автоматически перезагрузится. Не отключайте питание.");
        String write=Station1Plan.writeScript();
        require(adb.shell(root(info,write)));
        if(!state.edit().putBoolean("waiting",true).putString("mac",mac).putString("old_boot",boot).putLong("time",System.currentTimeMillis()).commit())throw new IOException("Boot записан; не удалось сохранить этап перезагрузки. Автоматическая перезагрузка отменена.");
        steps("Готово","Выполняется","Ожидание","Root boot записан и проверен. Сейчас Станция перезагрузится. Не отключайте питание; дождитесь автоматического восстановления ADB.");
        Session.log(context,"SETUP","Boot Станции 1",Station1Plan.ROOT_HASH,"Записан и проверен · перезагрузка");
        Session.adbConnected=false;adb.rebootStation();return false;
    }
    private static void cleanup(Context context,NativeAdb adb,SharedPreferences state,String mac,String boot){
        String key=mac+":"+boot;
        if(key.equals(state.getString("cleanup_attempt","")))return;
        state.edit().putString("cleanup_attempt",key).apply();
        try{
            File backup=null;
            String saved=state.getString("backup_path","");
            if(mac.equals(state.getString("backup_mac",""))&&!saved.isEmpty()){
                File candidate=new File(saved),directory=new File(context.getFilesDir(),"station1-backups");
                if(candidate.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))backup=candidate;
            }
            // Compatibility with the independently verified backup made before automatic backups.
            if(backup==null)backup=new File(context.getFilesDir(),"station1-boot-before.img");
            String backupHash=state.getString("backup_sha256",Station1Plan.STOCK_HASH);
            if(backup.length()!=Station1Plan.IMAGE_SIZE||!sha(backup).equals(backupHash)){
                Session.log(context,"BACKUP","Boot Станции 1","Копия boot на телефоне не подтверждена; временный бэкап на колонке сохранён.","Очистка отложена");return;
            }
            require(adb.shell(Station1Plan.cleanupScript(backupHash)));
            Session.log(context,"BACKUP","Boot Станции 1","Временные boot-before.img и boot-root.img удалены с колонки. Проверенная резервная копия сохранена на телефоне.","Временные образы очищены");
        }catch(Exception error){Session.log(context,"BACKUP","Boot Станции 1",error.toString(),"Не удалось очистить временные образы");}
    }
    private static void requestRoot(Context context,NativeAdb adb)throws Exception{
        if(System.currentTimeMillis()-lastRootRequest<15000)return;lastRootRequest=System.currentTimeMillis();
        NativeAdb.Result result=adb.rootStation();if(result.code()!=0||result.output().contains("cannot run as root"))throw new IOException("Root ADB недоступен: "+result.output()+". Нужен рабочий su 0 до записи boot.");Session.log(context,"ADB","Станция 1",result.output(),"Запрос root ADB");Session.adbConnected=false;
    }
    static void findReturning(Context context,NativeAdb adb)throws Exception{
        if(!pending(context)||System.currentTimeMillis()-lastSearch<15000)return;lastSearch=System.currentTimeMillis();
        String expected=context.getSharedPreferences("station1",Context.MODE_PRIVATE).getString("mac","");if(expected.isEmpty())return;
        for(String ip:StationDiscovery.candidates(Session.station)){
            if(Session.stopRequested)return;
            NativeAdb.Check check;try{check=adb.check(ip);}catch(IOException unavailable){continue;}if(!check.connected())continue;
            NativeAdb.Result result=adb.shell(StationSetup.INFO);Map<String,String> info=StationSetup.parseInfo(result.output());
            if(result.code()==0&&Station1Plan.matches(info)&&expected.equals(info.get("MAC"))){Session.station=ip;Session.adb="Станция 1 найдена после перезагрузки · "+ip;return;}
        }
    }
    private static File download(Context context)throws Exception{
        File file=new File(context.getCacheDir(),"station1-boot-root.img");
        if(file.length()==Station1Plan.IMAGE_SIZE&&sha(file).equals(Station1Plan.ROOT_HASH))return file;
        URLConnection connection=new URL("http://78.24.223.83/station1/boot-root.img").openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(30000);
        try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[65536];int total=0;for(int n;(n=in.read(buffer))!=-1;){total+=n;if(total>Station1Plan.IMAGE_SIZE)throw new IOException("Неверный размер boot-root.img");out.write(buffer,0,n);}}
        if(file.length()!=Station1Plan.IMAGE_SIZE||!sha(file).equals(Station1Plan.ROOT_HASH)){file.delete();throw new IOException("Не совпал SHA-256 boot-root.img");}return file;
    }
    private static String sha(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
