package dev.alice.bridge;

import java.util.*;
import java.util.regex.*;

/** Applied to every exported text, including historical events from older app versions. */
final class DiagnosticRedactor {
    private final List<String> secrets;
    DiagnosticRedactor(Collection<String> values){secrets=new ArrayList<>();for(String value:values)if(value!=null&&value.length()>=8)secrets.add(value);secrets.sort(Comparator.comparingInt(String::length).reversed());}
    static boolean secretKey(String key){return key.toLowerCase(Locale.ROOT).matches(".*(password|passphrase|presharedkey|psk|token|secret|cookie|authorization|activation_code|private.?key|backend_session).*");}
    String clean(String text){
        if(text==null)return "";
        String out=text;
        for(String secret:secrets)out=out.replace(secret,"<скрыто>");
        out=out.replaceAll("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----","<закрытый ключ скрыт>");
        out=out.replaceAll("(?im)(\\b(?:authorization|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]+","$1<скрыто>");
        out=out.replaceAll("(?i)(\\b(?:password|passphrase|preSharedKey|psk|token|access_token|refresh_token|conversationToken|activation_code|backend_session|secret)\\b[\\\"']?\\s*[=:]\\s*)(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'[^']*'|[^\\s&,;<>]+)","$1<скрыто>");
        out=out.replaceAll("(?i)status\\.[a-z0-9-]+\\.alicebridge\\.invalid","status.<сеанс скрыт>.alicebridge.invalid");
        out=out.replaceAll("(?is)(<string\\s+name=[\"'](?:PreSharedKey|Password)[\"']>).*?(</string>)","$1<скрыто>$2");
        return out;
    }
}
