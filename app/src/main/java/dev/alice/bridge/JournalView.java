package dev.alice.bridge;

import java.text.SimpleDateFormat;
import java.util.*;

final class JournalView {
    record Entry(long time,String kind,String source,String detail,String status) {}
    static String format(List<Entry> entries,boolean detailed){
        StringBuilder out=new StringBuilder();SimpleDateFormat clock=new SimpleDateFormat("HH:mm:ss",Locale.getDefault());
        Set<String> shown=new HashSet<>();int displayed=0;
        Map<String,Integer> counts=new HashMap<>();
        for(Entry e:entries)if(noisy(e))counts.merge(key(e),1,Integer::sum);
        for(int i=entries.size()-1;i>=0&&displayed<100;i--){
            Entry e=entries.get(i);String key=key(e);
            if(!detailed&&noisy(e)&&!shown.add(key)){continue;}
            out.append(clock.format(new Date(e.time))).append(" · ").append(e.kind).append(" · ").append(e.status);
            int count=counts.getOrDefault(key,1);if(!detailed&&noisy(e)&&count>1)out.append(" · ×").append(count);
            out.append('\n').append(e.source);
            if(!detailed&&e.kind.equals("DNS"))out.append(" · ").append(question(e));
            if(detailed)out.append('\n').append(e.detail);
            else if(!noisy(e)&&!e.detail.isBlank())out.append(" · ").append(e.detail.length()>260?e.detail.substring(0,260)+"…":e.detail);
            out.append("\n\n");displayed++;
        }
        if(out.length()==0)return "Здесь появятся события подключения и настройки.";
        if(!detailed)out.insert(0,"Повторы ADB и DNS сгруппированы. Полный вывод — в подробном режиме и файле диагностики.\n\n");
        return out.toString();
    }
    private static String question(Entry e){int end=e.detail.indexOf(" → ");return end>=0?e.detail.substring(0,end):e.detail.split(" · ",2)[0];}
    private static String key(Entry e){return e.kind+"|"+e.source+"|"+e.status+(e.kind.equals("DNS")?"|"+question(e):"");}
    private static boolean noisy(Entry e){return e.kind.equals("ADB")||e.kind.equals("DNS");}
}
