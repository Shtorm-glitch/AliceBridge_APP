package dev.alice.bridge;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
/** Execute the Android launcher with harmless stand-ins, including its detached daemon. */
public class MaxYamTest {
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("max-yam-test-"),bin=Files.createDirectories(dir.resolve("bin"));
  long pid=0;
  try{
   Path daemon=bin.resolve("yamd");Files.writeString(daemon,"#!/bin/sh\nprintf '%s\\n' \"$@\" > \"${0%/*}/args\"\nexec sleep 60\n");daemon.toFile().setExecutable(true);
   String script=MaxYam.start("/data/quazar/yam").replace("/data/quazar/yam",dir.toString());
   String prefix="id(){ echo 0; }; getenforce(){ echo Permissive; }; readlink(){ /usr/bin/readlink \"$@\"; };\n";
   Process launch=new ProcessBuilder("/bin/sh","-c",prefix+script).redirectErrorStream(true).start();
   if(!launch.waitFor(5,TimeUnit.SECONDS)||launch.exitValue()!=0)throw new AssertionError("launch: "+new String(launch.getInputStream().readAllBytes()));
   pid=Long.parseLong(Files.readString(dir.resolve("yamd.pid")).trim());
   if(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))throw new AssertionError("Daemon died with shell");
   String argv=Files.readString(bin.resolve("args"));if(!argv.contains("127.0.0.1:8099")||!argv.contains(dir+"/data"))throw new AssertionError(argv);
   Process denied=new ProcessBuilder("/bin/sh","-c","id(){ echo 0; };getenforce(){ echo Enforcing; };\n"+script).start();denied.waitFor();if(denied.exitValue()==0)throw new AssertionError("Must require Permissive");
   if(!MaxYam.start("/data/yam").contains("binary='/data/yam/yamd'"))throw new AssertionError("Guide legacy path");
   try{MaxYam.start("/tmp/injection");throw new AssertionError("Unknown base");}catch(IllegalArgumentException expected){}
   System.out.println("PASS: Max YAM survives launcher exit, private listen address, Permissive gate and legacy path");
  }finally{if(pid>0)ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);try(var paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
 }
}
