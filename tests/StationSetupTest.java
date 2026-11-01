import dev.alice.bridge.StationSetup;
import dev.alice.bridge.SetupRecovery;
import java.util.*;
import java.nio.file.*;
public class StationSetupTest {
 public static void main(String[] args) throws Exception {
  List<String> calls=new ArrayList<>();List<List<StationSetup.Step>> updates=new ArrayList<>();
  var result=StationSetup.apply(script->{calls.add(script);return new StationSetup.Result(calls.size()==2?1:0,"result "+calls.size());},updates::add);
  if(calls.size()!=3||updates.size()!=6||StationSetup.complete(result)||!result.get(1).status().equals("Ошибка")||!result.get(2).status().equals("Готово"))throw new AssertionError("independent outcomes");
  var success=StationSetup.apply(script->new StationSetup.Result(0,"verified"),s->{});
  if(!StationSetup.complete(success))throw new AssertionError("completion");
  var extra=new ArrayList<>(success);extra.add(new StationSetup.Step("fastfetch","Fastfetch","Пропущено",""));if(!StationSetup.complete(extra))throw new AssertionError("optional skip blocked setup");
  extra.set(3,new StationSetup.Step("fastfetch","Fastfetch","Ошибка",""));if(StationSetup.complete(extra))throw new AssertionError("failed optional stage needs explicit skip");
  var info=StationSetup.parseInfo("daemon noise\nAB_UID=0\nAB_VERSION=0.369.20261013\nAB_MODEL=Yandex=Lite\n");
  if(!"0".equals(info.get("UID"))||!"Yandex=Lite".equals(info.get("MODEL")))throw new AssertionError("parse");
  for(var step:StationSetup.pending())Files.writeString(Path.of(args[0],step.id()+".sh"),StationSetup.script(step.id()));
  Files.writeString(Path.of(args[0],"info.sh"),StationSetup.INFO);
  if(StationSetup.INFO.indexOf(0)>=0)throw new AssertionError("NUL in shell script");
  String ssidScript=StationSetup.INFO.substring(StationSetup.INFO.indexOf("ab_ssid="),StationSetup.INFO.indexOf("printf 'AB_DATA='"));
  for(String status:new String[]{"", "ssid=AliceBridge"}){
   Process process=new ProcessBuilder("sh","-c","wpa_cli(){ printf '%s\\n' '"+status+"'; }; "+ssidScript+"printf 'AB_DATA=98644\\n'").start();
   String output=new String(process.getInputStream().readAllBytes());process.waitFor();var parsed=StationSetup.parseInfo(output);
   if(!parsed.getOrDefault("SSID","missing").equals(status.isEmpty()?"":"AliceBridge")||!parsed.getOrDefault("DATA","").equals("98644"))throw new AssertionError(output);
  }
  List<String> fallbackCalls=new ArrayList<>();
  var recovered=StationSetup.apply(script->{fallbackCalls.add(script);return new StationSetup.Result(fallbackCalls.size()==1?1:0,"probe");},v->{});
  if(fallbackCalls.size()!=4||!StationSetup.complete(recovered)||!fallbackCalls.get(1).contains("AB_OTA_KEY"))throw new AssertionError("OTA fallback dispatch");
  Files.writeString(Path.of(args[0],"ota-fallback.sh"),StationSetup.otaFallbackScript());
  Files.writeString(Path.of(args[0],"inspect.sh"),StationSetup.INSPECT);
  var interruptedCalls=new ArrayList<String>();
  var interruptedSteps=StationSetup.apply(script->{interruptedCalls.add(script);if(interruptedCalls.size()==2)throw new java.io.IOException("adb -s: время ожидания 90 с истекло");return new StationSetup.Result(0,"ok");},v->{});
  if(interruptedCalls.size()!=2||!interruptedSteps.get(0).status().equals("Готово")||!interruptedSteps.get(2).status().equals("Ожидание")||!SetupRecovery.interrupted(interruptedSteps))throw new AssertionError("transport loss did not pause setup");
  var resumedCalls=new ArrayList<String>();
  var resumedSteps=StationSetup.apply(script->{resumedCalls.add(script);return new StationSetup.Result(0,"ok");},v->{},interruptedSteps);
  if(resumedCalls.size()!=2||!StationSetup.complete(resumedSteps))throw new AssertionError("completed OTA repeated during resume");
  var timeoutCalls=new ArrayList<String>();
  StationSetup.apply(script->{timeoutCalls.add(script);throw new java.io.IOException("Connection reset");},v->{});
  if(timeoutCalls.size()!=1)throw new AssertionError("OTA fallback attempted through broken transport");
  if(SetupRecovery.transport("Permission denied")||SetupRecovery.transport("Не найдены компоненты OTA"))throw new AssertionError("permanent failure would be auto retried");
  Thread.currentThread().interrupt();
  try{StationSetup.apply(script->{throw new AssertionError("cancelled shell executed");},s->{});throw new AssertionError("no interrupt");}catch(InterruptedException expected){}finally{Thread.interrupted();}
  System.out.println("PASS: setup outcomes, partial failures, cancellation, info parsing, scripts exported");
 }
}
