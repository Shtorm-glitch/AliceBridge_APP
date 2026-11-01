import dev.alice.bridge.*;

public final class StationTrackerTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        StationTracker tracker = new StationTracker();
        LocalNetwork network = new LocalNetwork("10.52.145.114", 24);
        String old = "10.52.145.190", next = "10.52.145.191", other = "10.52.145.192";
        String mac = "aa:bb:cc:dd:ee:01";
        check(tracker.observe(old, 1000).equals(old), "Select first DNS client");
        tracker.refresh(old + " lladdr " + mac + " REACHABLE", network);
        check(tracker.observe(other, 2000).equals(old), "Another DNS client cannot take over");
        check(tracker.refresh(other + " lladdr aa:bb:cc:dd:ee:02 REACHABLE", network).equals(old), "Other MAC cannot take over");
        tracker.observe(next, 3000);
        check(tracker.refresh(old + " FAILED\n" + next + " lladdr " + mac + " REACHABLE", network).equals(next), "Reboot plus DHCP change follows same MAC");
        check(tracker.refresh(old + " lladdr " + mac + " STALE\n" + next + " lladdr " + mac + " REACHABLE", network).equals(next), "Retained old neighbor does not roll IP back");
        check(tracker.refresh(other + " lladdr " + mac + " FAILED", network).equals(next), "Failed neighbor never selected");
        tracker.observe(old, 4000);
        check(tracker.refresh(old + " lladdr " + mac + " REACHABLE\n" + next + " lladdr " + mac + " STALE", network).equals(old), "Second reconnect can restore earlier IP");
        tracker.observe(next,5000);
        check(tracker.refresh(old+" lladdr "+mac+" REACHABLE\n"+next+" lladdr "+mac+" STALE",network).equals(next),"Fresh DNS wins over stale REACHABLE cache at the old IP");
        check(tracker.refresh(old+" lladdr "+mac+" PROBE\n"+next+" lladdr "+mac+" STALE",network).equals(next),"NUD probing cannot roll the client back without fresh traffic");
        tracker.reset();
        check(tracker.observe(other, 5000).equals(other), "New session forgets previous client identity");
        tracker.refresh(other + " lladdr aa:bb:cc:dd:ee:02 REACHABLE", network);
        check(tracker.refresh("192.168.3.28 lladdr aa:bb:cc:dd:ee:02 REACHABLE", network).equals(other), "Ignore addresses outside hotspot");
        System.out.println("PASS: 9 station address tracking checks");
    }
}
