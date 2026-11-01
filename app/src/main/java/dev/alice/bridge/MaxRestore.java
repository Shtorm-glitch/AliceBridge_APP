package dev.alice.bridge;
import android.content.Context;
import java.util.Map;
/** Opt-in applies to one physical speaker, never to its DHCP address. */
final class MaxRestore {
 static boolean enabled(Context c,Map<String,String> info){String key=MaxPlan.restoreKey(info);return !key.isEmpty()&&c.getSharedPreferences("config",0).getBoolean(key,false);}
 static void set(Context c,Map<String,String> info,boolean enabled){String key=MaxPlan.restoreKey(info);if(key.isEmpty())throw new IllegalArgumentException("Не получен MAC Макс");c.getSharedPreferences("config",0).edit().putBoolean(key,enabled).apply();}
}
