package guardian

import (
	"strings"
	"testing"
)

func TestWifiBackupSelectsOnlyActualSSID(t *testing.T) {
	config := []byte("network={\nssid=\"other\"\npsk=" + strings.Repeat("a", 64) + "\n}\nnetwork={\nssid=\"home #1\"\npsk=\"password123\"\n}\n")
	n, e := ParseWifiConfig(config, "686f6d65202331")
	if e != nil {
		t.Fatal(e)
	}
	if len(n.PSK) != 64 || n.PSK == strings.Repeat("a", 64) {
		t.Fatal("wrong profile selected")
	}
	if _, e = ParseWifiConfig(config, "missing"); e == nil {
		t.Fatal("unknown SSID selected")
	}
}
func TestMaskedPasswordNeverBecomesRollbackCredential(t *testing.T) {
	if _, e := ParseWifiConfig([]byte("network={\nssid=\"old\"\npsk=*\n}\n"), oldNetwork.SSIDHex); e == nil {
		t.Fatal("masked password accepted")
	}
}
