package dev.alice.bridge;
import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import java.lang.reflect.*;
import org.json.*;
/** Native tab routing and detail controls only; never installs or runs station packages. */
final class YamSmoke {
 static Field field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f;}
 static TextView find(View view,String label){if(view instanceof TextView t&&t.getText().toString().equals(label))return t;if(view instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){TextView t=find(g.getChildAt(i),label);if(t!=null)return t;}return null;}
 static void run(Instrumentation runner){Bundle result=new Bundle();int code=Activity.RESULT_OK;YamActivity[] a=new YamActivity[1];boolean old=Session.adbConnected;
  try{
   Session.adbConnected=false;a[0]=(YamActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),YamActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   long deadline=System.currentTimeMillis()+5000;while((Boolean)field(a[0],"busy").get(a[0])&&System.currentTimeMillis()<deadline)Thread.sleep(100);
   JSONArray data=new JSONArray("[{\"manifest\":{\"id\":\"test-bin\",\"name\":\"Test binary\",\"version\":\"1\",\"launch\":{\"kind\":\"binary\",\"mode\":\"oneshot\"}},\"enabled\":false,\"compatible\":true},{\"manifest\":{\"id\":\"test-lua\",\"name\":\"Test Lua\",\"version\":\"1\",\"launch\":{\"kind\":\"lua\",\"mode\":\"service\"}},\"enabled\":false,\"compatible\":true}]");
   field(a[0],"installed").set(a[0],data);Method list=YamActivity.class.getDeclaredMethod("list");list.setAccessible(true);
   runner.runOnMainSync(()->{try{list.invoke(a[0]);}catch(Exception e){throw new RuntimeException(e);}});runner.waitForIdleSync();View view=a[0].getWindow().getDecorView();
   if(find(view,"Test binary")==null||find(view,"Test Lua")!=null)throw new AssertionError("Binary tab mixed with Lua");
   final View binary=view;runner.runOnMainSync(()->find(binary,"Плагины").performClick());runner.waitForIdleSync();view=a[0].getWindow().getDecorView();
   if(find(view,"Test Lua")==null||find(view,"Test binary")!=null)throw new AssertionError("Lua tab mixed with binary");
   final View lua=view;runner.runOnMainSync(()->((View)find(lua,"Test Lua").getParent()).performClick());runner.waitForIdleSync();view=a[0].getWindow().getDecorView();
   if(find(view,"Включить")==null||find(view,"Удалить")==null||find(view,"Журнал")==null)throw new AssertionError("Missing management actions");
   result.putString("stream","PASS: YAM native binary/Lua tabs, separate manifests, plugin enable/remove/log controls; no station mutations\n");
  }catch(Throwable e){code=Activity.RESULT_CANCELED;result.putString("stream","FAIL: "+e+"\n");}finally{Session.adbConnected=old;runner.runOnMainSync(()->{if(a[0]!=null)a[0].finish();});runner.finish(code,result);}
 }
}
