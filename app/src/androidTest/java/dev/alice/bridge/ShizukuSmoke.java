package dev.alice.bridge;

import android.app.*;
import android.content.*;
import android.os.*;
import java.net.*;
import java.io.*;
import java.util.*;
import org.json.*;
import rikka.shizuku.Shizuku;

final class ShizukuSmoke {
 static void run(Instrumentation test){
  Bundle result=new Bundle();ShizukuNetwork network=null;HotspotSocks proxy=null;int code=Activity.RESULT_OK;
  try{
   // Request permission through the real manager UI; never change manager permissions through adb/root.
   test.startActivitySync(new Intent(test.getTargetContext(),ShizukuGuideActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   long deadline=System.currentTimeMillis()+15000;while(!Shizuku.pingBinder()&&System.currentTimeMillis()<deadline)Thread.sleep(200);
   if(!ShizukuNetwork.permitted())test.runOnMainSync(()->Shizuku.requestPermission(71));
   deadline=System.currentTimeMillis()+60000;while(!ShizukuNetwork.permitted()&&System.currentTimeMillis()<deadline)Thread.sleep(300);
   if(!ShizukuNetwork.permitted())throw new AssertionError("Нужно разрешить AliceBridge в Shizuku");
   if(Shizuku.getUid()!=2000)throw new AssertionError("Test requires shell Shizuku, got UID "+Shizuku.getUid());
   network=new ShizukuNetwork(test.getTargetContext());network.prepare("78.24.223.83");
   String probe=network.probe();JSONObject p=new JSONObject(probe);
   if(!p.getString("udp").contains("78.24.223.83")||!p.getString("tcp").contains("78.24.223.83"))throw new AssertionError(probe);
   if(!p.optString("http").contains("204"))throw new AssertionError(probe);
   String iface=network.startHotspot("AliceBridge","alice-shizuku-test");network.check();
   if(iface.isEmpty())throw new AssertionError("No hotspot interface");
   network.neighbors(iface);
   result.putString("stream","PASS: Shizuku UID 2000; real test-TUN; UDP + TCP DNS through HEV/SOCKS; hotspot upstream confirmed; neighbor query; cleanup\\n"+probe+"\\n");
  }catch(Throwable e){code=Activity.RESULT_CANCELED;result.putString("stream","FAIL: "+e+"\n");StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.putString("trace",trace.toString());}
  finally{if(network!=null)network.close();if(proxy!=null)proxy.close();test.finish(code,result);}
 }
}
