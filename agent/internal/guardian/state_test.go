package guardian

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeBackend struct {
	mu          sync.Mutex
	link        Link
	old         Network
	joinFail    bool
	persistFail bool
	restores    int
	joins       int
	order       []string
}

var oldNetwork = Network{SSIDHex: "6f6c64", PSK: strings.Repeat("a", 64)}
var newNetwork = Network{SSIDHex: "6e6577", PSK: strings.Repeat("b", 64)}

func (f *fakeBackend) Link() (Link, error)              { f.mu.Lock(); defer f.mu.Unlock(); return f.link, nil }
func (f *fakeBackend) Backup() (Network, string, error) { return f.old, "0", nil }
func (f *fakeBackend) Join(n Network) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.joins++
	f.order = append(f.order, "join")
	if f.joinFail {
		return "", errors.New("failed")
	}
	f.link = Link{SSIDHex: n.SSIDHex, IP: "192.168.1.9", Connected: true, ID: "1"}
	return "1", nil
}
func (f *fakeBackend) Restore(n Network, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.restores++
	f.link = Link{SSIDHex: n.SSIDHex, IP: "192.168.43.2", Connected: true, ID: id}
	return nil
}
func (f *fakeBackend) Persist(string) error {
	if f.persistFail {
		return errors.New("save failed")
	}
	return nil
}
func (f *fakeBackend) Discard(string) error     { return nil }
func (f *fakeBackend) SuspendSSH(time.Duration) {}
func (f *fakeBackend) Maintain() (string, string, string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.order = append(f.order, "maintain")
	return "ready", "not_installed", "protected", nil
}
func fixture(t *testing.T) (*Engine, *fakeBackend, Store) {
	t.Helper()
	f := &fakeBackend{old: oldNetwork, link: Link{SSIDHex: oldNetwork.SSIDHex, Connected: true, IP: "192.168.43.2", ID: "0"}}
	s := Store{File: filepath.Join(t.TempDir(), "config.json")}
	g, e := New(f, s, "1.0.0", 1, "boot", "linux")
	if e != nil {
		t.Fatal(e)
	}
	g.connectDelay = 0
	return g, f, s
}
func phase(t *testing.T, g *Engine, want string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		s := g.Status()
		if s.Transaction != nil && s.Transaction.Phase == want && g.op.TryLock() {
			g.op.Unlock()
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("phase=%+v, expected %s", g.Status().Transaction, want)
}

const operation = "0123456789abcdef0123456789abcdef"

func TestCandidateIsNotCommittedUntilPhoneConfirms(t *testing.T) {
	g, _, s := fixture(t)
	if _, e := g.Begin(operation, newNetwork); e != nil {
		t.Fatal(e)
	}
	phase(t, g, "awaiting_confirmation")
	p, _ := s.Load()
	if p.Home != nil {
		t.Fatal("candidate silently became home")
	}
	if _, e := g.Confirm(operation); e != nil {
		t.Fatal(e)
	}
	p, _ = s.Load()
	if p.Home == nil || *p.Home != newNetwork || p.Tx.Phase != "committed" {
		t.Fatal("commit missing")
	}
}
func TestMissingConfirmationRollsBackAfterAppCloses(t *testing.T) {
	g, f, s := fixture(t)
	g.Begin(operation, newNetwork)
	phase(t, g, "awaiting_confirmation")
	g.now = func() time.Time { return time.Now().Add(4 * time.Minute) }
	g.Tick()
	phase(t, g, "rolled_back")
	p, _ := s.Load()
	if p.Home != nil || f.restores != 1 {
		t.Fatal("rollback did not preserve old home")
	}
}
func TestBootRecoversInterruptedTransaction(t *testing.T) {
	g, f, s := fixture(t)
	g.Begin(operation, newNetwork)
	phase(t, g, "awaiting_confirmation")
	after, e := New(f, s, "1.0.0", 1, "new-boot", "linux")
	if e != nil {
		t.Fatal(e)
	}
	if e = after.Recover(); e != nil {
		t.Fatal(e)
	}
	phase(t, after, "rolled_back")
	if f.restores != 1 {
		t.Fatal("boot did not roll back")
	}
}
func TestConfirmationRequiresCorrectSSIDAndIP(t *testing.T) {
	g, f, _ := fixture(t)
	g.Begin(operation, newNetwork)
	phase(t, g, "awaiting_confirmation")
	for _, l := range []Link{{SSIDHex: "other", IP: "192.168.1.9", Connected: true}, {SSIDHex: newNetwork.SSIDHex, Connected: true}} {
		f.mu.Lock()
		f.link = l
		f.mu.Unlock()
		if _, e := g.Confirm(operation); e == nil {
			t.Fatal("unverified Wi-Fi committed")
		}
	}
}
func TestJoinFailureRollsBack(t *testing.T) {
	g, f, _ := fixture(t)
	f.joinFail = true
	g.Begin(operation, newNetwork)
	phase(t, g, "rolled_back")
	if f.restores != 1 {
		t.Fatal("old network not restored")
	}
}
func TestConcurrentChangesAreRefused(t *testing.T) {
	g, _, _ := fixture(t)
	g.Begin(operation, newNetwork)
	phase(t, g, "awaiting_confirmation")
	if _, e := g.Begin(strings.Repeat("c", 32), oldNetwork); e == nil {
		t.Fatal("pending transaction replaced")
	}
}
func TestPersistFailureRollsBack(t *testing.T) {
	g, f, _ := fixture(t)
	f.persistFail = true
	g.Begin(operation, newNetwork)
	phase(t, g, "awaiting_confirmation")
	if _, e := g.Confirm(operation); e == nil {
		t.Fatal("save failure hidden")
	}
	phase(t, g, "rolled_back")
}
func TestStatusAndErrorsDoNotExportCredentials(t *testing.T) {
	g, _, s := fixture(t)
	g.Begin(operation, newNetwork)
	phase(t, g, "awaiting_confirmation")
	b, _ := json.Marshal(g.Status())
	if strings.Contains(string(b), newNetwork.PSK) || strings.Contains(string(b), oldNetwork.PSK) || strings.Contains(string(b), "psk") {
		t.Fatal("secret exported")
	}
	st, _ := os.Stat(s.File)
	if st.Mode().Perm() != 0600 {
		t.Fatal("credentials file is not 0600")
	}
}
func TestBootRejoinsPersistedHomeAfterMaintainingServices(t *testing.T) {
	g, f, s := fixture(t)
	s.Save(Persisted{Schema: 1, Home: &newNetwork})
	g, e := New(f, s, "1.0.0", 1, "reboot", "linux")
	if e != nil {
		t.Fatal(e)
	}
	g.Tick()
	if f.joins != 1 || len(f.order) < 2 || f.order[0] != "maintain" || f.order[1] != "join" {
		t.Fatal("services waited for Wi-Fi")
	}
}
func TestReconnectUsesBackoff(t *testing.T) {
	g, f, s := fixture(t)
	s.Save(Persisted{Schema: 1, Home: &newNetwork})
	g, _ = New(f, s, "1.0.0", 1, "boot", "linux")
	f.joinFail = true
	g.Tick()
	g.Tick()
	if f.joins != 1 {
		t.Fatal("reconnect storm")
	}
}
func TestInvalidCredentialsNeverTouchWifi(t *testing.T) {
	g, f, _ := fixture(t)
	for _, n := range []Network{{"", strings.Repeat("a", 64)}, {"ff", "password"}, {"00", strings.Repeat("a", 64)}} {
		if _, e := g.Begin(operation, n); e == nil {
			t.Fatal("invalid network accepted")
		}
	}
	if f.joins != 0 {
		t.Fatal("Wi-Fi touched")
	}
}
func TestCorruptStateDoesNotResetExistingHome(t *testing.T) {
	_, f, s := fixture(t)
	os.WriteFile(s.File, []byte(`{"schema":99}`), 0600)
	if _, e := New(f, s, "1.0.0", 1, "boot", "linux"); e == nil {
		t.Fatal("unsupported state silently replaced")
	}
}
func TestStartupInsertionIsIdempotentAndBeforeExit(t *testing.T) {
	h := Startup{Path: "/etc/rc.local", Line: "agent start"}
	input := []byte("#!/bin/sh\nkeep-ssh\nexit 0\n")
	one, e := StartupText(h, input)
	if e != nil {
		t.Fatal(e)
	}
	two, e := StartupText(h, one)
	if e != nil || string(one) != string(two) {
		t.Fatal("duplicate hook")
	}
	if strings.Index(string(one), h.Line) > strings.Index(string(one), "exit 0") || !strings.Contains(string(one), "keep-ssh") {
		t.Fatal("boot hook unsafe")
	}
}
func TestUnknownBootServiceIsNotOverwritten(t *testing.T) {
	if _, e := StartupText(Startup{Whole: true}, []byte("#!/bin/sh\nother-service\n")); e == nil {
		t.Fatal("foreign boot script overwritten")
	}
}
func TestPreferredWifiFailureKeepsWorkingFallback(t *testing.T) {
	_, f, s := fixture(t)
	s.Save(Persisted{Schema: 1, Home: &newNetwork})
	g, _ := New(f, s, "1.0.0", 1, "boot", "linux")
	f.joinFail = true
	g.Tick()
	if f.restores != 1 {
		t.Fatal("failed home reconnect abandoned working fallback")
	}
}
func TestCompletedTransactionCannotBeRolledBackSilently(t *testing.T) {
	g, _, _ := fixture(t)
	g.Begin(operation, newNetwork)
	phase(t, g, "awaiting_confirmation")
	g.Confirm(operation)
	if _, e := g.Rollback(); e == nil {
		t.Fatal("committed home changed without a new transaction")
	}
}
func TestUpdatesAndShutdownAreBlockedDuringWifiChange(t *testing.T) {
	g, _, _ := fixture(t)
	g.Begin(operation, newNetwork)
	phase(t, g, "awaiting_confirmation")
	if g.CanStop() {
		t.Fatal("update allowed during Wi-Fi transaction")
	}
	if e := g.Quiesce(); e == nil {
		t.Fatal("pending Wi-Fi abandoned on shutdown")
	}
}
func TestQuiescedServiceCannotStartAnotherWifiChange(t *testing.T) {
	g, _, _ := fixture(t)
	if e := g.Quiesce(); e != nil {
		t.Fatal(e)
	}
	if _, e := g.Begin(operation, newNetwork); e == nil {
		t.Fatal("operation accepted after shutdown")
	}
}
func TestAtomicFileRefusesSymlinkTarget(t *testing.T) {
	d := t.TempDir()
	outside := filepath.Join(d, "outside")
	os.WriteFile(outside, []byte("keep"), 0600)
	link := filepath.Join(d, "managed")
	os.Symlink(outside, link)
	if e := atomicFile(link, []byte("replace"), 0700); e == nil {
		t.Fatal("symlink target overwritten")
	}
	b, _ := os.ReadFile(outside)
	if string(b) != "keep" {
		t.Fatal("unrelated file changed")
	}
}
