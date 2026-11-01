package dev.alicebridge.station;

import dev.alicebridge.bridge.*;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Matches only the currently associated network; never returns another saved password. */
public final class WifiProfile {
    record Credentials(String ssid, String password) {}

    static String currentSsid(String status) {
        int start = status.indexOf("SSID: ");
        if (start < 0) return "";
        start += 6;
        int end = status.indexOf(", BSSID:", start);
        if (end < 0) return "";
        String name = unquote(status.substring(start, end).trim());
        return name.equals("<unknown ssid>") ? "" : name;
    }

    static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")
                ? s.substring(1, s.length() - 1)
                : s;
    }

    static Credentials find(String ssid, String xml) throws Exception {
        if (ssid.isEmpty() || xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) return null;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList networks = doc.getElementsByTagName("Network");
        String found = null;
        for (int i = 0; i < networks.getLength(); i++) {
            Element network = (Element) networks.item(i);
            NodeList configs = network.getElementsByTagName("WifiConfiguration");
            if (configs.getLength() == 0) continue;
            NodeList values = ((Element) configs.item(0)).getElementsByTagName("string");
            String name = "", secret = "";
            for (int j = 0; j < values.getLength(); j++) {
                Element value = (Element) values.item(j);
                if (value.getAttribute("name").equals("SSID"))
                    name = unquote(value.getTextContent());
                if (value.getAttribute("name").equals("PreSharedKey"))
                    secret = unquote(value.getTextContent());
            }
            if (!ssid.equals(name)
                    || secret.equals("*")
                    || secret.length() < 8
                    || secret.length() > 63) continue;
            if (found != null && !found.equals(secret)) return null;
            found = secret;
        }
        return found == null ? null : new Credentials(ssid, found);
    }
}
