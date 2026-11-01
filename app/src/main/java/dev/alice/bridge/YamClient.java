package dev.alice.bridge;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** YAM API through our station-specific ADB forward, independent of Entware. */
final class YamClient {
    static final String BASE="/data/quazar/yam",REGISTRY="https://raw.githubusercontent.com/nyakokitsu/yapkg/registry/index.json";
    final Context context;final NativeAdb adb;final String host;private int port;private String base=BASE;
    java.util.function.Consumer<String> progress=value->{};
    YamClient(Context c)throws Exception{
        context=c.getApplicationContext();host=Session.station;
        if(host.isEmpty()||!Session.adbConnected)throw new IOException("Сначала подключитесь к Алисе по ADB");
        adb=new NativeAdb(context);NativeAdb.Check check=adb.check(host);if(!check.connected())throw new IOException(check.status());
    }
    boolean connect()throws Exception{
        if(MaxPlan.matches(Session.stationInfo)){
            if(!MaxRootBridge.ready(host))throw new IOException("Сначала получите root Макс в панели управления");
            NativeAdb.Result probe=shell("if [ -x /data/quazar/yam/bin/yamd ]; then echo AB_YAM_APP; elif [ -x /data/yam/yamd ]; then echo AB_YAM_LEGACY; fi");require(probe);
            if(probe.output().contains("AB_YAM_APP"))base=BASE;else if(probe.output().contains("AB_YAM_LEGACY"))base="/data/yam";else return false;
        }else{NativeAdb.Result exists=shell("test -x "+BASE+"/bin/yamd");if(exists.code()!=0)return false;}
        port=adb.forwardYam();
        try{request("GET","/api/plugins",null,null);return true;}catch(IOException down){
            require(shell(MaxPlan.matches(Session.stationInfo)?MaxYam.start(base):"test -f "+BASE+"/start.sh && sh "+BASE+"/start.sh"));
            for(int i=0;i<12;i++){Thread.sleep(400);try{request("GET","/api/plugins",null,null);return true;}catch(IOException retry){if(i==11)throw retry;}}
        }return false;
    }
    void install()throws Exception{
        if(Station1Plan.matches(Session.stationInfo)&&!Station1Setup.otaProtected(context))throw new IOException("Сначала завершите проверку root и блокировку OTA Станции 1 в настройке по шагам");
        if(!"aarch64".equals(Session.stationInfo.getOrDefault("ARCH",Session.stationInfo.getOrDefault("MODEL",""))))throw new IOException("Эта сборка YAM поддерживает только AArch64");
        boolean max=MaxPlan.matches(Session.stationInfo);
        if(max&&!MaxRootBridge.ready(host))throw new IOException("Сначала получите root Макс в панели управления");
        base=BASE;progress.accept("Подготавливаем окружение YAM…");
        if(Station1Plan.matches(Session.stationInfo))try(InputStream in=context.getAssets().open("scripts/station1-environment.sh")){require(shell(new String(Streams.readBounded(in,32768),StandardCharsets.UTF_8)));}
        if(!max)Station1Dns.ensure(context,adb);
        JSONObject release=new JSONObject(asset("yam/release.json"));File binary=File.createTempFile("yam-daemon-",".bin",context.getCacheDir());
        try{
            try(InputStream in=context.getAssets().open("yam/yamd");OutputStream out=new FileOutputStream(binary)){copy(in,out);}
            if(binary.length()!=release.getLong("size")||!sha(binary).equals(release.getString("sha256")))throw new IOException("Повреждён встроенный бинарник YAM");
            require(shell("mkdir -p "+BASE+"/bin "+BASE+"/data"));progress.accept("Передаём YAM на колонку — 10 МиБ. На Станции 1 это может занять несколько минут.");if(max){
                String remote=MaxPlan.BASE+"/yamd-"+UUID.randomUUID().toString().replace("-","")+".new";
                try{require(adb.push(binary,remote));require(shell("mv "+RootShell.quote(remote)+" "+BASE+"/bin/yamd.new"));}
                finally{shell("rm -f "+RootShell.quote(remote));}
            }else require(adb.push(binary,BASE+"/bin/yamd.new"));
            progress.accept(max?"Запускаем YAM через root Макс…":"Настраиваем YAM и его запуск при загрузке…");
            String script=asset(max?"scripts/install-yam-max.sh":"scripts/install-yam.sh").replace("__SHA256__",release.getString("sha256")).replace("__VERSION__",release.getString("version"));require(shell(script));
            progress.accept("Проверяем API YAM через ADB…");
            try{if(!connect())throw new IOException("YAM не запустился");}
            catch(Exception failure){shell("if [ -f "+BASE+"/yamd.pid ]; then p=$(cat "+BASE+"/yamd.pid); case \"$p\" in ''|*[!0-9]*) exit 1;; esac; [ \"$(readlink /proc/$p/exe)\" != "+BASE+"/bin/yamd ] || kill \"$p\"; fi");throw failure;}
            Session.log(context,"YAM",host,"Установлен YAM "+release.getString("version")+". Штатное автообновление YAM сохранено; установка новых пакетов только по кнопке.","Готово");
        }finally{binary.delete();}
    }
    JSONArray installed()throws Exception{return new JSONArray(request("GET","/api/plugins",null,null));}
    JSONArray catalog()throws Exception{
        JSONObject index=new JSONObject(downloadText(REGISTRY,4*1024*1024));JSONArray items=index.getJSONArray("packages"),out=new JSONArray();
        for(int i=0;i<items.length();i++){JSONObject x=items.getJSONObject(i);x.put("kind","binary");out.put(x);}
        JSONArray examples=new JSONObject(asset("yam/plugins.json")).getJSONArray("plugins");for(int i=0;i<examples.length();i++)out.put(examples.getJSONObject(i));return out;
    }
    void installPackage(JSONObject item)throws Exception{
        File archive=File.createTempFile("yam-package-",".yap",context.getCacheDir());
        try{
            JSONArray files=item.optJSONArray("files");
            if(files!=null){try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(archive))){for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String path=f.getString("path");if(!path.equals("plugin.toml")&&!path.equals("main.lua"))throw new IOException("Неизвестный файл плагина");byte[] data=download(f.getString("url"),2*1024*1024);if(!sha(data).equals(f.getString("sha256")))throw new IOException("Не совпал SHA-256 плагина");zip.putNextEntry(new ZipEntry(path));zip.write(data);zip.closeEntry();}}}
            else{downloadFile(item.getString("url"),archive,96*1024*1024);if(!sha(archive).equals(item.getString("sha256")))throw new IOException("Не совпал SHA-256 пакета");}
            upload(archive);Session.log(context,"YAM",item.getString("id"),"Пакет установлен через YAM API; SHA-256 проверен.","Готово");
        }finally{archive.delete();}
    }
    void upload(File file)throws Exception{
        String boundary="ABYAM"+UUID.randomUUID().toString().replace("-","");
        HttpURLConnection c=open("POST","/api/plugins");c.setDoOutput(true);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        byte[] begin=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"package\"; filename=\"package.yap\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8),end=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode((long)begin.length+file.length()+end.length);
        try{try(OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(file)){out.write(begin);copy(in,out);out.write(end);}response(c);}finally{c.disconnect();}
    }
    void action(String id,String action,boolean binary)throws Exception{
        YamPaths.id(id);
        if(!Set.of("toggle","invoke","service/start","stop","delete").contains(action))throw new IOException("Неизвестное действие YAM");
        if(binary&&!action.equals("delete")&&!action.equals("stop")){JSONObject settings=new JSONObject(request("GET","/api/settings",null,null));if(!settings.optBoolean("allow_plugin_binaries")){settings.put("allow_plugin_binaries",true);request("PUT","/api/settings",settings.toString().getBytes(StandardCharsets.UTF_8),"application/json");}}
        request(action.equals("delete")?"DELETE":"POST","/api/plugins/"+id+(action.equals("delete")?"":"/"+action),null,null);
        Session.log(context,"YAM",id,action,"Выполнено");
    }
    String terminal(String id)throws Exception{
        YamPaths.id(id);JSONArray installed=installed();JSONObject item=null;for(int i=0;i<installed.length();i++)if(installed.getJSONObject(i).getJSONObject("manifest").getString("id").equals(id))item=installed.getJSONObject(i);
        if(item==null||!item.optBoolean("enabled")||!item.optBoolean("compatible",true))throw new IOException("Пакет не включён или несовместим с колонкой");
        JSONObject m=item.getJSONObject("manifest"),l=m.getJSONObject("launch");if(!l.optString("kind").equals("binary")||!l.optString("mode").equals("oneshot"))throw new IOException("Терминал доступен для бинарных oneshot-пакетов");
        if(!new JSONObject(request("GET","/api/settings",null,null)).optBoolean("allow_plugin_binaries"))throw new IOException("Сначала включите пакет, разрешив запуск бинарников");
        List<String> args=new ArrayList<>(),libs=new ArrayList<>();JSONArray a=l.optJSONArray("args"),p=l.optJSONArray("library_paths");if(a!=null)for(int i=0;i<a.length();i++)args.add(a.getString(i));if(p!=null)for(int i=0;i<p.length();i++)libs.add(p.getString(i));
        return YamPaths.terminal(id,item.optString("directory",base+"/data/plugins/"+id),l.optString("path").isEmpty()?m.optString("entry"):l.optString("path"),l.optString("interpreter"),args,libs);
    }
    String logs(String id)throws Exception{YamPaths.id(id);return request("GET","/api/plugins/"+id+"/logs",null,null);}
    private NativeAdb.Result shell(String script)throws IOException,InterruptedException{
        if(MaxPlan.matches(Session.stationInfo)){
            if(!MaxRootBridge.ready(host))throw new IOException("Root-сессия Макс недоступна");
            return MaxCommands.shell(context,adb,script);
        }
        return adb.shell(script);
    }
    private HttpURLConnection open(String method,String path)throws IOException{
        if(!host.equals(Session.station))throw new IOException("Выбрана другая колонка. Откройте YAM заново.");
        if(port==0)throw new IOException("Нет ADB-перенаправления YAM");
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(90000);c.setRequestMethod(method);return c;
    }
    private String request(String method,String path,byte[] bytes,String contentType)throws IOException{HttpURLConnection c=open(method,path);try{if(bytes!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type",contentType);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}return response(c);}finally{c.disconnect();}}
    private static String response(HttpURLConnection c)throws IOException{int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();String body="";if(in!=null)try(in){body=new String(Streams.readBounded(in,8*1024*1024),StandardCharsets.UTF_8);}if(code<200||code>=300)throw new IOException("YAM HTTP "+code+": "+body);return body;}
    private String asset(String path)throws IOException{try(InputStream in=context.getAssets().open(path)){return new String(Streams.readBounded(in,131072),StandardCharsets.UTF_8);}}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}
    private static void downloadFile(String url,File file,int max)throws IOException{if(!url.startsWith("https://"))throw new IOException("Источник должен использовать HTTPS");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(60000);try{if(c.getResponseCode()!=200)throw new IOException("Загрузка: HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(file)){byte[] b=new byte[65536];long total=0;for(int n;(n=in.read(b))!=-1;){total+=n;if(total>max)throw new IOException("Архив больше 96 МиБ");out.write(b,0,n);}}}finally{c.disconnect();}}
    private static byte[] download(String url,int max)throws IOException{if(!url.startsWith("https://"))throw new IOException("Источник пакета должен использовать HTTPS");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(60000);try{int code=c.getResponseCode();if(code!=200)throw new IOException("Загрузка: HTTP "+code);try(InputStream in=c.getInputStream()){return Streams.readBounded(in,max);}}finally{c.disconnect();}}
    private static String downloadText(String url,int max)throws IOException{return new String(download(url,max),StandardCharsets.UTF_8);}
    private static void require(NativeAdb.Result r)throws IOException{if(r.code()!=0)throw new IOException(r.output());}
    private static String sha(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}return hex(md.digest());}
    private static String sha(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
