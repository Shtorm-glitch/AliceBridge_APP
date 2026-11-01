package dev.alice.bridge;
import android.app.*;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;
final class DiagnosticsSmoke {
 static void run(Instrumentation runner){
  Bundle result=new Bundle();int code=Activity.RESULT_OK;
  try{
   var context=runner.getTargetContext();
   Session.log(context,"DIAGNOSTIC_TEST","Экспорт","password=synthetic-diag-secret","Проверка экспорта");
   File file=new File(context.getCacheDir(),"diagnostics-smoke.zip");
   try(OutputStream out=new FileOutputStream(file)){Diagnostics.write(context,out);}
   Set<String> names=new HashSet<>();int events=0;
   try(ZipInputStream zip=new ZipInputStream(new FileInputStream(file),StandardCharsets.UTF_8)){
    ZipEntry entry;while((entry=zip.getNextEntry())!=null){names.add(entry.getName());String text=new String(Streams.readBounded(zip,16*1024*1024),StandardCharsets.UTF_8);
     if(text.contains("synthetic-diag-secret"))throw new AssertionError("Credential redaction failed");
     if(entry.getName().endsWith(".json"))new JSONObject(text);
     if(entry.getName().equals("events.jsonl")){for(String line:text.split("\n")){if(!line.isBlank()){new JSONObject(line);events++;}}}
    }
   }
   if(!names.containsAll(List.of("README.txt","phone-app.json","state-current.json","events.jsonl","phone-network.txt","phone-root-network.txt","phone-tethering.txt","phone-connectivity.txt","adb-and-station.txt","collection.txt")))throw new AssertionError("Missing report sections: "+names);
   if(events==0)throw new AssertionError("No diagnostic events");
   File exportDirectory=DiagnosticProvider.directory(context);exportDirectory.mkdirs();
   File shared=new File(exportDirectory,"AliceBridge-diagnostics-20261025-000000-123.zip");java.nio.file.Files.copy(file.toPath(),shared.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
   android.net.Uri uri=DiagnosticProvider.uri(context,shared);
   try(var cursor=context.getContentResolver().query(uri,null,null,null,null)){if(cursor==null||!cursor.moveToFirst()||cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))!=shared.length())throw new AssertionError("Shared file metadata");}
   try(InputStream input=context.getContentResolver().openInputStream(uri)){if(input==null||input.read()!='P'||input.read()!='K')throw new AssertionError("Shared ZIP cannot be opened");}
   boolean writeRejected=false;try(var fd=context.getContentResolver().openFileDescriptor(uri,"w")){}catch(SecurityException expected){writeRejected=true;}if(!writeRejected)throw new AssertionError("Provider accepted write access");
   boolean traversalRejected=false;try{context.getContentResolver().query(android.net.Uri.parse("content://"+context.getPackageName()+".diagnostics/../session.jsonl"),null,null,null,null);}catch(SecurityException expected){traversalRejected=true;}if(!traversalRejected)throw new AssertionError("Provider path traversal");
   shared.delete();
   result.putString("stream","PASS: Android ZIP export, valid JSON, complete sections, events="+events+", redaction, shared ZIP provider read-only and traversal protection; file="+file+"\n");
  }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("stream","FAIL: "+error+"\n");}
  runner.finish(code,result);
 }
}
