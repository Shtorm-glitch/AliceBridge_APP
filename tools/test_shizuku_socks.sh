#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/shizuku-socks-tests
javac -d build/shizuku-socks-tests tests/shizuku-stubs/android/content/Context.java tests/shizuku-stubs/android/net/Network.java tests/shizuku-stubs/dev/alice/bridge/Session.java app/src/main/java/dev/alice/bridge/DnsWire.java app/src/main/java/dev/alice/bridge/HotspotSocks.java tests/HotspotSocksTest.java
java -cp build/shizuku-socks-tests HotspotSocksTest
