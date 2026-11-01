package dev.alicebridge.app;
import org.json.*;
public final class UpdateInfoTest {
 static String valid()throws Exception{return new JSONObject().put("versionCode",32).put("versionName","0.4.20").put("url","http://78.24.223.83/AliceBridge-0.4.20.apk").put("sha256","a".repeat(64)).put("size",28000000).put("changes",new JSONArray().put("Проверка обновлений")).toString();}
 static void reject(JSONObject v)throws Exception{try{UpdateInfo.parse(v.toString());throw new AssertionError("Invalid update accepted");}catch(IllegalArgumentException|JSONException expected){}}
 @org.junit.Test public void releaseManifestValidation()throws Exception{
  var release=UpdateInfo.parse(valid());if(release.code()!=32||!release.changes().get(0).equals("Проверка обновлений"))throw new AssertionError("Release parsing");
  for(String url:new String[]{"http://example.org/AliceBridge-0.4.20.apk","http://78.24.223.83.evil/AliceBridge-0.4.20.apk","http://evil@78.24.223.83/AliceBridge-0.4.20.apk","http://78.24.223.83:8080/AliceBridge-0.4.20.apk","http://78.24.223.83/AliceBridge-0.4.19.apk","http://78.24.223.83/AliceBridge-0.4.20.apk?x=1"})reject(new JSONObject(valid()).put("url",url));
  reject(new JSONObject(valid()).put("size",200L*1024*1024));reject(new JSONObject(valid()).put("sha256","bad"));reject(new JSONObject(valid()).put("versionCode",0));
  System.out.println("Update metadata parsing, origin, filename, bounds: passed");
 }
}
