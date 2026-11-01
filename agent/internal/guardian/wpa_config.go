package guardian

import (
	"crypto/pbkdf2"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

func networkFromValues(ssid, psk string) (Network, error) {
	h, e := ssidHex(ssid)
	if e != nil {
		return Network{}, e
	}
	if strings.HasPrefix(psk, "\"") {
		password, e := strconv.Unquote(psk)
		if e != nil {
			return Network{}, e
		}
		salt, _ := hex.DecodeString(h)
		key, e := pbkdf2.Key(sha1.New, password, salt, 4096, 32)
		if e != nil {
			return Network{}, e
		}
		psk = hex.EncodeToString(key)
	}
	n := Network{h, strings.ToLower(psk)}
	return n, n.Validate()
}
func ParseWifiConfig(data []byte, ssid string) (Network, error) {
	if len(data) > 1<<20 {
		return Network{}, errors.New("Wi-Fi config too large")
	}
	inside := false
	values := map[string]string{}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "#") {
			continue
		}
		if strings.ReplaceAll(line, " ", "") == "network={" {
			inside = true
			values = map[string]string{}
			continue
		}
		if inside && line == "}" {
			n, e := networkFromValues(values["ssid"], values["psk"])
			if e == nil && n.SSIDHex == ssid {
				return n, nil
			}
			inside = false
			continue
		}
		if inside {
			if k, v, ok := strings.Cut(line, "="); ok {
				values[strings.TrimSpace(k)] = strings.TrimSpace(v)
			}
		}
	}
	return Network{}, errors.New("current Wi-Fi credentials not available")
}
func backupWifiFile(ssid string) (Network, error) {
	candidates := []string{}
	paths, _ := filepath.Glob("/proc/[0-9]*/cmdline")
	for _, p := range paths {
		b, e := os.ReadFile(p)
		if e != nil {
			continue
		}
		argv := strings.Split(string(b), "\x00")
		if len(argv) == 0 || filepath.Base(argv[0]) != "wpa_supplicant" {
			continue
		}
		for i, a := range argv {
			if a == "-c" && i+1 < len(argv) {
				candidates = append(candidates, argv[i+1])
			} else if strings.HasPrefix(a, "-c") && len(a) > 2 {
				candidates = append(candidates, a[2:])
			}
		}
	}
	candidates = append(candidates, "/bkupgrade/data/wpa_supplicant.conf", "/data/wpa_supplicant.conf", "/data/misc/wifi/wpa_supplicant.conf", "/etc/wpa_supplicant.conf", "/etc/wpa_supplicant/wpa_supplicant.conf")
	for _, p := range candidates {
		if !filepath.IsAbs(p) {
			continue
		}
		st, e := os.Stat(p)
		if e != nil || !st.Mode().IsRegular() || st.Size() > 1<<20 {
			continue
		}
		b, e := os.ReadFile(p)
		if e != nil {
			continue
		}
		if n, e := ParseWifiConfig(b, ssid); e == nil {
			return n, nil
		}
	}
	return Network{}, errors.New("cannot back up current Wi-Fi; network was not changed")
}
