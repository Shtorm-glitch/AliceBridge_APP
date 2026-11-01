package dev.alice.bridge;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.io.FileOutputStream;

/** Device checks for local terminal rendering and Wi-Fi search guidance; no installations. */
public final class TerminalSmoke extends Instrumentation {
 private boolean diagnostics,updates,shizuku,yam;
 @Override public void onCreate(Bundle args){super.onCreate(args);yam=args!=null&&"true".equals(args.getString("yam"));shizuku=args!=null&&"true".equals(args.getString("shizuku"));diagnostics=args!=null&&"true".equals(args.getString("diagnostics"));updates=args!=null&&"true".equals(args.getString("updates"));start();}
 private Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
 @Override public void onStart(){
  if(yam){YamSmoke.run(this);return;}
  if(shizuku){ShizukuSmoke.run(this);return;}
  if(diagnostics){DiagnosticsSmoke.run(this);return;}
  if(updates){UpdateSmoke.run(this);return;}
  TerminalActivity[] terminal=new TerminalActivity[1];HomeWifiActivity[] wifi=new HomeWifiActivity[1];
  String old=Session.station,ssid=Session.homeSsid,status=Session.wifiStatus;
  boolean connected=Session.adbConnected,pending=Session.wifiSwitchPending,done=Session.wifiDone,searching=Session.wifiSearching;
  long started=Session.wifiWaitStarted;
  Bundle result=new Bundle();int code=Activity.RESULT_OK;
  try{
   runOnMainSync(()->{Session.station="127.0.0.1";Session.adbConnected=true;});
   terminal[0]=(TerminalActivity)startActivitySync(new Intent(getTargetContext(),TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   long deadline=System.currentTimeMillis()+10000;while(!(Boolean)field(terminal[0],"ready")&&System.currentTimeMillis()<deadline)Thread.sleep(100);
   if(!(Boolean)field(terminal[0],"ready"))throw new AssertionError("Local xterm WebView did not initialize");
   View web=(View)field(terminal[0],"web");
   if(web.getWidth()<terminal[0].getResources().getDisplayMetrics().widthPixels*.95)throw new AssertionError("Terminal has excessive side margins");
   runOnMainSync(()->{terminal[0].finish();Session.wifiSwitchPending=true;Session.wifiDone=false;Session.wifiSearching=true;Session.homeSsid="Домашняя сеть";Session.wifiWaitStarted=SystemClock.elapsedRealtime()-90000;Session.wifiStatus="Ищем Алису в домашней сети. Проверяем новый IP и ADB, подождите…";});
   wifi[0]=(HomeWifiActivity)startActivitySync(new Intent(getTargetContext(),HomeWifiActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   waitForIdleSync();
   if(!((TextView)field(wifi[0],"help")).getText().toString().contains("снова откройте AliceBridge"))throw new AssertionError("Missing long-search guidance");
   if(!((TextView)field(wifi[0],"waitInfo")).getText().toString().contains("Прошло:"))throw new AssertionError("Missing elapsed time");
   if(((TextView)field(wifi[0],"searchButton")).isEnabled())throw new AssertionError("Concurrent search button enabled");
   try(FileOutputStream out=new FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"wifi-search-smoke.png"))){getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
   result.putString("stream","PASS: local xterm bridge; terminal uses full width; Wi-Fi search timer and restart guidance; concurrent search disabled\n");
  }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream","FAIL: "+error+"\n");}
  finally{runOnMainSync(()->{if(terminal[0]!=null)terminal[0].finish();if(wifi[0]!=null)wifi[0].finish();Session.station=old;Session.adbConnected=connected;Session.homeSsid=ssid;Session.wifiStatus=status;Session.wifiSwitchPending=pending;Session.wifiDone=done;Session.wifiSearching=searching;Session.wifiWaitStarted=started;});finish(code,result);}
 }
}
