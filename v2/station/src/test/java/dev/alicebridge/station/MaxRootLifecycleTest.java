package dev.alicebridge.station;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class MaxRootLifecycleTest {
    private static final class FakeProcess extends Process {
        final InputStream output; final PipedOutputStream keepOpen; boolean live=true;
        FakeProcess(String text)throws IOException {output=new ByteArrayInputStream(text.getBytes());keepOpen=null;}
        FakeProcess()throws IOException {PipedInputStream in=new PipedInputStream();keepOpen=new PipedOutputStream(in);output=in;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return output;}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public int waitFor(){return 0;}
        public int exitValue(){return 0;}
        public boolean isAlive(){return live;}
        public void destroy(){live=false;try{if(keepOpen!=null)keepOpen.close();output.close();}catch(IOException ignored){}}
    }
    @Test public void partialWorkerIsRetainedButNotComplete()throws Exception {
        MaxBridgeRegistry registry=new MaxBridgeRegistry();
        MaxRootBridge worker=new MaxRootBridge(new FakeProcess(),"192.168.1.2","boot",line->{});
        registry.activate(worker);
        try {
            assertTrue(registry.ready("192.168.1.2"));assertFalse(registry.complete("192.168.1.2"));
            registry.confirmCacheGuard();assertFalse(registry.shellReady("192.168.1.2"));assertSame(worker,registry.current("192.168.1.2"));
            registry.confirmPermissive();assertTrue(registry.shellReady("192.168.1.2"));assertFalse(registry.complete("192.168.1.2"));
            registry.confirmAdbSettings();assertTrue(registry.complete("192.168.1.2"));assertFalse(registry.ready("192.168.1.3"));
        } finally {registry.stop();}
        assertFalse(registry.ready("192.168.1.2"));assertFalse(registry.complete("192.168.1.2"));
    }
    @Test(timeout=2000) public void explicitReaderFailureDoesNotWaitFiveMinutes()throws Exception {
        MaxRootBridge worker=new MaxRootBridge(new FakeProcess("bridge: reader bring-up failed\n"),"host","boot",line->{});
        try {worker.awaitReady();fail("Failure must be reported");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("reader bring-up failed"));}
        finally {worker.close();}
    }
    @Test public void workerProofRequiresRootCapabilitiesAndContext(){
        String status="Name:\tworker\nUid:\t0\t0\t0\t0\nCapEff:\t0000003fffffffff\nnonvoluntary_ctxt_switches:\t1\n";
        MaxPlan.requireWorkerIdentity(status,"u:r:init:s0");
        for(String invalid:new String[]{status.replace("Uid:\t0\t0\t0\t0","Uid:\t2000\t2000\t2000\t2000"),status.replace("0000003fffffffff","0000000000000000")}){
            try {MaxPlan.requireWorkerIdentity(invalid,"u:r:init:s0");fail("Invalid identity accepted");}catch(IllegalArgumentException expected){}
        }
        try {MaxPlan.requireWorkerIdentity(status,"u:r:shell:s0");fail("Wrong context");}catch(IllegalArgumentException expected){}
    }
    @Test public void adbSettingsDoNotDisableAuthOrRestartAdbd(){
        String script=MaxAdbSettings.script();
        assertTrue(script.contains("persist.adb.tcp.port 5555"));assertTrue(script.contains("AB_MAX_ADB_SETTINGS_SAVED"));
        for(String bad:new String[]{"ro.adb.secure","ctl.restart","stop adbd","start adbd","persist.sys.usb.config","dd if=","remount"})assertFalse(bad,script.contains(bad));
    }
}
