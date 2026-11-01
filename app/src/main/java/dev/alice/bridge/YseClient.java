package dev.alice.bridge;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Calls the station's YSE CLI over app-owned ADB; long jobs survive UI reconnection. */
public final class YseClient {
    private static final String BIN="/data/opt/bin/yse",JOBS="/data/quasar/alicebridge-yse";
    private static String checkedCli="";
    public static void inspect(NativeAdb adb) throws IOException,InterruptedException {
        NativeAdb.Result state=adb.shell("test -x "+BIN+" && test -x /opt/bin/curl && test -x /opt/bin/opkg && /opt/bin/opkg list-installed | grep -q '^libpthread '");
        Session.yseReady=state.code()==0;
        Session.yseStatus=Session.yseReady?"YSE и Entware установлены":"YSE и Entware не установлены · установка по кнопке";
    }
    public static void ensure(Context context,NativeAdb adb) throws Exception {
        Session.yseStatus="Устанавливаем YSE и Entware";Session.yseReady=false;
        if(Station1Plan.matches(Session.stationInfo)){
            try(InputStream in=context.getAssets().open("scripts/station1-environment.sh")){require(adb.shell(new String(Streams.readBounded(in,32768),StandardCharsets.UTF_8)));}
        }
        File file=new File(context.getCacheDir(),"yse");
        try(InputStream in=context.getAssets().open("yse/yse");OutputStream out=new FileOutputStream(file)){out.write(Streams.readBounded(in,131072));}
        require(adb.shell("""
            set -eu
            mkdir -p /data/opt/bin /data/quasar/alicebridge-yse
            """));require(adb.push(file,BIN+".new"));
        require(adb.shell("chmod 755 "+BIN+".new && mv "+BIN+".new "+BIN));
        preserveOtaCache(context,adb);
        String offline=EntwareBootstrap.prepare(context,adb);
        execute(context,adb,"bootstrap",null,offline);
        require(adb.shell("set -e\n/opt/bin/opkg list-installed | grep -q '^libpthread '\n[ -x /opt/bin/curl ]\n"+BIN+" version"));
        Session.yseReady=true;Session.yseStatus="Entware готов · YSE";
        Session.log(context,"YSE",adb.target(),"YSE установлен; Entware проверен","Готово");
    }
    private static void preserveOtaCache(Context context,NativeAdb adb) throws Exception {
        NativeAdb.Result free=adb.shell("df -Pk /data | tail -n 1 | awk '{print $4}'");require(free);
        long available;try{available=Long.parseLong(free.output().trim());}catch(NumberFormatException invalid){return;}
        if(available>32*1024)return;
        NativeAdb.Result files=adb.shell("for f in /bkupgrade/update_*.img; do [ ! -f \"$f\" ] || basename \"$f\"; done");require(files);
        File directory=new File(context.getFilesDir(),"firmware-backups");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось создать резервную копию OTA");
        for(String name:files.output().split("\\R")){
            if(!name.matches("update_[0-9.]+\\.img"))continue;
            String remote="/bkupgrade/"+name;Session.yseStatus="Сохраняем остаточный образ OTA на телефон";
            NativeAdb.Result sum=adb.shell("sha256sum "+RootShell.quote(remote)+" | awk '{print $1}'");require(sum);
            String expected=sum.output().trim();if(!expected.matches("[a-f0-9]{64}"))throw new IOException("Не удалось проверить образ OTA");
            File copy=new File(directory,name);require(adb.pull(remote,copy,300));
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(copy)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}
            StringBuilder actual=new StringBuilder();for(byte value:digest.digest())actual.append(String.format(Locale.ROOT,"%02x",value&255));
            if(!actual.toString().equals(expected))throw new IOException("Резервная копия OTA не совпала; файл на колонке сохранён");
            require(adb.shell("set -e\n[ \"$(sha256sum "+RootShell.quote(remote)+" | awk '{print $1}')\" = "+expected+" ]\nrm "+RootShell.quote(remote)+"\nsync"));
            Session.log(context,"YSE","Место для пакетов",name+" сохранён на телефоне; SHA256 совпал. Остаточный OTA удалён с колонки.","Готово");
        }
    }
    public static String command(Context context,String verb,String name) throws Exception {
        if(!Session.adbConnected||!Session.yseReady)throw new IOException("Дождитесь подключения YSE");
        NativeAdb adb=new NativeAdb(context);NativeAdb.Check check=adb.check(Session.station);
        if(!check.connected())throw new IOException(check.status());
        refreshCli(context,adb);
        return execute(context,adb,verb,name);
    }
    /** Upgrade our CLI fixes for existing installations without reinstalling Entware or packages. */
    private static synchronized void refreshCli(Context context,NativeAdb adb)throws Exception{
        byte[] bytes;try(InputStream in=context.getAssets().open("yse/yse")){bytes=Streams.readBounded(in,131072);}
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sum=new StringBuilder();for(byte value:digest)sum.append(String.format(Locale.ROOT,"%02x",value&255));
        String key=adb.target()+":"+Session.stationInfo.getOrDefault("BOOT","")+":"+sum;
        if(key.equals(checkedCli))return;
        NativeAdb.Result same=adb.shell("test -x "+BIN+" && [ \"$(sha256sum "+BIN+" | awk '{print $1}')\" = "+sum+" ]");
        if(same.code()!=0){
            File file=File.createTempFile("yse-cli-",".sh",context.getCacheDir());
            try{try(OutputStream out=new FileOutputStream(file)){out.write(bytes);}require(adb.push(file,BIN+".new"));
                require(adb.shell("set -e\n[ \"$(sha256sum "+BIN+".new | awk '{print $1}')\" = "+sum+" ]\nchmod 755 "+BIN+".new\nmv "+BIN+".new "+BIN));
            }finally{file.delete();}
            Session.log(context,"YSE",adb.target(),"Обновлён встроенный установщик YAP; пакеты и Entware сохранены.","Исправления установщика применены");
        }
        checkedCli=key;
    }
    static void installZsh(Context context,NativeAdb adb)throws Exception{
        if(!Session.yseReady)throw new IOException("Сначала установите YSE и Entware отдельной кнопкой");
        Session.zshStatus="Проверяем место для Zsh";
        preserveOtaCache(context,adb);
        Session.zshStatus="Передаём Zsh из APK";
        EntwareBootstrap.Payload payload=EntwareBootstrap.stage(context,adb,"zsh");
        Session.zshStatus="Устанавливаем Zsh и настраиваем тему adam1";
        execute(context,adb,"zsh",null,"sh "+payload.base()+"/install.sh "+payload.base()+" "+payload.arch());
        inspectZsh(adb);
        if(!Session.zshReady)throw new IOException("Не удалось подтвердить оболочку Zsh");
        Session.zshRevision++;
    }
    static void inspectZsh(NativeAdb adb)throws IOException,InterruptedException{
        NativeAdb.Result state=adb.shell("""
            test -x /opt/bin/zsh && test -x /data/opt/bin/alicebridge-login && test -f /data/quasar/alicebridge-zsh/configured || exit 1
            ab_shell=$(awk -F: '$1=="root"{print $7}' /etc/passwd)
            case "$ab_shell" in /usr/bin/alicebridge-login|/system/bin/alicebridge-login|/data/opt/bin/alicebridge-login) test -x "$ab_shell";; *) exit 1;; esac
            """);
        Session.zshReady=state.code()==0;Session.zshStatus=Session.zshReady?"Zsh готов · тема adam1 · оболочка по умолчанию":"Zsh · установка по кнопке";
    }
    private static String execute(Context context,NativeAdb adb,String verb,String name) throws Exception {return execute(context,adb,verb,name,"");}
    private static String execute(Context context,NativeAdb adb,String verb,String name,String offline) throws Exception {
        if(Set.of("bootstrap","update","catalog","install","info","ent-update","ent-catalog").contains(verb))Station1Dns.ensure(context,adb);
        if(!Set.of("bootstrap","update","list","catalog","info","install","remove","zsh","ent-update","ent-catalog","ent-list").contains(verb))throw new IOException("Неизвестная команда YSE");
        if(Set.of("info","install","remove").contains(verb)&&(name==null||!name.matches("(?:(?:ent|yap):)?[a-zA-Z0-9][a-zA-Z0-9+_.-]{0,100}")||name.contains("..")))throw new IOException("Недопустимое имя пакета");
        String id=UUID.randomUUID().toString().replace("-",""),base=JOBS+"/"+id;
        String command=BIN+" "+(verb.equals("catalog")?"list -a":verb)+(name==null?"":" "+RootShell.quote(name));
        if(verb.equals("ent-update"))command="/opt/bin/opkg update";
        if(verb.equals("ent-catalog"))command="/opt/bin/opkg list";
        if(verb.equals("ent-list"))command="/opt/bin/opkg list-installed";
        if(verb.equals("bootstrap"))command=offline+"YSE_OFFLINE=1 "+command;
        if(verb.equals("zsh"))command=offline;
        String job="#!/bin/sh\nexec > "+base+".log 2>&1\nexport HOME=/data/opt/home\nmkdir -p \"$HOME\"\n"+
            "if ! mkdir "+JOBS+"/lock 2>/dev/null; then echo 'Другая операция YSE ещё выполняется'; echo 75 > "+base+".exit; exit; fi\n"+
            "printf '%s\n' \"$$\" > "+JOBS+"/lock/pid\ncat /proc/sys/kernel/random/boot_id > "+JOBS+"/lock/boot\n"+
            "trap 'rm -f "+JOBS+"/lock/pid "+JOBS+"/lock/boot; rmdir "+JOBS+"/lock' EXIT\n"+command+"\nrc=$?\nprintf '%s\n' \"$rc\" > "+base+".exit.new\nmv "+base+".exit.new "+base+".exit\n";
        require(adb.shell("mkdir -p "+JOBS+"\ncat > "+base+".sh <<'AB_YSE_JOB'\n"+job+"AB_YSE_JOB\nif command -v start-stop-daemon >/dev/null 2>&1; then\nstart-stop-daemon --start --background --make-pidfile --pidfile "+base+".pid --startas /bin/sh -- "+base+".sh\nelif command -v setsid >/dev/null 2>&1; then\nsetsid /bin/sh "+base+".sh < /dev/null > /dev/null 2>&1 &\necho $! > "+base+".pid\nelse echo 'Нет start-stop-daemon или setsid для фоновой задачи'; exit 1\nfi\n"));
        long deadline=System.currentTimeMillis()+15*60*1000;
        while(System.currentTimeMillis()<deadline){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            NativeAdb.Result state=adb.shell("if [ -f "+base+".exit ]; then cat "+base+".exit; else echo RUNNING; fi\ntail -c 900 "+base+".log 2>/dev/null || true");require(state);
            YseJob.State task=YseJob.parse(state.output());Session.yseOperation=task.log().isEmpty()?"Выполняем команду YSE…":task.log();
            if(!task.running()){
                File output=File.createTempFile("yse-result-",".log",context.getCacheDir());String text;
                try{require(adb.pull(base+".log",output));try(InputStream in=new FileInputStream(output)){text=new String(Streams.readBounded(in,4*1024*1024),StandardCharsets.UTF_8);}}finally{output.delete();}
                if(task.code()!=0||Set.of("update","catalog").contains(verb)&&text.contains("[yse] ошибка:"))throw new IOException(text.isBlank()?"YSE завершился с ошибкой "+task.code():text);
                Session.log(context,"YSE",verb+(name==null?"":" "+name),text.length()>5000?text.substring(text.length()-5000):text,"Выполнено");return text;
            }
            Thread.sleep(700);
        }
        throw new IOException("Операция YSE ещё выполняется на колонке. Проверьте журнал перед повтором.");
    }
    private static void require(NativeAdb.Result r) throws IOException{if(r.code()!=0)throw new IOException(r.output());}
}
