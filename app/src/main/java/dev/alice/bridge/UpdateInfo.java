package dev.alice.bridge;

import org.json.*;
import java.net.URI;
import java.util.*;

final class UpdateInfo {
    static final String ORIGIN="http://78.24.223.83";
    record Release(long code,String version,String url,String sha256,long size,List<String> changes) {}
    static Release parse(String json)throws Exception{
        JSONObject v=new JSONObject(json);long code=v.getLong("versionCode"),size=v.getLong("size");String version=v.getString("versionName"),url=v.getString("url"),sha=v.getString("sha256");
        URI uri=new URI(url);
        if(code<1||size<1024||size>150L*1024*1024||!version.matches("[0-9]+(?:\\.[0-9]+){1,3}")||!sha.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Некорректные сведения об обновлении");
        if(!"http".equals(uri.getScheme())||!"78.24.223.83".equals(uri.getHost())||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().equals("/AliceBridge-"+version+".apk"))throw new IllegalArgumentException("Неизвестный адрес обновления");
        List<String> changes=new ArrayList<>();JSONArray list=v.getJSONArray("changes");for(int i=0;i<list.length()&&i<30;i++){String line=list.getString(i);if(line.length()>2000)throw new IllegalArgumentException("Слишком длинное описание обновления");changes.add(line);}
        return new Release(code,version,url,sha.toLowerCase(Locale.ROOT),size,List.copyOf(changes));
    }
}
