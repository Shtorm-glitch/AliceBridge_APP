/** Regression tests execute scripts against fixtures and local PTY emulators only. */
public class MigratedToolsTest {
    private static String directory() throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of("build/station-scripts");
        java.nio.file.Files.createDirectories(p);
        return p.toString();
    }

    @org.junit.Test
    public void stationSetupTest() throws Exception {
        StationSetupTest.main(new String[] {directory()});
    }

    @org.junit.Test
    public void postSetupSafetyTest() throws Exception {
        PostSetupSafetyTest.main(new String[] {directory()});
    }

    @org.junit.Test
    public void maxPlanTest() throws Exception {
        MaxPlanTest.main(new String[0]);
    }

    @org.junit.Test
    public void terminalCommandsTest() throws Exception {
        TerminalCommandsTest.main(new String[0]);
    }

    @org.junit.Test
    public void yamPathsTest() throws Exception {
        dev.alicebridge.station.YamPathsTest.main(new String[0]);
    }

    @org.junit.Test
    public void yseJobTest() throws Exception {
        YseJobTest.main(new String[0]);
    }

    @org.junit.Test
    public void yseCatalogTest() throws Exception {
        YseCatalogTest.main(new String[0]);
    }

    @org.junit.Test
    public void stationRootProcessTest() throws Exception {
        dev.alicebridge.station.StationRootProcessTest.main(new String[0]);
    }
}
