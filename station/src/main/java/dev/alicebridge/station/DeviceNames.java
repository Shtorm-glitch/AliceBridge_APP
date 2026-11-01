package dev.alicebridge.station;
import dev.alicebridge.bridge.*;
import java.util.*;

/** Platform identifiers read from the station, with conservative friendly names. */
public final class DeviceNames {
    private static final Map<String,String> MODELS=Map.ofEntries(
        Map.entry("yandexstation","Станция 1"),Map.entry("tulip-d1","Станция 1"),
        Map.entry("yandexmicro","Станция Лайт 1"),Map.entry("yandex-micro","Станция Лайт 1"),Map.entry("yandex station micro v1.2","Станция Лайт 1"),
        Map.entry("yandexmini","Станция Мини 1"),Map.entry("yandex-mini","Станция Мини 1"),
        Map.entry("yandexmini_2","Станция Мини 2"),Map.entry("yandex-mini2","Станция Мини 2"),Map.entry("yandex,station-mini2","Станция Мини 2"),
        Map.entry("yandexstation_2","Станция Макс"),Map.entry("plum","Станция Лайт 2"),
        Map.entry("cucumber","Станция Миди"),Map.entry("yandex,cucumber-dvt2","Станция Миди"),
        Map.entry("yandex,station-midi","Станция 2"),Map.entry("bergamot","Станция Мини 3"),Map.entry("yandex,station-mini3","Станция Мини 3"),
        Map.entry("mango","Станция Стрит"),Map.entry("yandex station mango dvt","Станция Стрит"),Map.entry("yandex,saturn","Умный хаб Яндекса"));
    public static String name(Map<String,String> info){
        for(String field:List.of("BUILDNAME","PLATFORM","DEVICE","HOSTNAME","MODEL")){
            String value=info.getOrDefault(field,"").trim().toLowerCase(Locale.ROOT);
            if(MODELS.containsKey(value))return MODELS.get(value);
        }
        for(String field:List.of("BUILDNAME","PLATFORM","DEVICE","MODEL")){
            String value=info.getOrDefault(field,"").replaceAll("[\\p{Cntrl}]","").trim();
            if(!value.isEmpty())return value.length()>64?value.substring(0,64):value;
        }
        return "Определяем модель…";
    }
    public static String identifier(Map<String,String> info){for(String key:List.of("BUILDNAME","PLATFORM","DEVICE")){String value=info.getOrDefault(key,"");if(!value.isEmpty())return value;}return "—";}
}
