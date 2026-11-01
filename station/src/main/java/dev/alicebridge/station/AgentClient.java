package dev.alicebridge.station;

import dev.alicebridge.bridge.*;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** The APK contains the public signing key and installer client, never the daemon executable. */
public final class AgentClient {
    public static final String BASE="/data/quasar/alicebridge-agent",BINARY=BASE+"/agent";
    private static final String SERVER="http://78.24.223.83/agent/";
    private final StationState state;
    private AgentRelease latest;private long checked;
    AgentClient(StationState state){this.state=state;}
    public boolean supported(){String arch=state.stationInfo.getOrDefault("ARCH","");return (arch.equals("aarch64")||arch.equals("arm64")||arch.startsWith("armv7"));}
    private String arch()throws IOException{String a=state.stationInfo.getOrDefault("ARCH","");if(a.equals("aarch64")||a.equals("arm64"))return "arm64";if(a.startsWith("armv7"))return "arm";throw new IOException("Архитектура службы не поддерживается");}
    private void requireSupport()throws IOException{if(!supported())throw new IOException("Для этой архитектуры пока нет бинарника службы. Поддерживаются ARM64 и ARMv7.");}
    private NativeAdb.Result shell(String command)throws Exception{return MaxCommands.shell(state.context,state.adbClient,command);}
    private static void require(NativeAdb.Result r)throws IOException{if(r.code()!=0)throw new IOException(r.output());}
    private JSONObject result(NativeAdb.Result r)throws Exception{require(r);JSONObject j=new JSONObject(r.output().trim());if(!j.optBoolean("ok"))throw new IOException(j.optString("error","Служба не подтвердила действие"));return j.getJSONObject("status");}
    public boolean installed()throws Exception{return shell("test -x "+BINARY).code()==0;}
    public JSONObject inspect(boolean checkUpdate)throws Exception{
        if(!supported()){state.agentStatus="Не поддерживается на этой модели";state.agentReady=false;return new JSONObject();}
        if(!installed()){state.agentStatus="Не установлена";state.agentReady=false;state.agentVersion="";return new JSONObject();}
        NativeAdb.Result status=shell(BINARY+" status");
        if(status.code()!=0){require(shell(BINARY+" start"));status=shell(BINARY+" status");}
        JSONObject info=result(status);state.agentReady=true;state.agentVersion=info.optString("version");state.agentReport=info.toString(2);
        state.agentStatus=("android".equals(info.optString("platform"))?"Установлена до перезагрузки · ":"Установлена · ")+state.agentVersion+" · ADB: "+info.optString("adb")+" · OTA: "+info.optString("ota")+" · SSH: "+info.optString("ssh");
        if(checkUpdate){try{AgentRelease r=release(false);state.agentLatest=r.payload.getString("version");state.agentUpdate=r.payload.getInt("build")>info.optInt("build");}catch(Exception failure){state.agentUpdate=false;state.agentStatus+=" · проверка обновления недоступна";}}
        return info;
    }
    public void forceReleaseCheck()throws Exception{checked=0;latest=null;inspect(true);}
    private AgentRelease release(boolean force)throws Exception{
        if(!force&&latest!=null&&System.currentTimeMillis()-checked<300000)return latest;
        byte[] data=download(SERVER+"manifest.json",65536);byte[] key;
        try(InputStream input=state.context.getAssets().open("agent/signing-public.der")){key=Streams.readBounded(input,4096);}
        latest=AgentRelease.verify(data,key);
        long appCode=state.context.getPackageManager().getPackageInfo(state.context.getPackageName(),0).getLongVersionCode();
        if(latest.payload.optLong("min_app_code",0)>appCode)throw new IOException("Для новой службы сначала обновите приложение AliceBridge");
        checked=System.currentTimeMillis();return latest;
    }
    public void install(boolean adopt)throws Exception{
        requireSupport();AgentRelease r=release(true);JSONObject a=r.artifact(arch());
        state.yseOperation="Скачиваем подписанный AliceBridge Agent "+r.payload.getString("version");
        byte[] data=download(SERVER+a.getString("file"),32*1024*1024);
        if(data.length!=a.getLong("size")||!sha(data).equals(a.getString("sha256")))throw new IOException("Размер или SHA-256 службы не совпал; установка отменена");
        if(Station1Plan.matches(state.stationInfo))state.stationWifi.prepare(state.context,state.adbClient);
        String stage="/data/local/tmp/ab-agent-install-"+UUID.randomUUID().toString().replace("-","");
        require(state.adbClient.shell("mkdir -p "+stage));
        try{
            push(data,stage+"/agent");push(r.envelope,stage+"/manifest.json");
            if(!state.stationInfo.getOrDefault("SDK","").isEmpty()&&!Station1Plan.matches(state.stationInfo)) {
                byte[] helper;
                try(InputStream in=state.context.getAssets().open("station1/wifi-helper.jar")){helper=Streams.readBounded(in,256*1024);}
                push(helper,stage+"/wifi-helper.jar");
                require(shell("mkdir -p /data/quasar/alicebridge-home-wifi && chmod 700 /data/quasar/alicebridge-home-wifi && cp "+stage+"/wifi-helper.jar /data/quasar/alicebridge-home-wifi/helper.jar && chmod 600 /data/quasar/alicebridge-home-wifi/helper.jar"));
            }
            String hashCommand=Station1Plan.matches(state.stationInfo)?"/system/bin/busybox-smp sha256sum":"sha256sum";
            String awk=Station1Plan.matches(state.stationInfo)?"/system/bin/busybox-smp awk":"awk";
            String script="set -e\nchmod 700 "+stage+" "+stage+"/agent\nchmod 600 "+stage+"/manifest.json\n[ \"$("+hashCommand+" "+stage+"/agent | "+awk+" '{print $1}')\" = "+a.getString("sha256")+" ]\n"+stage+"/agent install --manifest "+stage+"/manifest.json";
            state.yseOperation="Проверяем бинарник, настраиваем автозапуск и проверяем ответ службы";
            NativeAdb.Result installed=shell(script);require(installed);if(!installed.output().contains("AB_AGENT_INSTALLED"))throw new IOException("Установка службы не подтверждена");
            JSONObject initial=inspect(false);
            if(adopt&&!initial.optBoolean("home_configured")){
                for(int attempt=0;attempt<12;attempt++){
                    try{rpc(new JSONObject().put("method","adopt"));break;}
                    catch(Exception unavailable){
                        String message=unavailable.getMessage()==null?"":unavailable.getMessage();
                        if(attempt<11&&(message.contains("operation pending")||message.contains("maintenance"))){Thread.sleep(500);continue;}
                        state.log(state.context,"СЛУЖБА","Wi-Fi","Не удалось сохранить текущую сеть. Укажите домашний Wi-Fi через приложение; пароль не записывается в журнал.","Требуется настройка");break;
                    }
                }
            }
            inspect(true);state.log(state.context,"СЛУЖБА","AliceBridge Agent",state.agentStatus,"Подпись и запуск проверены");
        }finally{try{state.adbClient.shell("rm -rf "+stage);}catch(Exception ignored){}}
    }
    public JSONObject rpc(JSONObject request)throws Exception{
        String remote="/data/local/tmp/ab-agent-rpc-"+UUID.randomUUID().toString().replace("-","");
        try{push(request.toString().getBytes(StandardCharsets.UTF_8),remote);return result(shell("chmod 600 "+remote+"; "+BINARY+" rpc < "+remote));}
        finally{try{state.adbClient.shell("rm -f "+remote);}catch(Exception ignored){}}
    }
    public String beginWifi(String ssid,String password,String id)throws Exception{
        requireSupport();if(!installed())throw new IOException("Сначала установите AliceBridge Agent в панели колонки: он сохранит сеть и обеспечит откат.");
        String[] credentials=HomeWifi.credentials(ssid,password).split("\n");if(!id.matches("[a-f0-9]{32}"))throw new IOException("Неверный идентификатор смены сети");state.agentTransaction=id;
        JSONObject reply=rpc(new JSONObject().put("method","wifi.begin").put("id",id).put("network",new JSONObject().put("ssid_hex",credentials[0]).put("psk",credentials[1])));
        JSONObject transaction=reply.optJSONObject("transaction");if(transaction==null||!id.equals(transaction.optString("id")))throw new IOException("Служба не подтвердила начало смены Wi-Fi");
        state.agentTransaction=id;return "Служба меняет сеть. Точка останется включённой до подтверждения ADB; без подтверждения служба вернёт предыдущую сеть.";
    }
    public boolean confirmWifi()throws Exception{
        JSONObject status=inspect(false),tx=status.optJSONObject("transaction");if(tx==null)return false;
        String id=tx.optString("id");if(state.agentTransaction.isEmpty()||!state.agentTransaction.equals(id))return false;
        if(!tx.optString("phase").equals("committed"))status=rpc(new JSONObject().put("method","wifi.confirm").put("id",id));
        tx=status.optJSONObject("transaction");return tx!=null&&tx.optString("phase").equals("committed");
    }
    public void rollback()throws Exception{rpc(new JSONObject().put("method","wifi.rollback"));inspect(false);}
    public void remove()throws Exception{require(shell(BINARY+" uninstall"));if(installed())throw new IOException("Удаление службы не подтверждено");state.agentReady=false;state.agentUpdate=false;state.agentVersion="";state.agentReport="";state.agentStatus="Служба удалена; ADB, SSH и прежняя защита OTA не удалялись";}
    private void push(byte[] data,String target)throws Exception{File f=File.createTempFile("agent-transfer-",".tmp",state.context.getCacheDir());try{try(OutputStream out=new FileOutputStream(f)){out.write(data);}require(state.adbClient.push(f,target));}finally{f.delete();}}
    private static byte[] download(String url,int max)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);
        try{if(c.getResponseCode()!=200)throw new IOException("Загрузка службы: HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream()){return Streams.readBounded(in,max);}}finally{c.disconnect();}
    }
    private static String sha(byte[] bytes)throws Exception{byte[] sum=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:sum)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
