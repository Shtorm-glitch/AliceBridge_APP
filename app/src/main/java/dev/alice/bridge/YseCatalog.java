package dev.alice.bridge;
import java.util.*;
public final class YseCatalog {
    public record Item(String id,String name,String version,String description,String source){}
    public static List<Item> parse(String text){
        Map<String,Item> items=new LinkedHashMap<>();
        for(String line:text.split("\\R")){
            String[] fields=line.split(" - ",3);if(fields.length<2||!fields[0].matches("[a-zA-Z0-9][a-zA-Z0-9+_.-]*"))continue;
            boolean yap=line.matches(".*\\[yap/[^]]+]\\s*$");String source=yap?"yap":"ent";
            String version=fields[1].replaceFirst("\\s+\\[yap/[^]]+]\\s*$","").trim();
            String desc=fields.length==3?fields[2].replaceFirst("\\s*\\[yap/[^]]+]\\s*$",""):"";
            String id=source+":"+fields[0];items.put(id,new Item(id,fields[0],version,desc,yap?".yap":"Entware"));
        }
        return List.copyOf(items.values());
    }
}
