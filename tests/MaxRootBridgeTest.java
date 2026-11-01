package dev.alice.bridge;
import java.io.*;
public class MaxRootBridgeTest {
 public static void main(String[] args)throws Exception{
  String worker="echo 'bridge: worker READY'; while IFS= read -r line; do case \"$line\" in exit) exit 0;; \\\\stat*) echo '\\stat: mode=0750 uid=0 gid=0';; *) sh -c \"$line\";; esac; done";
  Process p=new ProcessBuilder("sh","-c",worker).redirectErrorStream(true).start();
  try(MaxRootBridge b=new MaxRootBridge(p,"127.0.0.1","boot-a",s->{})){
   b.awaitReady();b.builtin("\\stat /cache/recovery","\\stat: mode=0750 uid=0 gid=0");b.execute("printf 'ok\\n'");
   try{b.execute("bad\nline");throw new AssertionError();}catch(IOException expected){}
   try{b.execute("x".repeat(1900));throw new AssertionError();}catch(IOException expected){}
  }
  System.out.println("PASS: Max bridge READY, builtin response, serialized marker command and input limits");
 }
}
