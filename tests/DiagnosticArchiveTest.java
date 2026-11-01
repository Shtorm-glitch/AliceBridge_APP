package dev.alice.bridge;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public final class DiagnosticArchiveTest {
 public static void main(String[] args)throws Exception{
  File journal=File.createTempFile("alice-diagnostic-test-",".jsonl");
  try{
   try(Writer out=new OutputStreamWriter(new FileOutputStream(journal),StandardCharsets.UTF_8)){
    for(int n=0;n<1200;n++)out.write(new JSONObject(Map.of("n",n,"detail","adb timeout 192.168.3.33:5555 password=old-secret-123","nested",new JSONObject(Map.of("token","nested-token-123")),"json_text","{\"password\":\"embedded-secret-123\"}")).toString()+"\n");
    out.write("broken tail token=corrupt-secret-123");
   }
   ByteArrayOutputStream output=new ByteArrayOutputStream();
   try(DiagnosticArchive archive=new DiagnosticArchive(output,new DiagnosticRedactor(List.of("known-secret-123")))){
    archive.put("state.json",new JSONObject(Map.of("password","known-secret-123","target","192.168.3.33","array",new JSONArray().put(new JSONObject(Map.of("access_token","array-secret-123"))))).toString());
    archive.put("failure.txt","Root unavailable: timeout\nAuthorization: OAuth auth-secret-123");archive.journal(journal);
   }
   Map<String,String> entries=new HashMap<>();
   try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(output.toByteArray()),StandardCharsets.UTF_8)){ZipEntry entry;while((entry=input.getNextEntry())!=null)entries.put(entry.getName(),new String(input.readAllBytes(),StandardCharsets.UTF_8));}
   if(entries.size()!=3)throw new AssertionError("Missing ZIP entries");
   JSONObject state=new JSONObject(entries.get("state.json"));if(!state.getString("target").equals("192.168.3.33"))throw new AssertionError("Missing diagnostic state");
   String[] lines=entries.get("events.jsonl").split("\n");if(lines.length!=1201)throw new AssertionError("History truncated to UI limit");
   for(String line:lines)new JSONObject(line);
   String all=entries.toString();for(String secret:List.of("old-secret-123","nested-token-123","embedded-secret-123","corrupt-secret-123","known-secret-123","array-secret-123","auth-secret-123"))if(all.contains(secret))throw new AssertionError("Secret leaked");
   if(!all.contains("adb timeout 192.168.3.33:5555"))throw new AssertionError("Failure evidence lost");
   System.out.println("ZIP roundtrip, full history, malformed tail, nested redaction: passed");
  }finally{journal.delete();}
 }
}
