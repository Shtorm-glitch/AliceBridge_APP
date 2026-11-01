import dev.alice.bridge.YseCatalog;
public class YseCatalogTest {public static void main(String[] args){
 var list=YseCatalog.parse("noise\nhtop - 3.4.1-1 - Interactive process viewer\njq - 1.7.1 [yap/yapkg]\njq - 1.7.1 - JSON processor [yap/yapkg]\nlibc - 2.27-12\n../escape - 1\n");
 if(list.size()!=3)throw new AssertionError(list);
 if(!list.get(0).id().equals("ent:htop"))throw new AssertionError();
 if(!list.get(1).id().equals("yap:jq")||!list.get(1).description().equals("JSON processor")||!list.get(1).version().equals("1.7.1"))throw new AssertionError();
 if(!list.get(2).id().equals("ent:libc"))throw new AssertionError();
 System.out.println("PASS: YSE Entware/YAP source parsing, deduplication and invalid rows");
}}
