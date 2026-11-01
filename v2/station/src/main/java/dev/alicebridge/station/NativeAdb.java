package dev.alicebridge.station;
import dev.alicebridge.bridge.*;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owns an actual Android adb client/server; all server IPC and keys are app-private. */
public final class NativeAdb implements AutoCloseable {
    private final StationState scopeState;

    public record Result(int code, String output) {}
    public record Check(boolean connected, String status, String detail) {}
    private final Context context;
    private final File binary, home, temporary;
    private final String serverSocket;
    private boolean started;
    private String target = "";
    private int yamForwardPort;
    private int rootForwardPort;
    private String yamForwardTarget = "";
    public String target() { return target; }
    public void forgetTarget() throws IOException, InterruptedException {
        if (started && !target.isEmpty()) run(3, "disconnect", target + ":5555");
        target = ""; yamForwardPort=0; yamForwardTarget="";rootForwardPort=0;
    }

    public NativeAdb(Context context) {
        this.scopeState=StationContext.require(context);
        this.context = context;
        binary = new File(context.getApplicationInfo().nativeLibraryDir, "libadb.so");
        home = new File(context.getFilesDir(), "v2-adb");
        temporary = new File(context.getCacheDir(), "v2-adb");
        serverSocket = "localfilesystem:" + new File(home, "server.sock").getAbsolutePath();
    }
    private Result run(int timeout, String... args) throws IOException, InterruptedException {
        if (!binary.isFile()) throw new IOException("В APK отсутствует Android ADB для ARM64");
        if ((!home.isDirectory() && !home.mkdirs()) || (!temporary.isDirectory() && !temporary.mkdirs()))
            throw new IOException("Не удалось создать закрытый каталог ADB");
        List<String> command = new ArrayList<>(List.of(binary.getAbsolutePath(), "-L", serverSocket));
        command.addAll(Arrays.asList(args));
        File output = File.createTempFile("adb-client-", ".log", context.getCacheDir());
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(home).redirectErrorStream(true).redirectOutput(output);
            // Patched getenv keys in the separately distributed executable; HOME/TMPDIR of the app stay unchanged.
            builder.environment().put("ABHM", home.getAbsolutePath());
            builder.environment().put("AB_TMP", temporary.getAbsolutePath());
            builder.environment().put("ADB_MDNS_AUTO_CONNECT", "0");
            process = builder.start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("adb " + args[0] + ": время ожидания " + timeout + " с истекло");
            }
            try (InputStream in = new FileInputStream(output)) {
                return new Result(process.exitValue(), new String(Streams.readBounded(in, 32768), StandardCharsets.UTF_8).trim());
            }
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (!output.delete()) android.util.Log.w("AliceBridge", "ADB temporary output remains");
        }
    }
    public Check check(String host) throws IOException, InterruptedException {
        RootShell.validateIpv4(host);
        if (!target.isEmpty() && !target.equals(host)) forgetTarget();
        target = host;
        String endpoint = host + ":5555";
        if (!started) {
            Result version = run(4, "version");
            if (version.code() != 0) throw new IOException(version.output());
            scopeState.log(context, "ADB", "Встроенный Android ADB", version.output(), "Запуск клиента");
            Result start = run(10, "start-server");
            if (start.code() != 0) throw new IOException("ADB server: " + start.output());
            started = true;
        }
        Result connect;
        try { connect = run(8, "connect", endpoint); }
        catch (IOException timeout) { connect = new Result(-1, timeout.getMessage()); }
        Result state = run(4, "-s", endpoint, "get-state");
        String details = "adb connect " + endpoint + "\n" + connect.output() + "\nadb -s " + endpoint + " get-state\n" + state.output();
        if (state.code() == 0 && state.output().equals("device")) {
            Result shell = run(5, "-s", endpoint, "shell", "id");
            details += "\nadb -s " + endpoint + " shell id\n" + shell.output();
            if (shell.code() == 0 && shell.output().contains("uid=")) return new Check(true, "ADB подключён · shell id подтверждён", details);
            return new Check(false, "ADB transport доступен · shell пока не ответил", details);
        }
        String lower = details.toLowerCase(Locale.ROOT);
        String status = lower.contains("unauthorized") ? "ADB требует авторизацию ключа" :
            lower.contains("offline") ? "ADB найден · устройство offline" :
            lower.contains("no route to host") ? "Колонка недоступна по сети · No route to host" :
            lower.contains("refused") ? "ADB 5555 отклоняет подключение" :
            lower.contains("timed out") || lower.contains("время ожидания") ? "ADB 5555 · тайм-аут, повторим попытку" : "Ожидаем подключения ADB";
        return new Check(false, status, details);
    }
    public int forwardYam() throws IOException, InterruptedException {
        if (!started || target.isEmpty()) throw new IOException("Нет подключения ADB");
        if(yamForwardPort>0 && yamForwardTarget.equals(target)){
            Result mappings=run(4,"forward","--list");
            if(mappings.code()==0 && AdbForward.matches(mappings.output(),target,yamForwardPort))return yamForwardPort;
        }
        Result result = run(5, "-s", target + ":5555", "forward", "tcp:0", "tcp:8099");
        if (result.code() != 0) throw new IOException(result.output());
        try {yamForwardPort=Integer.parseInt(result.output().trim());yamForwardTarget=target;return yamForwardPort;}
        catch (NumberFormatException e) { throw new IOException("ADB не вернул порт YAM: " + result.output()); }
    }
    public Result rootStation()throws IOException,InterruptedException {return run(8,"-s",target+":5555","root");}
    public Result rebootStation()throws IOException,InterruptedException {return run(8,"-s",target+":5555","reboot");}
    public Result push(File file, String destination) throws IOException, InterruptedException {
        if (!started || target.isEmpty()) throw new IOException("Нет подключения ADB");
        int seconds=(int)Math.min(600,Math.max(90,30+file.length()/32768));
        if(!station1Root())return run(seconds,"-s",target+":5555","push",file.getAbsolutePath(),destination);
        String staging="/data/local/tmp/alicebridge-upload-"+UUID.randomUUID().toString().replace("-","");
        try{
            Result transferred=run(seconds,"-s",target+":5555","push",file.getAbsolutePath(),staging);
            if(transferred.code()!=0)return transferred;
            return shell("set -e\ncp "+RootShell.quote(staging)+" "+RootShell.quote(destination));
        }finally{removeStaging(staging);}
    }
    public Process terminal(String token,int cols,int rows) throws IOException,InterruptedException {
        if(!started||target.isEmpty())throw new IOException("Нет соединения ADB");
        if(Station1Plan.matches(scopeState.stationInfo)&&!station1Root())throw new IOException("Сначала завершите установку root boot Станции 1");
        String remote=pushScript(STATION_TOOLS+TerminalCommands.open(token,cols,rows,Station1Plan.matches(scopeState.stationInfo)));
        if(station1Root())return openRootProcess(remote,true);
        ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"-L",serverSocket,"-s",target+":5555","shell","-tt","sh "+RootShell.quote(remote));
        builder.directory(home).redirectErrorStream(true);
        builder.environment().put("ABHM",home.getAbsolutePath());builder.environment().put("AB_TMP",temporary.getAbsolutePath());builder.environment().put("TERM","xterm-256color");
        return builder.start();
    }
    public Result pull(String source,File file) throws IOException,InterruptedException {return pull(source,file,90);}
    public Result pull(String source,File file,int timeout) throws IOException,InterruptedException {
        if(!station1Root())return run(timeout,"-s",target+":5555","pull",source,file.getAbsolutePath());
        String staging="/data/local/tmp/alicebridge-download-"+UUID.randomUUID().toString().replace("-","");
        try{
            Result prepared=shell("set -e\ncp "+RootShell.quote(source)+" "+RootShell.quote(staging)+"\nchown 2000:2000 "+RootShell.quote(staging)+"\nchmod 600 "+RootShell.quote(staging));
            if(prepared.code()!=0)return prepared;
            return run(timeout,"-s",target+":5555","pull",staging,file.getAbsolutePath());
        }finally{removeStaging(staging);}
    }
    private void removeStaging(String path)throws InterruptedException{
        try{run(4,"-s",target+":5555","shell","rm -f "+RootShell.quote(path));}
        catch(IOException error){android.util.Log.w("AliceBridge","Station staging cleanup failed",error);}
    }
    private static final String STATION_TOOLS="""
        if [ -x /system/bin/busybox-smp ]; then
            if [ ! -f /data/local/tmp/alicebridge-tools/.ready ]; then
                /system/bin/busybox-smp mkdir -p /data/local/tmp/alicebridge-tools
                ab_applets=$(/system/bin/busybox-smp --list 2>/dev/null) || ab_applets='awk sed grep head tail cut tr sha256sum stty tty tar gunzip df stat dd chmod chown cp mv rm mkdir rmdir ln ls cat touch uname sort find wc sleep sync'
                for ab_applet in $ab_applets; do
                    case "$ab_applet" in su|reboot|poweroff|halt|mount) continue;; esac
                    /system/bin/busybox-smp ln -sf /system/bin/busybox-smp "/data/local/tmp/alicebridge-tools/$ab_applet"
                done
                /system/bin/busybox-smp touch /data/local/tmp/alicebridge-tools/.ready
            fi
            if [ -L /data/local/tmp/alicebridge-tools/mount ]; then /system/bin/busybox-smp rm -f /data/local/tmp/alicebridge-tools/mount; fi
            export PATH=/data/local/tmp/alicebridge-tools:/system/bin:/system/xbin:$PATH
        fi
        """;
    public Result shell(String script) throws IOException, InterruptedException {
        if (!started || target.isEmpty()) throw new IOException("Нет подключения ADB");
        String marker = "AB_EXIT_" + UUID.randomUUID().toString().replace("-", "") + "=";
        // Old adbd rejects long command lines and has no raw stdin shell service.
        String remote=pushScript(RemoteShell.wrap(STATION_TOOLS+script,marker));
        try {
            Result raw;
            if(station1Root()){
                Process root=openRootProcess(remote,false);
                try(InputStream input=root.getInputStream()){
                    raw=new Result(0,new String(Streams.readBounded(input,32768),StandardCharsets.UTF_8));
                }finally{root.destroyForcibly();}
            }else raw=run(90,"-s",target+":5555","shell",(!scopeState.stationInfo.getOrDefault("SDK", "").isEmpty()?"/system/bin/sh ":"/bin/sh ")+RootShell.quote(remote));
            RemoteShell.Result result=RemoteShell.parse(raw.output(),marker);
            return new Result(result.code(),result.output());
        } finally {
            try{run(4,"-s",target+":5555","shell","rm -f "+RootShell.quote(remote));}
            catch(IOException cleanupError){android.util.Log.w("AliceBridge","Remote station script cleanup failed",cleanupError);}
        }
    }
    private boolean station1Root(){return target.equals(scopeState.station)&&Station1Plan.matches(scopeState.stationInfo)&&"1".equals(scopeState.stationInfo.get("DEBUGGABLE"));}
    private Process openRootProcess(String remote,boolean interactive)throws IOException,InterruptedException{
        try{return new StationRootProcess(forwardRootShell(),"/system/bin/sh "+RootShell.quote(remote),interactive);}
        catch(IOException error){rootForwardPort=0;throw error;}
    }
    private int forwardRootShell()throws IOException,InterruptedException{
        if(rootForwardPort>0){
            Result mappings=run(4,"forward","--list");
            if(mappings.code()==0)for(String line:mappings.output().split("\\R")){
                String[] fields=line.trim().split("\\s+");
                if(fields.length==3&&fields[0].equals(target+":5555")&&fields[1].equals("tcp:"+rootForwardPort)&&fields[2].equals("tcp:2323"))return rootForwardPort;
            }
            rootForwardPort=0;
        }
        Result result=run(5,"-s",target+":5555","forward","tcp:0","tcp:2323");
        if(result.code()!=0)throw new IOException("Не удалось подключить root-оболочку: "+result.output());
        try{int port=Integer.parseInt(result.output().trim());if(port<1||port>65535)throw new NumberFormatException();return rootForwardPort=port;}
        catch(NumberFormatException error){throw new IOException("ADB не вернул порт root-оболочки");}
    }
    private String pushScript(String script)throws IOException,InterruptedException {
        File source=File.createTempFile("station-command-", ".sh", context.getCacheDir());
        String remote="/data/local/tmp/"+source.getName();
        try {
            source.setReadable(false,false);source.setReadable(true,true);
            source.setWritable(false,false);source.setWritable(true,true);
            try(OutputStream out=new FileOutputStream(source)){
                out.write(("rm -f \"$0\"\n"+script+"\n").getBytes(StandardCharsets.UTF_8));
            }
            Result pushed=run(90,"-s",target+":5555","push",source.getAbsolutePath(),remote);
            if(pushed.code()!=0)throw new IOException("Не удалось передать скрипт на колонку: "+pushed.output());
            return remote;
        } finally {
            if(!source.delete())android.util.Log.w("AliceBridge","Temporary station script remains");
        }
    }
    /** Read-only: uses the existing server, never connects another target or kills the shared server. */
    public String diagnosticSnapshot() throws IOException,InterruptedException {
        StringBuilder report=new StringBuilder("Время: "+System.currentTimeMillis()+"\n");
        if(!new File(context.getFilesDir(),"adb-server.sock").exists())return report+"Собственный ADB-сервер не запущен.\n";
        for(String[] args:new String[][]{{"version"},{"devices","-l"},{"forward","--list"}}){Result r=run(4,args);report.append(Arrays.toString(args)).append(" · exit=").append(r.code()).append('\n').append(r.output()).append("\n\n");}
        String host=scopeState.station;
        try{RootShell.validateIpv4(host);}catch(IllegalArgumentException invalid){return report+"Адрес выбранной колонки отсутствует.\n";}
        Result state=run(4,"-s",host+":5555","get-state");report.append("get-state: ").append(state.code()).append(' ').append(state.output()).append('\n');
        if(state.code()!=0||!state.output().equals("device"))return report+"Колонка не отвечает; сохранённые сведения — в state-*.json.\n";
        String script="""
            date; id; uname -a; cat /proc/uptime; cat /proc/sys/kernel/random/boot_id
            cat /system/vendor/quasar/version_full /etc/buildname 2>/dev/null
            getprop ro.board.platform 2>/dev/null; getprop ro.build.display.id 2>/dev/null
            ip -4 addr; ip -4 route; ip neigh; df -h; cat /etc/resolv.conf
            wpa_cli -i wlan0 status 2>/dev/null
            ps | grep -E 'adbd|maind|quasar_launcher|dropbear|wifid|updatesd|yamd' | grep -v grep
            ls -l /data/quazar/yam/bin/yamd /data/quazar/yam/yamd.pid /system/vendor/quasar/device_ota_update.sh 2>/dev/null
            sha256sum /system/vendor/quasar/device_ota_update.sh 2>/dev/null
            ls -l /data/quasar/activate_adb_quasmodrom /data/quasar/activate_network_adb /data/opt/bin/alicebridge-login 2>/dev/null
            ls -l /etc/init.d/S99zalicebridge-ssh /data/quasar/alicebridge-ssh/dropbear.pid /data/dropbear/start.sh 2>/dev/null
            for f in /data/quasar/alicebridge-ssh/startup.log /data/quasar/alicebridge-ssh/dropbear.log /data/dropbear/dropbear.log; do echo "=== $f ==="; tail -n 25 "$f" 2>/dev/null | cut -c 1-180; done
            for f in /data/quasar/daemons_logs/wifid.log /data/quasar/daemons_logs/updatesd.log /data/quasar/daemons_logs/wifid.log.1 /data/quasar/daemons_logs/updatesd.log.1; do
                echo "=== $f ==="; tail -n 35 "$f" | cut -c 1-160 2>/dev/null
            done
            """;
        Result snapshot=run(10,"-s",host+":5555","shell","sh","-c",RootShell.quote(script));
        report.append("Снимок колонки · exit=").append(snapshot.code()).append('\n').append(snapshot.output());return report.toString();
    }
    @Override public void close() {
        if (!started) return;
        try {
            Result result = run(5, "kill-server");
            if (result.code() != 0) scopeState.log(context, "ADB", "Собственный ADB-сервер", result.output(), "Ошибка остановки");
        } catch (IOException e) { scopeState.log(context, "ADB", "Собственный ADB-сервер", e.toString(), "Ошибка остановки"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        started = false; yamForwardPort=0; yamForwardTarget="";rootForwardPort=0;
    }
}
