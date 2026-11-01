package dev.alice.bridge;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.lang.reflect.*;

final class UpdateSmoke {
 static void run(Instrumentation runner){
  Bundle result=new Bundle();int code=Activity.RESULT_OK;MainActivity[] activity=new MainActivity[1];
  try{
   var context=runner.getTargetContext();File candidate=new File(context.getCacheDir(),"update-test-candidate.apk");
   if(!candidate.isFile())throw new AssertionError("Missing signed test fixture");
   MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(candidate)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
   StringBuilder sha=new StringBuilder();for(byte b:hash.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
   try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
    Thread http=new Thread(()->{try(Socket socket=server.accept();InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream()){
     int tail=0;while(true){int b=in.read();if(b<0)throw new IOException("No request");tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
     out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+candidate.length()+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));try(InputStream file=new FileInputStream(candidate)){Streams.copy(file,out);}
    }catch(Exception ignored){}});http.start();
    var release=new UpdateInfo.Release(33,"0.4.21","http://127.0.0.1:"+server.getLocalPort()+"/candidate.apk",sha.toString(),candidate.length(),List.of("Тестовая проверка окна обновления","Пропуск действует только до следующего открытия"));
    File ready=AppUpdates.download(context,release,(a,b)->{});http.join(5000);AppUpdates.verify(context,ready,release);
    boolean rejected=false;try{AppUpdates.verify(context,ready,new UpdateInfo.Release(33,"0.4.21",release.url(),"0".repeat(64),release.size(),release.changes()));}catch(IOException expected){rejected=true;}if(!rejected)throw new AssertionError("Hash mismatch accepted");
    if(AppUpdates.signaturesMatch(new android.content.pm.Signature[]{new android.content.pm.Signature(new byte[]{1})},new android.content.pm.Signature[]{new android.content.pm.Signature(new byte[]{2})}))throw new AssertionError("Wrong signer accepted");
    try(InputStream in=context.getContentResolver().openInputStream(UpdateProvider.uri(context,ready))){if(in==null||in.read()!='P'||in.read()!='K')throw new AssertionError("Installer cannot read APK");}
    boolean writeRejected=false;try(var fd=context.getContentResolver().openFileDescriptor(UpdateProvider.uri(context,ready),"w")){}catch(SecurityException expected){writeRejected=true;}if(!writeRejected)throw new AssertionError("Update provider accepted writes");
    activity[0]=(MainActivity)runner.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Method show=MainActivity.class.getDeclaredMethod("showUpdate",UpdateInfo.Release.class);show.setAccessible(true);Field field=MainActivity.class.getDeclaredField("updateDialog");field.setAccessible(true);
    runner.runOnMainSync(()->{try{show.invoke(activity[0],release);}catch(Exception e){throw new RuntimeException(e);}});runner.waitForIdleSync();Dialog dialog=(Dialog)field.get(activity[0]);
    if(dialog==null||!dialog.isShowing()||find(dialog.getWindow().getDecorView(),"Обновить")==null||find(dialog.getWindow().getDecorView(),"Что нового")==null)throw new AssertionError("Missing update UI");
    android.graphics.Bitmap[] bitmap=new android.graphics.Bitmap[1];runner.runOnMainSync(()->{View decor=dialog.getWindow().getDecorView();bitmap[0]=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);decor.draw(new android.graphics.Canvas(bitmap[0]));});
    try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"update-dialog-smoke.png"))){bitmap[0].compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
    runner.runOnMainSync(()->find(dialog.getWindow().getDecorView(),"Пропустить обновление").performClick());if(dialog.isShowing())throw new AssertionError("Skip did not close dialog");
    runner.runOnMainSync(()->{try{show.invoke(activity[0],release);}catch(Exception e){throw new RuntimeException(e);}});Dialog again=(Dialog)field.get(activity[0]);if(again==null||!again.isShowing())throw new AssertionError("Skip permanently suppressed update");runner.runOnMainSync(again::dismiss);
    ready.delete();candidate.delete();
   }
   result.putString("stream","PASS: signed newer APK download, SHA/version/signer validation, installer URI read-only; update dialog, changes, skip and repeat prompt\n");
  }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream","FAIL: "+error+"\n");}
  finally{runner.runOnMainSync(()->{if(activity[0]!=null)activity[0].finish();});runner.finish(code,result);}
 }
 private static View find(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),text);if(result!=null)return result;}}return null;}
}
