package dev.alice.bridge;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Streaming full history, independent of the bounded UI list. */
final class DiagnosticArchive implements AutoCloseable {
    private final ZipOutputStream zip;
    private final DiagnosticRedactor redact;
    DiagnosticArchive(OutputStream destination,DiagnosticRedactor redact){this.zip=new ZipOutputStream(destination,StandardCharsets.UTF_8);this.redact=redact;}
    void put(String name,String text)throws IOException{
        if(name.endsWith(".json")){try{text=sanitize(new JSONObject(text)).toString(2);}catch(JSONException bad){text=new JSONObject(Map.of("unparsed",redact.clean(text))).toString();}}
        else text=redact.clean(text);
        zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    long journal(File source)throws IOException{
        long length=source.length();zip.putNextEntry(new ZipEntry("events.jsonl"));
        if(source.exists())try(InputStream input=new BufferedInputStream(new FileInputStream(source))){
            ByteArrayOutputStream line=new ByteArrayOutputStream();
            for(long n=0;n<length;n++){
                int b=input.read();if(b<0)break;
                if(b=='\n'){event(new String(line.toByteArray(),StandardCharsets.UTF_8));line.reset();}
                else{line.write(b);if(line.size()>=4*1024*1024){event(new String(line.toByteArray(),StandardCharsets.UTF_8));line.reset();}}
            }
            if(line.size()>0)event(new String(line.toByteArray(),StandardCharsets.UTF_8));
        }
        zip.closeEntry();return length;
    }
    private void event(String text)throws IOException{
        try{text=sanitize(new JSONObject(text)).toString();}catch(JSONException bad){text=new JSONObject(Map.of("unparsed",redact.clean(text))).toString();}
        zip.write((text+"\n").getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject sanitize(JSONObject source)throws JSONException{
        JSONObject out=new JSONObject();Iterator<String> keys=source.keys();
        while(keys.hasNext()){String key=keys.next();out.put(key,DiagnosticRedactor.secretKey(key)?"<скрыто>":value(source.get(key)));}return out;
    }
    private Object value(Object v)throws JSONException{
        if(v instanceof JSONObject)return sanitize((JSONObject)v);
        if(v instanceof JSONArray){JSONArray result=new JSONArray(),input=(JSONArray)v;for(int i=0;i<input.length();i++)result.put(value(input.get(i)));return result;}
        if(v instanceof String){String text=(String)v;try{if(text.trim().startsWith("{"))return sanitize(new JSONObject(text)).toString();if(text.trim().startsWith("["))return value(new JSONArray(text)).toString();}catch(JSONException ignored){}return redact.clean(text);}
        return v;
    }
    @Override public void close()throws IOException{zip.close();}
}
