package guardian

import (
	"context"
	"crypto/pbkdf2"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"alicebridge/agent/assets"
)

const Base = "/data/quasar/alicebridge-agent"

type Device struct {
	Platform        string
	Iface           string
	mu              sync.Mutex
	nextOTA         time.Time
	otaHashes       map[string][32]byte
	ota             string
	nextADB         time.Time
	nextADBProbe    time.Time
	adbProtocolOK   bool
	nextSSHProbe    time.Time
	sshProtocolOK   bool
	nextSSH         time.Time
	sshSuspendUntil atomic.Int64
}

func Detect() (string, error) {
	if _, e := os.Stat("/system/bin/getprop"); e == nil {
		sdk, _ := run(3*time.Second, "/system/bin/getprop", "ro.build.version.sdk")
		if strings.TrimSpace(sdk) != "" {
			board, _ := run(3*time.Second, "/system/bin/getprop", "ro.board.platform")
			if strings.TrimSpace(board) != "tulip" {
				return "android", nil
			}
			return "station1", nil
		}
	}
	if _, e := os.Stat("/system/vendor/quasar/device_ota_update.sh"); e != nil {
		return "", errors.New("unsupported station filesystem")
	}
	return "linux", nil
}
func run(timeout time.Duration, name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Cancel = func() error {
		if cmd.Process == nil {
			return nil
		}
		return cmd.Process.Signal(syscall.SIGTERM)
	}
	cmd.WaitDelay = 5 * time.Second
	var out limitedBuffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	e := cmd.Run()
	if e != nil {
		return out.String(), errors.New("device command failed: " + filepath.Base(name))
	}
	return out.String(), nil
}

type limitedBuffer struct{ data []byte }

func (b *limitedBuffer) Write(p []byte) (int, error) {
	n := len(p)
	if len(b.data) < 32768 {
		left := 32768 - len(b.data)
		if len(p) > left {
			p = p[:left]
		}
		b.data = append(b.data, p...)
	}
	return n, nil
}
func (b *limitedBuffer) String() string { return string(b.data) }
func (d *Device) wpa(args ...string) (string, error) {
	out, e := run(8*time.Second, "wpa_cli", append([]string{"-i", d.Iface}, args...)...)
	if e == nil {
		return out, nil
	}
	fallback, x := run(8*time.Second, "wpa_cli", args...)
	if x != nil {
		return out, e
	}
	for _, line := range strings.Split(fallback, "\n") {
		if strings.HasPrefix(line, "Selected interface '") && strings.HasSuffix(line, "'") {
			name := strings.TrimSuffix(strings.TrimPrefix(line, "Selected interface '"), "'")
			if iface, x := net.InterfaceByName(name); x == nil && iface.Flags&net.FlagLoopback == 0 {
				d.Iface = name
			}
		}
	}
	return fallback, nil
}
func (d *Device) ok(args ...string) error {
	s, e := d.wpa(args...)
	if e != nil || !strings.HasSuffix(strings.TrimSpace(s), "OK") {
		return errors.New("Wi-Fi controller rejected operation")
	}
	return nil
}
func fields(s string) map[string]string {
	m := map[string]string{}
	for _, l := range strings.Split(s, "\n") {
		if k, v, ok := strings.Cut(l, "="); ok {
			m[k] = v
		}
	}
	return m
}
func cleanValue(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}
func ssidHex(s string) (string, error) {
	s = cleanValue(s)
	if strings.HasPrefix(s, "\"") {
		v, e := strconv.Unquote(s)
		if e != nil {
			return "", e
		}
		return hex.EncodeToString([]byte(v)), nil
	}
	if b, e := hex.DecodeString(s); e == nil && len(b) > 0 && len(b) <= 32 {
		return strings.ToLower(s), nil
	}
	return "", errors.New("cannot read SSID")
}
func (d *Device) Link() (Link, error) {
	if d.Platform != "linux" {
		return d.androidLink()
	}
	out, e := d.wpa("status")
	if e != nil {
		return Link{}, e
	}
	p := fields(out)
	l := Link{ID: p["id"], IP: p["ip_address"], Connected: p["wpa_state"] == "COMPLETED"}
	if l.ID != "" {
		v, x := d.wpa("get_network", l.ID, "ssid")
		if x == nil {
			l.SSIDHex, _ = ssidHex(v)
		}
	}
	if ip := net.ParseIP(l.IP); ip == nil || ip.IsUnspecified() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		l.IP = ""
		if iface, e := net.InterfaceByName(d.Iface); e == nil {
			if addresses, e := iface.Addrs(); e == nil {
				for _, address := range addresses {
					ip, _, e := net.ParseCIDR(address.String())
					if e == nil && ip.To4() != nil && !ip.IsUnspecified() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
						l.IP = ip.String()
						break
					}
				}
			}
		}
	}
	return l, nil
}
func (d *Device) Backup() (Network, string, error) {
	if d.Platform != "linux" {
		return d.androidBackup()
	}
	link, e := d.Link()
	if e != nil || link.ID == "" || link.SSIDHex == "" {
		return Network{}, "", errors.New("no active Wi-Fi to roll back to")
	}
	value, e := d.wpa("get_network", link.ID, "psk")
	if e != nil {
		return Network{}, "", e
	}
	value = cleanValue(value)
	if strings.HasPrefix(value, "\"") {
		password, x := strconv.Unquote(value)
		if x != nil {
			return Network{}, "", x
		}
		ssid, _ := hex.DecodeString(link.SSIDHex)
		key, x := pbkdf2.Key(sha1.New, password, ssid, 4096, 32)
		if x != nil {
			return Network{}, "", x
		}
		value = hex.EncodeToString(key)
	}
	n := Network{link.SSIDHex, value}
	if e := n.Validate(); e != nil {
		n, e = backupWifiFile(link.SSIDHex)
		if e != nil {
			return Network{}, "", e
		}
	}
	return n, link.ID, n.Validate()
}
func (d *Device) Join(n Network) (string, error) {
	if e := n.Validate(); e != nil {
		return "", e
	}
	if d.Platform != "linux" {
		return d.androidJoin(n)
	}
	out, e := d.wpa("add_network")
	if e != nil {
		return "", e
	}
	id := cleanValue(out)
	if _, e = strconv.ParseUint(id, 10, 32); e != nil {
		return "", errors.New("cannot allocate Wi-Fi profile")
	}
	good := false
	defer func() {
		if !good {
			d.wpa("remove_network", id)
		}
	}()
	for _, args := range [][]string{{"set_network", id, "ssid", n.SSIDHex}, {"set_network", id, "psk", n.PSK}, {"set_network", id, "key_mgmt", "WPA-PSK"}, {"select_network", id}} {
		if e = d.ok(args...); e != nil {
			return "", e
		}
	}
	if e = d.waitLink(n.SSIDHex, 45*time.Second); e != nil {
		return "", e
	}
	good = true
	return id, nil
}
func (d *Device) waitLink(ssid string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		l, e := d.Link()
		if e == nil && l.Connected && l.IP != "" && l.SSIDHex == ssid {
			return nil
		}
		time.Sleep(time.Second)
	}
	return errors.New("Wi-Fi connection timed out")
}
func (d *Device) Restore(n Network, id string) error {
	if d.Platform != "linux" {
		_, e := d.androidJoin(n)
		return e
	}
	if _, e := strconv.ParseUint(id, 10, 32); e == nil {
		s, e := d.wpa("get_network", id, "ssid")
		h, _ := ssidHex(s)
		if e == nil && h == n.SSIDHex && d.ok("select_network", id) == nil {
			if e = d.waitLink(n.SSIDHex, 25*time.Second); e == nil {
				return nil
			}
		}
	}
	_, e := d.Join(n)
	return e
}
func (d *Device) Persist(id string) error {
	if d.Platform != "linux" {
		return nil
	}
	return d.ok("save_config")
}
func (d *Device) Discard(id string) error {
	if d.Platform != "linux" || id == "" {
		return nil
	}
	if _, e := strconv.ParseUint(id, 10, 32); e != nil {
		return e
	}
	return d.ok("remove_network", id)
}
func listening(port int) bool {
	c, e := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 500*time.Millisecond)
	if e == nil {
		c.Close()
		return true
	}
	return false
}
func executable(p string) bool {
	s, e := os.Stat(p)
	return e == nil && s.Mode().IsRegular() && s.Mode()&0111 != 0
}
func regular(p string) bool { s, e := os.Lstat(p); return e == nil && s.Mode().IsRegular() }
func (d *Device) SuspendSSH(duration time.Duration) {
	d.sshSuspendUntil.Store(time.Now().Add(duration).UnixMilli())
}
func (d *Device) Maintain() (string, string, string, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	now := time.Now()
	var errorsList []string
	adb := "ready"
	if now.After(d.nextADBProbe) {
		d.nextADBProbe = now.Add(time.Minute)
		d.adbProtocolOK = adbHealthy("127.0.0.1:5555")
	}
	if !listening(5555) || !d.adbProtocolOK {
		adb = "unavailable"
		if now.After(d.nextADB) {
			d.nextADB = now.Add(30 * time.Second)
			if d.Platform != "linux" {
				run(3*time.Second, "/system/bin/setprop", "persist.adb.tcp.port", "5555")
				run(3*time.Second, "/system/bin/setprop", "service.adb.tcp.port", "5555")
				run(3*time.Second, "/system/bin/setprop", "ctl.restart", "adbd")
			} else {
				// Reuse the firmware's own supported ADB activation entry point.
				for _, p := range []string{"/data/quasar/activate_adb_quasmodrom", "/data/quasar/activate_network_adb"} {
					if !regular(p) {
						if _, e := os.Lstat(p); e != nil && os.IsNotExist(e) {
							f, x := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0644)
							if x == nil {
								f.Close()
							}
						}
					}
					run(3*time.Second, "chattr", "+i", p)
				}
				for _, p := range []string{"/system/vendor/quasar/activate_adb.sh", "/usr/bin/activate_adb.sh"} {
					if regular(p) {
						run(10*time.Second, "/bin/sh", p, "check")
						break
					}
				}
			}
			d.adbProtocolOK = adbHealthy("127.0.0.1:5555")
			if d.adbProtocolOK {
				adb = "ready"
			} else {
				adb = "recovery_pending"
			}
		}
	}
	ssh := "not_installed"
	start := "/etc/init.d/S99zalicebridge-ssh"
	port := 2222
	if d.Platform != "linux" {
		start = "/data/dropbear/start.sh"
		port = 22
	}
	if regular(start) {
		if now.After(d.nextSSHProbe) {
			d.nextSSHProbe = now.Add(time.Minute)
			d.sshProtocolOK = sshHealthy(fmt.Sprintf("127.0.0.1:%d", port))
		}
		ssh = "ready"
		if !listening(port) || !d.sshProtocolOK {
			ssh = "unavailable"
			if now.After(d.nextSSH) && now.UnixMilli() > d.sshSuspendUntil.Load() {
				d.nextSSH = now.Add(time.Minute)
				sh := "/bin/sh"
				if d.Platform != "linux" {
					sh = "/system/bin/sh"
				}
				args := []string{start}
				if d.Platform == "linux" {
					args = append(args, "start")
				}
				run(10*time.Second, sh, args...)
				d.sshProtocolOK = sshHealthy(fmt.Sprintf("127.0.0.1:%d", port))
				if d.sshProtocolOK {
					ssh = "ready"
				} else {
					ssh = "recovery_pending"
				}
			}
		}
	}
	if d.Platform == "android" {
		d.ota = "unverified"
	} else if now.After(d.nextOTA) {
		d.nextOTA = now.Add(time.Minute)
		paths := []string{"/system/vendor/quasar/device_ota_update.sh"}
		if d.Platform == "linux" {
			paths = append(paths, "/system/vendor/quasar/ota_script.pub")
		}
		changed := len(d.otaHashes) != len(paths)
		for _, p := range paths {
			if h, e := fileHash(p); e != nil || h != d.otaHashes[p] {
				changed = true
			}
		}
		if changed {
			name, sh := "linux-ota.sh", "/bin/sh"
			if d.Platform != "linux" {
				name, sh = "station1-ota.sh", "/system/bin/sh"
			}
			script, e := assets.Scripts.ReadFile(name)
			if e == nil {
				_, e = run(30*time.Second, sh, "-c", "trap 'exit 143' TERM INT\n"+string(script))
			}
			hashes := map[string][32]byte{}
			if e == nil {
				for _, p := range paths {
					var h [32]byte
					h, e = fileHash(p)
					if e != nil {
						break
					}
					hashes[p] = h
				}
			}
			if e != nil {
				d.ota = "failed"
				errorsList = append(errorsList, "OTA protection not verified")
			} else {
				d.otaHashes = hashes
				d.ota = "protected"
			}
		}
	}
	if e := freezeUpdatesd(); e != nil {
		errorsList = append(errorsList, e.Error())
		d.ota = "failed"
	}

	if len(errorsList) > 0 {
		return adb, ssh, d.ota, errors.New(strings.Join(errorsList, "; "))
	}
	return adb, ssh, d.ota, nil
}
func fileHash(path string) ([32]byte, error) {
	st, e := os.Lstat(path)
	if e != nil {
		return [32]byte{}, e
	}
	if !st.Mode().IsRegular() || st.Size() > 1<<20 {
		return [32]byte{}, errors.New("invalid OTA target")
	}
	b, e := os.ReadFile(path)
	if e != nil {
		return [32]byte{}, e
	}
	return sha256.Sum256(b), nil
}
func freezeUpdatesd() error {
	paths, _ := filepath.Glob("/proc/[0-9]*/cmdline")
	for _, p := range paths {
		data, e := os.ReadFile(p)
		if e != nil {
			continue
		}
		argv := strings.Split(string(data), "\x00")
		if len(argv) < 2 || filepath.Base(argv[0]) != "maind" || argv[1] != "--updatesd" {
			continue
		}
		pid, e := strconv.Atoi(filepath.Base(filepath.Dir(p)))
		if e != nil {
			continue
		}
		if e = syscall.Kill(pid, syscall.SIGSTOP); e != nil && e != syscall.ESRCH {
			return errors.New("cannot stop updatesd")
		}
	}
	return nil
}

const helper = "/data/quasar/alicebridge-home-wifi/helper.jar"

func (d *Device) android(args ...string) (string, error) {
	if !regular(helper) {
		return "", errors.New("station1 Wi-Fi helper not installed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 65*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "/system/bin/app_process", append([]string{"/system/bin", "dev.alice.station.StationWifi"}, args...)...)
	cmd.Env = append(os.Environ(), "CLASSPATH="+helper)
	var out limitedBuffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	e := cmd.Run()
	if e != nil {
		return "", errors.New("Android Wi-Fi operation failed")
	}
	return out.String(), nil
}
func (d *Device) androidLink() (Link, error) {
	s, e := d.android("probe")
	if e != nil {
		return Link{}, e
	}
	f := fields(s)
	return Link{SSIDHex: f["SSID_HEX"], IP: f["IP"], ID: f["ID"], Connected: f["STATE"] == "COMPLETED"}, nil
}
func (d *Device) androidBackup() (Network, string, error) {
	file, err := os.CreateTemp("/data/quasar/alicebridge-home-wifi", "agent-backup-")
	if err != nil {
		return Network{}, "", err
	}
	path := file.Name()
	file.Close()
	os.Remove(path)
	defer os.Remove(path)
	s, e := d.android("backup", path)
	if e != nil {
		link, probeErr := d.androidLink()
		if probeErr != nil {
			return Network{}, "", e
		}
		saved, readErr := backupWifiFile(link.SSIDHex)
		return saved, link.ID, readErr
	}
	b, e := os.ReadFile(path)
	os.Remove(path)
	if e != nil {
		return Network{}, "", e
	}
	parts := strings.Split(strings.TrimSpace(string(b)), "\n")
	if len(parts) != 2 {
		return Network{}, "", errors.New("invalid Android Wi-Fi backup")
	}
	n := Network{parts[0], parts[1]}
	return n, fields(s)["ID"], n.Validate()
}
func (d *Device) androidJoin(n Network) (string, error) {
	f, e := os.CreateTemp("/data/quasar/alicebridge-home-wifi", "agent-credentials-")
	if e != nil {
		return "", e
	}
	p := f.Name()
	defer os.Remove(p)
	if e = f.Chmod(0600); e == nil {
		_, e = f.WriteString(n.SSIDHex + "\n" + n.PSK + "\n")
	}
	f.Close()
	if e != nil {
		return "", e
	}
	out, e := d.android("switch", p)
	if e != nil || !strings.Contains(out, "CONNECTED") {
		return "", errors.New("Android Wi-Fi change failed")
	}
	l, e := d.androidLink()
	return l.ID, e
}
