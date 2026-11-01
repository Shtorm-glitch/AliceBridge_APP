#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_classes=build/protocol-tests
mkdir -p "$test_classes"
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/DnsWire.java tests/AdbProbe.java app/src/main/java/dev/alice/bridge/HttpWire.java app/src/main/java/dev/alice/bridge/Streams.java app/src/main/java/dev/alice/bridge/LocalNetwork.java app/src/main/java/dev/alice/bridge/ResponsePreview.java tests/ProtocolTest.java
java -cp "$test_classes" ProtocolTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/LocalNetwork.java app/src/main/java/dev/alice/bridge/StationTracker.java tests/StationTrackerTest.java
java -cp "$test_classes" StationTrackerTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/LegacyWpaConfig.java tests/LegacyWpaConfigTest.java
java -cp "$test_classes" LegacyWpaConfigTest
javac -cp "$test_classes" -d "$test_classes" tests/DnsTxtTest.java
java -cp "$test_classes" DnsTxtTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/ProgressInfo.java tests/ProgressInfoTest.java
java -cp "$test_classes" ProgressInfoTest
if [ "$#" -gt 0 ]; then
    javac -d "$test_classes" app/src/main/java/dev/alice/bridge/TlsIdentity.java tests/TlsIdentityTest.java
    java -cp "$test_classes" TlsIdentityTest "$1"
fi
setup_scripts=build/station-scripts
mkdir -p "$setup_scripts"
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/SetupRecovery.java app/src/main/java/dev/alice/bridge/StationSetup.java tests/StationSetupTest.java
java -cp "$test_classes" StationSetupTest "$setup_scripts"
python3 tests/ota_block_test.py
for setup_script in "$setup_scripts"/*.sh; do sh -n "$setup_script"; done
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/RemoteShell.java app/src/main/java/dev/alice/bridge/HomeWifi.java tests/PostSetupSafetyTest.java
java -cp "$test_classes" PostSetupSafetyTest "$setup_scripts"
sh -n "$setup_scripts/home-wifi.sh"
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/StationDiscovery.java tests/StationDiscoveryTest.java
java -cp "$test_classes" StationDiscoveryTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/AdbForward.java tests/AdbForwardTest.java
java -cp "$test_classes" AdbForwardTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/YseCatalog.java tests/YseCatalogTest.java
java -cp "$test_classes" YseCatalogTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/YseJob.java tests/YseJobTest.java
java -cp "$test_classes" YseJobTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/TerminalCommands.java tests/TerminalCommandsTest.java
java -cp "$test_classes" TerminalCommandsTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/RemoteShell.java app/src/main/java/dev/alice/bridge/StationRootProcess.java tests/StationRootProcessTest.java
java -cp "$test_classes" dev.alice.bridge.StationRootProcessTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/SetupRecovery.java app/src/main/java/dev/alice/bridge/StationSetup.java app/src/main/java/dev/alice/bridge/Station1Plan.java app/src/main/java/dev/alice/bridge/DeviceNames.java tests/Station1PlanTest.java
java -cp "$test_classes" Station1PlanTest

javac -d "$test_classes" app/src/main/java/dev/alice/bridge/WifiProfile.java tests/WifiProfileTest.java
java -cp "$test_classes" dev.alice.bridge.WifiProfileTest

javac -d "$test_classes" app/src/main/java/dev/alice/bridge/DiagnosticRedactor.java app/src/main/java/dev/alice/bridge/JournalView.java tests/DiagnosticTest.java
java -cp "$test_classes" dev.alice.bridge.DiagnosticTest

javac -d "$test_classes" app/src/main/java/dev/alice/bridge/YamPaths.java tests/YamPathsTest.java
java -cp "$test_classes" dev.alice.bridge.YamPathsTest

javac -d "$test_classes" app/src/main/java/dev/alice/bridge/ShizukuProbe.java tests/ShizukuProbeTest.java
java -cp "$test_classes" dev.alice.bridge.ShizukuProbeTest

python3 tests/ssh_boot_test.py

javac -cp "$test_classes" -d "$test_classes" app/src/main/java/dev/alice/bridge/MaxPlan.java tests/MaxPlanTest.java app/src/main/java/dev/alice/bridge/MaxRootBridge.java tests/MaxRootBridgeTest.java
java -cp "$test_classes" MaxPlanTest
java -cp "$test_classes" dev.alice.bridge.MaxRootBridgeTest
javac -d "$test_classes" app/src/main/java/dev/alice/bridge/MaxYam.java tests/MaxYamTest.java
java -cp "$test_classes" dev.alice.bridge.MaxYamTest
