package guardian

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const Protocol = 1

type Network struct {
	SSIDHex string `json:"ssid_hex"`
	PSK     string `json:"psk"`
}

func (n Network) Validate() error {
	b, e := hex.DecodeString(n.SSIDHex)
	if e != nil || len(b) < 1 || len(b) > 32 {
		return errors.New("invalid SSID")
	}
	if strings.ContainsAny(string(b), "\x00\n\r") {
		return errors.New("invalid SSID control character")
	}
	b, e = hex.DecodeString(n.PSK)
	if e != nil || len(b) != 32 {
		return errors.New("invalid WPA PSK")
	}
	return nil
}

type Link struct {
	SSIDHex   string `json:"ssid_hex"`
	IP        string `json:"ip"`
	Connected bool   `json:"connected"`
	ID        string `json:"id,omitempty"`
}
type Transaction struct {
	ID         string    `json:"id"`
	Phase      string    `json:"phase"`
	Target     Network   `json:"target"`
	Previous   Network   `json:"previous"`
	PreviousID string    `json:"previous_id,omitempty"`
	TargetID   string    `json:"target_id,omitempty"`
	Deadline   time.Time `json:"deadline"`
	Message    string    `json:"message"`
}
type Persisted struct {
	Schema int          `json:"schema"`
	Home   *Network     `json:"home,omitempty"`
	Tx     *Transaction `json:"transaction,omitempty"`
}
type PublicTransaction struct {
	ID       string    `json:"id"`
	Phase    string    `json:"phase"`
	Deadline time.Time `json:"deadline"`
	Message  string    `json:"message"`
}
type Status struct {
	HomeConfigured bool               `json:"home_configured"`
	HomeSSIDHex    string             `json:"home_ssid_hex,omitempty"`
	Protocol       int                `json:"protocol"`
	Version        string             `json:"version"`
	Build          int                `json:"build"`
	Boot           string             `json:"boot"`
	Platform       string             `json:"platform"`
	ADB            string             `json:"adb"`
	SSH            string             `json:"ssh"`
	OTA            string             `json:"ota"`
	Wifi           Link               `json:"wifi"`
	Transaction    *PublicTransaction `json:"transaction,omitempty"`
	LastError      string             `json:"last_error,omitempty"`
}
type Backend interface {
	Link() (Link, error)
	Backup() (Network, string, error)
	Join(Network) (string, error)
	Restore(Network, string) error
	Persist(string) error
	Discard(string) error
	SuspendSSH(time.Duration)
	Maintain() (adb, ssh, ota string, err error)
}
type Store struct{ File string }

func (s Store) Load() (Persisted, error) {
	var p Persisted
	b, e := os.ReadFile(s.File)
	if os.IsNotExist(e) {
		return Persisted{Schema: 1}, nil
	}
	if e != nil {
		return p, e
	}
	if len(b) > 32768 {
		return p, errors.New("oversized state")
	}
	e = json.Unmarshal(b, &p)
	if e == nil && p.Schema != 1 {
		e = errors.New("unsupported state schema")
	}
	return p, e
}
func (s Store) Save(p Persisted) error {
	if e := os.MkdirAll(filepath.Dir(s.File), 0700); e != nil {
		return e
	}
	if st, e := os.Lstat(s.File); e == nil && st.Mode()&os.ModeSymlink != 0 {
		return errors.New("state symlink refused")
	}
	b, e := json.Marshal(p)
	if e != nil {
		return e
	}
	f, e := os.CreateTemp(filepath.Dir(s.File), ".state-")
	if e != nil {
		return e
	}
	name := f.Name()
	defer os.Remove(name)
	if e = f.Chmod(0600); e == nil {
		_, e = f.Write(b)
	}
	if e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e == nil {
		e = ce
	}
	if e == nil {
		e = os.Rename(name, s.File)
	}
	if e == nil {
		if d, x := os.Open(filepath.Dir(s.File)); x == nil {
			e = d.Sync()
			d.Close()
		}
	}
	return e
}

type Engine struct {
	mu           sync.Mutex
	op           sync.Mutex
	backend      Backend
	store        Store
	state        Persisted
	status       Status
	now          func() time.Time
	nextHome     time.Time
	homeFailures int
	paused       bool
	connectDelay time.Duration
}

func New(b Backend, s Store, version string, build int, boot, platform string) (*Engine, error) {
	p, e := s.Load()
	if e != nil {
		return nil, e
	}
	g := &Engine{backend: b, store: s, state: p, now: time.Now, connectDelay: 3 * time.Second, status: Status{Protocol: Protocol, Version: version, Build: build, Boot: boot, Platform: platform, ADB: "checking", SSH: "checking", OTA: "checking"}}
	return g, nil
}
func active(t *Transaction) bool {
	return t != nil && (t.Phase == "preparing" || t.Phase == "connecting" || t.Phase == "awaiting_confirmation" || t.Phase == "rolling_back" || t.Phase == "rollback_failed")
}
func (g *Engine) Status() Status {
	g.mu.Lock()
	defer g.mu.Unlock()
	s := g.status
	s.HomeConfigured = g.state.Home != nil
	if g.state.Home != nil {
		s.HomeSSIDHex = g.state.Home.SSIDHex
	}
	if t := g.state.Tx; t != nil {
		s.Transaction = &PublicTransaction{t.ID, t.Phase, t.Deadline, t.Message}
	}
	return s
}
func (g *Engine) Recover() error {
	g.op.Lock()
	defer g.op.Unlock()
	g.mu.Lock()
	t := g.state.Tx
	g.mu.Unlock()
	if active(t) {
		return g.rollback("Previous Wi-Fi change was interrupted; restoring previous network")
	}
	return nil
}
func (g *Engine) Begin(id string, n Network) (Status, error) {
	if len(id) != 32 {
		return g.Status(), errors.New("invalid operation id")
	}
	if _, e := hex.DecodeString(id); e != nil {
		return g.Status(), e
	}
	if e := n.Validate(); e != nil {
		return g.Status(), e
	}
	if !g.op.TryLock() {
		return g.Status(), errors.New("another operation is running")
	}
	defer g.op.Unlock()
	if g.paused {
		return g.Status(), errors.New("agent is stopping")
	}
	g.mu.Lock()
	busy := active(g.state.Tx)
	g.mu.Unlock()
	if busy {
		return g.Status(), errors.New("Wi-Fi transaction already pending")
	}
	old, oldID, e := g.backend.Backup()
	if e != nil {
		return g.Status(), fmt.Errorf("rollback network not available: %w", e)
	}
	if e = old.Validate(); e != nil {
		return g.Status(), errors.New("cannot verify rollback credentials")
	}
	tx := &Transaction{ID: id, Phase: "preparing", Target: n, Previous: old, PreviousID: oldID, Deadline: g.now().Add(3 * time.Minute), Message: "Preparing network; hotspot must stay enabled"}
	g.mu.Lock()
	previous := g.state.Tx
	g.state.Tx = tx
	e = g.store.Save(g.state)
	if e != nil {
		g.state.Tx = previous
	}
	g.mu.Unlock()
	if e != nil {
		return g.Status(), e
	}
	// Work survives the ADB connection and the phone application closing.
	go func() { time.Sleep(g.connectDelay); g.connect(id) }()
	return g.Status(), nil
}
func (g *Engine) connect(id string) {
	g.op.Lock()
	defer g.op.Unlock()
	g.mu.Lock()
	t := g.state.Tx
	if t == nil || t.ID != id || t.Phase != "preparing" {
		g.mu.Unlock()
		return
	}
	t.Phase = "connecting"
	t.Message = "Connecting to candidate network"
	e := g.store.Save(g.state)
	target := t.Target
	g.mu.Unlock()
	if e != nil {
		g.rollback("Cannot persist transaction")
		return
	}
	targetID, e := g.backend.Join(target)
	if e != nil {
		g.rollback("Could not connect; restoring previous network")
		return
	}
	g.mu.Lock()
	t = g.state.Tx
	t.TargetID = targetID
	t.Phase = "awaiting_confirmation"
	t.Message = "Network associated; awaiting confirmation from the same station over ADB"
	e = g.store.Save(g.state)
	g.mu.Unlock()
	if e != nil {
		g.rollback("Cannot persist candidate state")
	}
}
func (g *Engine) Confirm(id string) (Status, error) {
	if !g.op.TryLock() {
		return g.Status(), errors.New("Wi-Fi change still running")
	}
	defer g.op.Unlock()
	g.mu.Lock()
	t := g.state.Tx
	g.mu.Unlock()
	if t == nil || t.ID != id || t.Phase != "awaiting_confirmation" {
		return g.Status(), errors.New("no matching pending Wi-Fi change")
	}
	if !g.now().Before(t.Deadline) {
		g.rollback("Confirmation expired")
		return g.Status(), errors.New("confirmation expired")
	}
	link, e := g.backend.Link()
	if e != nil || !link.Connected || link.IP == "" || link.SSIDHex != t.Target.SSIDHex {
		return g.Status(), errors.New("candidate Wi-Fi is not connected")
	}
	if e = g.backend.Persist(t.TargetID); e != nil {
		g.rollback("Could not save selected network")
		return g.Status(), e
	}
	g.mu.Lock()
	previous := g.state.Home
	target := t.Target
	g.state.Home = &target
	t.Phase = "committed"
	t.Message = "Home Wi-Fi confirmed; phone hotspot may now stop"
	e = g.store.Save(g.state)
	if e != nil {
		g.state.Home = previous
		t.Phase = "awaiting_confirmation"
	}
	g.mu.Unlock()
	if e != nil {
		g.rollback("Could not persist home Wi-Fi")
	}
	return g.Status(), e
}
func (g *Engine) rollback(reason string) error {
	g.mu.Lock()
	t := g.state.Tx
	if t == nil {
		g.mu.Unlock()
		return nil
	}
	t.Phase = "rolling_back"
	t.Message = reason
	e := g.store.Save(g.state)
	g.mu.Unlock()
	restore := g.backend.Restore(t.Previous, t.PreviousID)
	if restore == nil && t.TargetID != "" && t.TargetID != t.PreviousID {
		restore = g.backend.Discard(t.TargetID)
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if restore == nil {
		g.nextHome = g.now().Add(5 * time.Minute)
	}
	if restore != nil {
		t.Phase = "rollback_failed"
		t.Message = "Rollback failed; keep hotspot enabled and retry recovery"
		g.status.LastError = restore.Error()
	} else {
		t.Phase = "rolled_back"
		t.Message = reason + "; previous network restored"
	}
	save := g.store.Save(g.state)
	if restore != nil {
		return restore
	}
	if e != nil {
		return e
	}
	return save
}
func (g *Engine) Rollback() (Status, error) {
	if !g.Pending() {
		return g.Status(), errors.New("no pending Wi-Fi change")
	}
	if !g.op.TryLock() {
		return g.Status(), errors.New("operation still running")
	}
	defer g.op.Unlock()
	e := g.rollback("Rollback requested")
	return g.Status(), e
}
func (g *Engine) Pending() bool { g.mu.Lock(); defer g.mu.Unlock(); return active(g.state.Tx) }
func (g *Engine) CanStop() bool {
	if !g.op.TryLock() {
		return false
	}
	defer g.op.Unlock()
	return !g.Pending()
}
func (g *Engine) Quiesce() error {
	if !g.op.TryLock() {
		return errors.New("maintenance is running")
	}
	defer g.op.Unlock()
	if g.Pending() {
		return errors.New("Wi-Fi transaction pending")
	}
	g.paused = true
	return nil
}
func (g *Engine) Tick() {
	if !g.op.TryLock() {
		return
	}
	defer g.op.Unlock()
	if g.paused {
		return
	}
	// Service recovery must not wait for a Wi-Fi association timeout.
	adb, ssh, ota, serviceErr := g.backend.Maintain()
	link, linkErr := g.backend.Link()
	g.mu.Lock()
	g.status.Wifi = link
	g.status.ADB = adb
	g.status.SSH = ssh
	g.status.OTA = ota
	t := g.state.Tx
	home := g.state.Home
	g.mu.Unlock()
	if active(t) && !g.now().Before(t.Deadline) {
		g.rollback("Wi-Fi confirmation timed out")
	}
	if !active(t) && home != nil && (linkErr != nil || !link.Connected || link.SSIDHex != home.SSIDHex) && !g.now().Before(g.nextHome) {
		var fallback Network
		var fallbackID string
		var fallbackOK bool
		if linkErr == nil && link.Connected && link.IP != "" {
			var e error
			fallback, fallbackID, e = g.backend.Backup()
			fallbackOK = e == nil && fallback.Validate() == nil
			if !fallbackOK && link.ID != "" {
				fallback = Network{SSIDHex: link.SSIDHex}
				fallbackID = link.ID
				fallbackOK = true
			}
		}
		if _, e := g.backend.Join(*home); e != nil {
			if fallbackOK {
				g.backend.Restore(fallback, fallbackID)
			}
			g.homeFailures++
			delay := time.Duration(5*(1<<min(g.homeFailures, 5))) * time.Second
			g.nextHome = g.now().Add(delay)
			linkErr = errors.New("Home Wi-Fi reconnect failed; retry scheduled")
		} else {
			g.homeFailures = 0
			g.nextHome = time.Time{}
		}
	}
	g.mu.Lock()
	if serviceErr != nil {
		g.status.LastError = serviceErr.Error()
	} else if linkErr != nil {
		g.status.LastError = linkErr.Error()
	} else {
		g.status.LastError = ""
	}
	g.mu.Unlock()
}
