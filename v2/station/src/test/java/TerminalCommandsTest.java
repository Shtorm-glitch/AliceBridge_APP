import dev.alicebridge.station.TerminalCommands;
public class TerminalCommandsTest {
 public static void main(String[] args){
  String token="0123456789abcdef0123456789abcdef";
  String open=TerminalCommands.open(token,80,24);
  if(!open.contains("test -t 0")||!open.contains("stty rows 24 cols 80")||!open.contains("exec /bin/sh -i"))throw new AssertionError(open);
  if(!open.contains("/pid")||!open.contains("alicebridge-login"))throw new AssertionError(open);
  if(!TerminalCommands.open(token,80,24,false).equals(open))throw new AssertionError("Other models changed");
  String root=TerminalCommands.open(token,80,24,true);
  if(!root.startsWith("exec su 0 /system/bin/sh -c '")||!root.contains("$(id -u)")||!root.contains("exec /system/bin/sh -i"))throw new AssertionError(root);
  try{java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/station1-tests"));Utf8.write(java.nio.file.Path.of("build/station1-tests/terminal.sh"),root);}catch(Exception error){throw new RuntimeException(error);}
  try{Utf8.write(java.nio.file.Path.of("build/terminal-foreground.sh"),TerminalCommands.foreground(token));}catch(Exception error){throw new RuntimeException(error);}
  if(!TerminalCommands.resize(token,100,30).contains("stty -F"))throw new AssertionError();
  if(!TerminalCommands.packageCommand("ent:htop").equals("/opt/bin/htop"))throw new AssertionError();
  for(String id:new String[]{"ent:../bin/sh","ent:foo;reboot","ent:a/b","x:htop"})try{TerminalCommands.packageCommand(id);throw new AssertionError(id);}catch(IllegalArgumentException expected){}
  try{TerminalCommands.open("../../tmp",80,24);throw new AssertionError();}catch(IllegalArgumentException expected){}
  try{TerminalCommands.open(token,0,24);throw new AssertionError();}catch(IllegalArgumentException expected){}
  System.out.println("Terminal commands passed");
 }
}
