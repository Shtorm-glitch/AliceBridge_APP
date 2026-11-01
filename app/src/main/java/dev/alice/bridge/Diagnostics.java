package dev.alice.bridge;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** A single portable report. No configuration stores, keys, backup images or credentials are copied. */
final class Diagnostics {
    static JSONObject state(Context c)throws JSONException{
        JSONObject j=new JSONObject();j.put("time",System.currentTimeMillis());j.put("process",android.os.Process.myPid());
        j.put("phase",Session.phase.name());j.put("station",Session.station);j.put("station_info",new JSONObject(Session.stationInfo));
        j.put("adb_connected",Session.adbConnected);j.put("adb_attempts",Session.adbAttempts);j.put("last_adb_attempt",Session.lastAdbAttempt);j.put("adb",Session.adb);
        j.put("running",Session.running);j.put("busy",Session.busy);j.put("flow_active",Session.flowActive);j.put("direct_mode",Session.directMode);j.put("stop_requested",Session.stopRequested);
        j.put("root",Session.root);j.put("network",Session.network);j.put("hotspot",Session.hotspot);j.put("problem",Session.problem);
        j.put("backend",Session.backend);j.put("backend_error",Session.backendError);j.put("backend_payload",Session.backendPayload);
        j.put("station_info_error",Session.stationInfoError);j.put("wifi_status",Session.wifiStatus);j.put("home_ssid",Session.homeSsid);
        j.put("wifi_pending",Session.wifiSwitchPending);j.put("wifi_done",Session.wifiDone);j.put("wifi_editing",Session.wifiEditing);j.put("wifi_searching",Session.wifiSearching);j.put("wifi_search_attempts",Session.wifiSearchAttempts);j.put("wifi_wait_started_elapsed",Session.wifiWaitStarted);
        j.put("discovery_running",Session.discoveryRunning);j.put("discovery_status",Session.discoveryStatus);j.put("discovered_stations",new JSONObject(Session.discoveredStations));
        j.put("tracker",new JSONObject(Session.tracker.diagnostics()));j.put("stages",new JSONObject(Session.stageSnapshot()));j.put("domains",new JSONObject(Session.domainSnapshot()));
        JSONArray steps=new JSONArray();for(StationSetup.Step step:Session.setupSteps){JSONObject v=new JSONObject();v.put("id",step.id());v.put("title",step.title());v.put("status",step.status());v.put("output",step.output());steps.put(v);}j.put("setup_steps",steps);
        j.put("yse",Session.yseStatus);j.put("yse_operation",Session.yseOperation);j.put("ssh",Session.sshStatus);j.put("zsh",Session.zshStatus);
        SharedPreferences p=c.getSharedPreferences("config",0);JSONObject settings=new JSONObject();
        for(String key:new String[]{"resolver","last_station","last_station_mac","home_ssid","hotspot_ssid","hotspot_security","workflow_boot","reboot_required"})if(p.contains(key))settings.put(key,p.getAll().get(key));
        j.put("apkg",Session.apkgStatus);j.put("max_root_bridge_ready",MaxRootBridge.ready(Session.station));j.put("settings",settings);JSONObject boot=new JSONObject(c.getSharedPreferences("station1",0).getAll());j.put("station1_reboot_state",boot);
        return j;
    }
    private static long lastCheckpoint;
    private static boolean processCheckpoint,crashHandlerInstalled;
    static synchronized void installCrashHandler(Context c){
        if(crashHandlerInstalled)return;crashHandlerInstalled=true;
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Session.log(c,"APP","AliceBridge","Запуск процесса pid="+android.os.Process.myPid()+"; Android "+Build.VERSION.RELEASE+"; модель="+Build.MODEL,"Начало процесса");
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));Session.log(c,"CRASH",thread.getName(),trace.toString(),"Необработанная ошибка");}catch(Throwable ignored){}
            if(previous!=null)previous.uncaughtException(thread,error);
        });
    }
    static void checkpoint(Context c){
        long now=SystemClock.elapsedRealtime();if(lastCheckpoint!=0&&now-lastCheckpoint<3000)return;lastCheckpoint=now;
        if(!processCheckpoint){processCheckpoint=true;try{File old=new File(c.getFilesDir(),"diagnostic-state.json");if(old.exists())java.nio.file.Files.copy(old.toPath(),new File(c.getFilesDir(),"diagnostic-previous-process.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(Exception ignored){}}
        try{android.util.AtomicFile file=new android.util.AtomicFile(new File(c.getFilesDir(),"diagnostic-state.json"));FileOutputStream out=null;try{out=file.startWrite();out.write(state(c).toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);}}catch(Exception ignored){}
    }
    static void write(Context c,OutputStream destination)throws Exception{
        long started=System.currentTimeMillis();List<String> secrets=new ArrayList<>();
        for(Map.Entry<String,?> e:c.getSharedPreferences("config",0).getAll().entrySet())if(DiagnosticRedactor.secretKey(e.getKey())&&e.getValue() instanceof String)secrets.add((String)e.getValue());
        DiagnosticRedactor redact=new DiagnosticRedactor(secrets);
        try(DiagnosticArchive zip=new DiagnosticArchive(destination,redact)){
            put(zip,"README.txt","AliceBridge: полный отчёт диагностики\nСоздан: "+new Date(started)+"\nВремя в events.jsonl — Unix milliseconds. Все накопленные события приложения экспортируются, включая подробный ADB.\nФайл включает локальные IP, MAC, имена Wi-Fi, сведения о прошивках и сетевой конфигурации; передавайте его разработчику. Пароли, токены и ключи исключены.\nСнимки собираются последовательно, время указано в каждом разделе. Недоступный root/ADB или тайм-аут отмечаются в файле и не отменяют экспорт.\nstate-current.json — текущее состояние процесса; state-last-recorded.json — последний сохранённый снимок (важен после перезапуска).\nСервер: включён последний полученный статус через DNS; полного журнала удалённого сервера в приложении нет.\nСеть не изменяется, поиск по подсети и переподключение ADB при экспорте не запускаются.\n",redact);
            JSONObject app=new JSONObject();app.put("version",c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName);app.put("code",c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode());app.put("android",Build.VERSION.RELEASE);app.put("sdk",Build.VERSION.SDK_INT);app.put("manufacturer",Build.MANUFACTURER);app.put("model",Build.MODEL);app.put("device",Build.DEVICE);app.put("build",Build.DISPLAY);app.put("security_patch",Build.VERSION.SECURITY_PATCH);app.put("supported_abis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));app.put("elapsed_ms",SystemClock.elapsedRealtime());app.put("timezone",TimeZone.getDefault().getID());app.put("captured",System.currentTimeMillis());
            put(zip,"phone-app.json",app.toString(2),redact);put(zip,"state-current.json",state(c).toString(2),redact);
            File saved=new File(c.getFilesDir(),"diagnostic-state.json");if(saved.exists())put(zip,"state-last-recorded.json",read(saved,2*1024*1024),redact);
            File previous=new File(c.getFilesDir(),"diagnostic-previous-process.json");if(previous.exists())put(zip,"state-previous-process.json",read(previous,2*1024*1024),redact);
            File journal=new File(c.getFilesDir(),"session.jsonl");long length=zip.journal(journal);
            put(zip,"phone-shizuku-network.json",ShizukuNetwork.lastStatus,redact);
            put(zip,"phone-network.txt",command(c,new String[]{"sh","-c","date; uname -a; ip -4 addr; ip -4 route show table all; ip neigh; cat /proc/net/arp; cmd wifi status"},8),redact);
            if(ShizukuNetwork.selected(c)) put(zip,"phone-root-network.txt","Режим Shizuku: root телефона не запрашивается. См. phone-shizuku-network.json",redact);
            else put(zip,"phone-root-network.txt",command(c,new String[]{"su","-c","id; cmd wifi status; ip -4 addr; ip -4 route show table all; ip rule; ip neigh; cat /proc/net/arp; iptables -w 2 -t nat -S; iptables -w 2 -S; cat /data/misc/dhcp/dnsmasq.leases 2>/dev/null; cat /data/misc/apexdata/com.android.tethering/dnsmasq.leases 2>/dev/null"},12),redact);
            if(ShizukuNetwork.selected(c)) put(zip,"phone-tethering.txt","Режим Shizuku: root телефона не запрашивается. См. phone-shizuku-network.json",redact);
            else put(zip,"phone-tethering.txt",command(c,new String[]{"su","-c","dumpsys tethering"},8),redact);
            if(ShizukuNetwork.selected(c)) put(zip,"phone-connectivity.txt","Режим Shizuku: root телефона не запрашивается. См. phone-shizuku-network.json",redact);
            else put(zip,"phone-connectivity.txt",command(c,new String[]{"su","-c","dumpsys connectivity"},6),redact);
            put(zip,"app-logcat.txt",command(c,new String[]{"logcat","-d","-v","threadtime","-t","1500","--pid="+android.os.Process.myPid()},6),redact);
            put(zip,"app-crashes.txt",command(c,new String[]{"logcat","-b","crash","-d","-v","threadtime","-t","200","--pid="+android.os.Process.myPid()},6),redact);
            try{NativeAdb adb=new NativeAdb(c);put(zip,"adb-and-station.txt",adb.diagnosticSnapshot(),redact);}catch(Exception e){put(zip,"adb-and-station.txt","Недоступно: "+e.getClass().getSimpleName(),redact);}
            put(zip,"collection.txt","Начало: "+started+"\nКонец: "+System.currentTimeMillis()+"\nРазмер журнала на начало копирования: "+length+" байт\n",redact);
        }
    }
    private static void put(DiagnosticArchive zip,String name,String text,DiagnosticRedactor redact)throws IOException{zip.put(name,text);}
    private static String read(File file,int max)throws IOException{try(InputStream input=new FileInputStream(file)){return new String(Streams.readBounded(input,max),StandardCharsets.UTF_8);}}
    private static String command(Context c,String[] args,int seconds){
        long start=System.currentTimeMillis();File output=null;java.lang.Process process=null;
        try{output=File.createTempFile("ab-diagnostic-",".txt",c.getCacheDir());process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start();if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroyForcibly();return "Время: "+start+"\nТайм-аут "+seconds+" с\n"+read(output,1024*1024);}return "Время: "+start+"\nКод: "+process.exitValue()+"\n"+read(output,1024*1024);}catch(Exception e){return "Время: "+start+"\nНедоступно: "+e.getClass().getSimpleName();}finally{if(process!=null)process.destroyForcibly();if(output!=null)output.delete();}
    }
}
