package dev.alice.bridge;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Max-specific transport, sharing the app's existing private ADB server and keys. */
final class MaxCommands {
 private final Context context;private final String host;private final File binary,home,tmp;private final String server;
 MaxCommands(Context c,String host){context=c;this.host=host;binary=new File(c.getApplicationInfo().nativeLibraryDir,"libadb.so");home=new File(c.getFilesDir(),"adb-home");tmp=new File(c.getCacheDir(),"adb-tmp");server="localfilesystem:"+new File(c.getFilesDir(),"adb-server.sock").getAbsolutePath();}
 private ProcessBuilder command(String...args){List<String> cmd=new ArrayList<>(List.of(binary.getAbsolutePath(),"-L",server,"-s",host+":5555"));cmd.addAll(Arrays.asList(args));ProcessBuilder b=new ProcessBuilder(cmd).directory(home).redirectErrorStream(true);b.environment().put("ABHM",home.getAbsolutePath());b.environment().put("AB_TMP",tmp.getAbsolutePath());return b;}
 NativeAdb.Result rawShell(String script)throws IOException,InterruptedException{
  File log=File.createTempFile("max-adb-", ".log",context.getCacheDir());Process p=null;
  try{p=command("shell",script).redirectOutput(log).start();if(!p.waitFor(180,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("Таймаут команды ADB Макс");}try(InputStream in=new FileInputStream(log)){return new NativeAdb.Result(p.exitValue(),new String(Streams.readBounded(in,32768),StandardCharsets.UTF_8).trim());}}
  finally{if(p!=null&&p.isAlive())p.destroyForcibly();log.delete();}
 }
 Process openBridge()throws IOException{return command("shell","-T",MaxPlan.BASE+"/root-bridge esc-bridge").start();}
 static NativeAdb.Result shell(Context c,NativeAdb adb,String script)throws IOException,InterruptedException{
  if(!MaxRootBridge.ready(adb.target()))return adb.shell(script);
  MaxCommands wire=new MaxCommands(c,adb.target());String id=UUID.randomUUID().toString().replace("-",""),marker="AB_MAX_EXIT_"+id+"=";
  String remote=MaxPlan.BASE+"/command-"+id+".sh",output=remote+".out";File local=File.createTempFile("max-command-", ".sh",c.getCacheDir());
  try{
   try(OutputStream out=new FileOutputStream(local)){out.write(RemoteShell.wrap(script,marker).getBytes(StandardCharsets.UTF_8));}
   NativeAdb.Result pushed=adb.push(local,remote);if(pushed.code()!=0)throw new IOException(pushed.output());
   MaxRootBridge.current(adb.target()).execute("/system/bin/sh "+RootShell.quote(remote)+" > "+RootShell.quote(output)+" 2>&1; chmod 644 "+RootShell.quote(output));
   NativeAdb.Result raw=wire.rawShell("cat "+RootShell.quote(output));RemoteShell.Result parsed=RemoteShell.parse(raw.output(),marker);return new NativeAdb.Result(parsed.code(),parsed.output());
  }catch(IOException e){MaxRootBridge.stop();throw e;}
  finally{local.delete();try{wire.rawShell("rm -f "+RootShell.quote(remote)+" "+RootShell.quote(output));}catch(IOException ignored){}}
 }
}
