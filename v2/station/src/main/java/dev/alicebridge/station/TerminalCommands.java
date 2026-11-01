package dev.alicebridge.station;
import dev.alicebridge.bridge.*;

public final class TerminalCommands {
    public static String open(String token,int cols,int rows,boolean station1){
        String command=open(token,cols,rows);
        if(!station1)return command;
        command="export PATH=/data/local/tmp/alicebridge-tools:/system/bin:/system/xbin:$PATH; [ \"$(id -u)\" = 0 ] || { echo 'Не удалось открыть root-терминал Станции 1'; exit 1; }; "+command.replace("exec /bin/sh -i","exec /system/bin/sh -i");
        return "exec su 0 /system/bin/sh -c '"+command.replace("'","'\\''")+"'";
    }
    private static String base(String token){if(!token.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Неверная сессия терминала");return "/data/quasar/alicebridge-terminal/"+token;}
    private static void dimensions(int cols,int rows){if(cols<10||cols>500||rows<3||rows>300)throw new IllegalArgumentException("Неверный размер терминала");}
    public static String open(String token,int cols,int rows){dimensions(cols,rows);String dir=base(token);return "mkdir -p "+dir+"; chmod 700 "+dir+"; "+
        "if ! test -t 0; then echo 'ADB не предоставил интерактивный PTY'; exit 1; fi; "+
        "echo $$ > "+dir+"/pid; tty > "+dir+"/tty; stty echo; stty rows "+rows+" cols "+cols+"; "+
        "export TERM=xterm-256color PATH=/data/local/tmp/alicebridge-tools:/data/local/bin:/usr/local/bin:/data/opt/bin:/opt/bin:/opt/sbin:/system/bin:/system/xbin:/usr/bin:/bin:/usr/sbin:/sbin HOME=/data/opt/home; "+
        "mkdir -p \"$HOME\"; cd /data; if [ -x /data/opt/bin/alicebridge-login ]; then exec /data/opt/bin/alicebridge-login; else exec /bin/sh -i; fi";}
    public static String resize(String token,int cols,int rows){dimensions(cols,rows);return "ab_tty=$(cat "+base(token)+"/tty 2>/dev/null) || exit 0; case \"$ab_tty\" in /dev/pts/[0-9]*) stty -F \"$ab_tty\" rows "+rows+" cols "+cols+";; esac";}
    public static String foreground(String token){return "p=$(cat "+base(token)+"/pid 2>/dev/null) || exit 1; case \"$p\" in ''|*[!0-9]*) exit 1;; esac; s=$(cat /proc/$p/stat) || exit 1; s=${s##*) }; set -- $s; [ \"$3\" = \"$6\" ] && echo IDLE";}
    public static String cleanup(String token){return "rm -f "+base(token)+"/tty "+base(token)+"/pid; rmdir "+base(token)+" 2>/dev/null || true";}
    public static String packageCommand(String id){if(!id.matches("(?:ent|yap):[a-zA-Z0-9][a-zA-Z0-9+_.-]{0,100}")||id.contains(".."))throw new IllegalArgumentException("Неверное имя пакета");return (id.startsWith("ent:")?"/opt/bin/":"/data/opt/bin/")+id.substring(id.indexOf(':')+1);}
}
