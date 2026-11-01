package dev.alicebridge.station;
import android.content.Context;
import java.util.*;
/** One selected station. Only its serialized controller may perform commands. */
public final class StationState implements AutoCloseable {
    public interface Logger { void event(String kind,String source,String detail,String status); }
    public final StationContext context;
    public final NativeAdb adbClient;
    final RetailCaSetup retailCa=new RetailCaSetup(this);
    final Station1Setup station1=new Station1Setup(this);
    final MaxSetup maxSetup=new MaxSetup(this);
    final YseClient yse=new YseClient(this);
    final SshClient ssh=new SshClient(this);
    final ApkgClient apkg=new ApkgClient(this);
    final FastfetchSetup fastfetch=new FastfetchSetup(this);
    final AgentClient agent=new AgentClient(this);
    final EntwareBootstrap entware=new EntwareBootstrap(this);
    final Station1Wifi stationWifi=new Station1Wifi(this);
    final Station1Dns stationDns=new Station1Dns(this);
    final MaxBridgeRegistry maxBridge=new MaxBridgeRegistry();
    private final Logger logger;
    public volatile String station="",adb="",yseStatus="",yseOperation="",sshStatus="",zshStatus="",apkgStatus="";
    public volatile boolean adbConnected,stopRequested,yseReady,sshReady,zshReady;
    public volatile boolean agentReady,agentUpdate;
    public volatile String agentStatus="Не установлена",agentVersion="",agentLatest="",agentReport="",agentTransaction="";
    public volatile int zshRevision;
    public volatile Map<String,String> stationInfo=Map.of();
    public volatile List<StationSetup.Step> setupSteps=List.of();
    public StationState(Context applicationContext, Logger logger) {
        this.logger=logger;context=new StationContext(applicationContext,this);adbClient=new NativeAdb(context);
    }
    public void log(Context unused,String kind,String source,String detail,String status){logger.event(kind,source,detail,status);}
    @Override public void close(){stopRequested=true;maxBridge.stop();adbClient.close();}
}
