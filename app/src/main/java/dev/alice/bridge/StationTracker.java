package dev.alice.bridge;

import java.util.*;

/** Keeps the selected hotspot client across DHCP changes using its link-layer identity. */
public final class StationTracker {
    private String address = "", mac = "";
    private final Map<String, Long> queries = new HashMap<>();
    public synchronized Map<String,Object> diagnostics(){Map<String,Object> result=new LinkedHashMap<>();result.put("address",address);result.put("mac",mac);result.put("dns_seen_at",new LinkedHashMap<>(queries));return result;}
    public synchronized void reset() { address = ""; mac = ""; queries.clear(); }
    public synchronized String observe(String ip, long now) {
        queries.put(ip, now);
        if (queries.size() > 128) queries.entrySet().removeIf(e -> now - e.getValue() > 120000);
        if (address.isEmpty()) address = ip;
        return address;
    }
    public synchronized String discoverNeighbors(String neighbors, LocalNetwork network, String expectedMac, long now) {
        if(!address.isEmpty())return refresh(neighbors,network);
        Map<String,String> candidates=new LinkedHashMap<>();
        for(String line:neighbors.split("\n")){
            String[] fields=line.trim().split("\\s+");List<String> values=Arrays.asList(fields);int at=values.indexOf("lladdr");
            if(fields.length<3 || !network.client(fields[0]) || at<0 || at+1>=fields.length || values.contains("FAILED") || values.contains("INCOMPLETE"))continue;
            String identity=fields[at+1].toLowerCase(Locale.ROOT);
            if(identity.matches("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}") && !identity.equals("00:00:00:00:00:00"))candidates.put(fields[0],identity);
        }
        List<String> matches=new ArrayList<>();
        for(Map.Entry<String,String> c:candidates.entrySet())if(!expectedMac.isEmpty() && c.getValue().equalsIgnoreCase(expectedMac))matches.add(c.getKey());
        String candidate=matches.size()==1?matches.get(0):candidates.size()==1?candidates.keySet().iterator().next():"";
        if(!candidate.isEmpty()){observe(candidate,now);return refresh(neighbors,network);}return "";
    }
    public synchronized String refresh(String neighbors, LocalNetwork network) {
        Map<String, String> identities = new HashMap<>();
        Set<String> reachable = new HashSet<>();
        for (String line : neighbors.split("\n")) {
            String[] words = line.trim().split("\\s+");
            if (words.length < 3 || !network.client(words[0])) continue;
            List<String> fields = Arrays.asList(words);
            int at = fields.indexOf("lladdr");
            if (at < 0 || at + 1 >= words.length || fields.contains("FAILED") || fields.contains("INCOMPLETE")) continue;
            String identity = words[at + 1].toLowerCase(Locale.ROOT);
            if (!identity.matches("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}") || identity.equals("00:00:00:00:00:00")) continue;
            identities.put(words[0], identity);
            if (fields.contains("REACHABLE") || fields.contains("DELAY") || fields.contains("PROBE")) reachable.add(words[0]);
        }
        if (mac.isEmpty() && identities.containsKey(address)) mac = identities.get(address);
        if (mac.isEmpty()) return address;
        String candidate = address;
        long latest=queries.getOrDefault(address,Long.MIN_VALUE);
        // A fresh DNS packet is stronger evidence than a retained neighbor-cache state.
        // Never roll a freshly observed DHCP address back to an old REACHABLE entry.
        for(Map.Entry<String,String> neighbor:identities.entrySet()){
            if(!mac.equals(neighbor.getValue()))continue;
            long seen=queries.getOrDefault(neighbor.getKey(),Long.MIN_VALUE);
            if(seen>latest){candidate=neighbor.getKey();latest=seen;}
        }
        if(candidate.equals(address)&&!identities.containsKey(address)){
            List<String> alternatives=new ArrayList<>();
            for(Map.Entry<String,String> neighbor:identities.entrySet()){
                long seen=queries.getOrDefault(neighbor.getKey(),Long.MIN_VALUE);
                if(mac.equals(neighbor.getValue())&&reachable.contains(neighbor.getKey())&&(seen==Long.MIN_VALUE||seen>=latest))alternatives.add(neighbor.getKey());
            }
            if(alternatives.size()==1)candidate=alternatives.get(0);
        }
        address = candidate;
        return address;
    }
}
