package dev.alice.bridge;
public final class WifiProfileTest {
    static String profile(String name,String password){return "<Network><WifiConfiguration><string name=\"SSID\">&quot;"+name+"&quot;</string><string name=\"PreSharedKey\">&quot;"+password+"&quot;</string></WifiConfiguration></Network>";}
    public static void main(String[] args)throws Exception{
        if(!WifiProfile.currentSsid("WifiInfo: SSID: \"Home, & more\", BSSID: 01:02, MAC: x").equals("Home, & more"))throw new AssertionError("SSID parsing");
        if(!WifiProfile.currentSsid("Wifi is disabled").isEmpty())throw new AssertionError("disconnected");
        String xml="<WifiConfigStoreData><NetworkList>"+profile("Other","wrongsecret")+profile("Home &amp; more","p&amp;ssword123")+"</NetworkList></WifiConfigStoreData>";
        var credentials=WifiProfile.find("Home & more",xml);
        if(credentials==null||!credentials.password().equals("p&ssword123"))throw new AssertionError("exact match and XML escaping");
        if(WifiProfile.find("Missing",xml)!=null)throw new AssertionError("unrelated profile");
        if(WifiProfile.find("Home","<root>"+profile("Home","password1")+profile("Home","password2")+"</root>")!=null)throw new AssertionError("ambiguous profile");
        if(WifiProfile.find("Home","<!DOCTYPE root SYSTEM 'file:///etc/passwd'><root/>")!=null)throw new AssertionError("external entities");
        if(WifiProfile.find("Home","<root><Network><WifiConfiguration><string name=\"SSID\">&quot;Home&quot;</string><EncryptedData name=\"PreSharedKey\">encrypted</EncryptedData></WifiConfiguration></Network></root>")!=null)throw new AssertionError("encrypted password");
        System.out.println("Wi-Fi profile tests passed");
    }
}
