package dev.alice.bridge;

import java.util.*;

/** Only edits a generated, session-local hostapd configuration. Never logs credentials. */
public final class LegacyWpaConfig {
    private LegacyWpaConfig() {}
    public static String mixed(String original) {
        Map<String,String> changes=new LinkedHashMap<>();
        changes.put("wpa","3");
        changes.put("wpa_pairwise","TKIP CCMP");
        changes.put("rsn_pairwise","CCMP");
        changes.put("group_cipher","CCMP");
        changes.put("ieee80211w","0");
        StringBuilder out=new StringBuilder();
        for(String line:original.split("\n",-1)) {
            int at=line.indexOf('=');String key=at<0?"":line.substring(0,at).trim();
            if(!changes.containsKey(key))out.append(line).append('\n');
        }
        changes.forEach((k,v)->out.append(k).append('=').append(v).append('\n'));
        return out.toString();
    }
    public static String value(String config,String key) {
        String result="";
        for(String line:config.split("\n"))if(line.startsWith(key+"="))result=line.substring(key.length()+1).trim();
        return result;
    }
    public static boolean isMixed(String config) {
        return value(config,"wpa").equals("3") && value(config,"group_cipher").equals("CCMP") &&
            Arrays.asList(value(config,"wpa_pairwise_cipher").split("\\s+")).contains("TKIP") &&
            Arrays.asList(value(config,"rsn_pairwise_cipher").split("\\s+")).contains("CCMP");
    }
}
