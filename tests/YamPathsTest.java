package dev.alice.bridge;
import java.nio.file.*;
import java.util.*;
public class YamPathsTest {
 public static void main(String[] args)throws Exception{
  String command=YamPaths.terminal("nano","/data/quazar/yam/data/plugins/nano","nano/bin/nano","",List.of("; touch /tmp/ab-yam-INJECTION","a'b"),List.of("nano/lib"));
  if(!command.contains("LD_LIBRARY_PATH=")||!command.contains("'./nano/bin/nano'")||!command.contains("'a'\\''b'"))throw new AssertionError(command);
  for(String path:List.of("../x","/etc/passwd","a/../../x","a//x","x\u0000y")){
   try{YamPaths.terminal("nano","/data/quazar/yam/data/plugins/nano",path,"",List.of(),List.of());throw new AssertionError(path);}catch(IllegalArgumentException expected){}
  }
  try{YamPaths.terminal("nano","/data/quazar/yam/data/plugins/other","bin/nano","",List.of(),List.of());throw new AssertionError();}catch(IllegalArgumentException expected){}
  try{YamPaths.terminal("nano","/data/quazar/yam/data/plugins/nano","bin/nano","python",List.of(),List.of());throw new AssertionError();}catch(IllegalArgumentException expected){}
  for(String id:List.of("../x","x;id","x y","")){try{YamPaths.id(id);throw new AssertionError(id);}catch(IllegalArgumentException expected){}}
  System.out.println("PASS: YAM trusted TUI commands, argument escaping, traversal and unknown root/interpreter rejection");
 }
}
