package guardian

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const Binary = Base + "/agent"

type Startup struct {
	Path    string `json:"path"`
	Line    string `json:"line,omitempty"`
	Whole   bool   `json:"whole"`
	Runtime bool   `json:"runtime,omitempty"`
}

func privateBase() error { return privateDirectory(Base) }

// Firmware may alias /data or /data/quasar to a persistent volume. Resolve
// those parents, but never follow a link in our own managed directory.
func privateDirectory(base string) error {
	parent, e := filepath.EvalSymlinks(filepath.Dir(base))
	if e != nil {
		return fmt.Errorf("resolve agent parent %s: %w", filepath.Dir(base), e)
	}
	resolved := filepath.Join(parent, filepath.Base(base))
	if e = os.Mkdir(resolved, 0700); e != nil && !os.IsExist(e) {
		return e
	}
	fd, e := syscall.Open(resolved, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	if e != nil {
		return fmt.Errorf("agent directory must be a real directory: %w", e)
	}
	f := os.NewFile(uintptr(fd), resolved)
	defer f.Close()
	st, e := f.Stat()
	if e != nil {
		return e
	}
	owner, ok := st.Sys().(*syscall.Stat_t)
	if !ok || owner.Uid != uint32(os.Geteuid()) {
		return errors.New("agent directory belongs to another user")
	}
	return f.Chmod(0700)
}

func atomicFile(path string, data []byte, mode os.FileMode) error {
	if st, e := os.Lstat(path); e == nil && !st.Mode().IsRegular() {
		return errors.New("non-regular target refused")
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".agent-")
	if e != nil {
		return e
	}
	name := f.Name()
	defer os.Remove(name)
	if e = f.Chmod(mode); e == nil {
		_, e = f.Write(data)
	}
	if e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e == nil {
		e = ce
	}
	if e == nil {
		e = os.Rename(name, path)
	}
	return e
}
func mountWrite(platform string) (func() error, error) {
	if platform == "android" {
		return func() error { return nil }, nil
	}
	target := "/"
	mount := "mount"
	if platform == "station1" {
		target = "/system"
		mount = "/system/bin/mount"
	}
	data, e := os.ReadFile("/proc/mounts")
	if e != nil {
		return nil, e
	}
	found := false
	ro := false
	for _, line := range strings.Split(string(data), "\n") {
		p := strings.Fields(line)
		if len(p) > 3 && p[1] == target {
			found = true
			ro = strings.Contains(","+p[3]+",", ",ro,")
			break
		}
	}
	if !found {
		return nil, errors.New("mount mode unknown")
	}
	if !ro {
		return func() error { return nil }, nil
	}
	if _, e = run(10*time.Second, mount, "-o", "remount,rw", target); e != nil {
		return nil, e
	}
	return func() error { _, e := run(10*time.Second, mount, "-o", "remount,ro", target); return e }, nil
}
func hook(platform string) (Startup, error) {
	if platform == "android" {
		return Startup{Runtime: true}, nil
	}
	if platform == "station1" {
		return Startup{Path: "/system/etc/rc.local", Line: "[ ! -x " + Binary + " ] || " + Binary + " start || true"}, nil
	}
	if st, e := os.Stat("/etc/init.d"); e == nil && st.IsDir() {
		return Startup{Path: "/etc/init.d/S99zzalicebridge-agent", Whole: true}, nil
	}
	if regular("/etc/rc.local") {
		return Startup{Path: "/etc/rc.local", Line: "[ ! -x " + Binary + " ] || " + Binary + " start || true"}, nil
	}
	return Startup{}, errors.New("no supported boot entry point")
}
func StartupText(h Startup, original []byte) ([]byte, error) {
	if h.Runtime {
		return nil, nil
	}
	if h.Whole {
		if len(original) > 0 && !strings.Contains(string(original), "# AliceBridge Agent managed") {
			return nil, errors.New("boot entry owned by another service")
		}
		return []byte("#!/bin/sh\n# AliceBridge Agent managed\ncase \"${1:-start}\" in\nstart) " + Binary + " start || true;;\nstop) " + Binary + " stop;;\nrestart) " + Binary + " stop; " + Binary + " start;;\nesac\n"), nil
	}
	if len(original) == 0 || !strings.HasPrefix(string(original), "#!") {
		return nil, errors.New("unknown rc.local format")
	}
	for _, line := range strings.Split(string(original), "\n") {
		if line == h.Line {
			return original, nil
		}
	}
	before, after, _ := strings.Cut(string(original), "\n")
	return []byte(before + "\n" + h.Line + "\n" + after), nil
}
func waitControl(method string) (Response, error) {
	deadline := time.Now().Add(15 * time.Second)
	var response Response
	var err error
	for {
		response, err = Call(Base, Request{Method: method})
		if err == nil {
			return response, nil
		}
		if tx := response.Status.Transaction; tx != nil && (tx.Phase == "preparing" || tx.Phase == "connecting" || tx.Phase == "awaiting_confirmation" || tx.Phase == "rolling_back" || tx.Phase == "rollback_failed") {
			return response, err
		}
		if !time.Now().Before(deadline) {
			return response, err
		}
		time.Sleep(300 * time.Millisecond)
	}
}
func Install(executable, manifest string, version string, build int) (err error) {
	if os.Geteuid() != 0 {
		return errors.New("root required")
	}
	platform, e := Detect()
	if e != nil {
		return e
	}
	signed, e := VerifyExecutable(manifest, executable, build)
	if e != nil {
		return e
	}
	if signed.Version != version {
		return errors.New("manifest version differs from executable")
	}

	if e = privateBase(); e != nil {
		return e
	}
	if platform == "station1" {
		device := Device{Platform: platform}
		proof, x := device.android("probe")
		if x != nil || !strings.Contains(proof, "AGENT_WIFI_API=1") {
			return errors.New("update Station 1 Wi-Fi helper before installing agent")
		}
	}
	updateLock, e := os.OpenFile(Base+"/update.lock", os.O_CREATE|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	defer updateLock.Close()
	if e = syscall.Flock(int(updateLock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		return errors.New("another agent installer is running")
	}

	store := Store{Base + "/config.json"}
	saved, e := store.Load()
	if e != nil {
		return e
	}
	if active(saved.Tx) {
		return errors.New("Wi-Fi transaction pending; finish rollback/confirmation first")
	}
	running := false
	if current, x := Call(Base, Request{Method: "status"}); x == nil {
		if current.Status.Build > build {
			return errors.New("agent downgrade refused")
		}
		if _, x = waitControl("pre-update"); x != nil {
			return x
		}
		running = true
	}

	h, e := hook(platform)
	if e != nil {
		return e
	}
	var original []byte
	if h.Path != "" {
		original, e = os.ReadFile(h.Path)
	}
	if e != nil && !os.IsNotExist(e) {
		return e
	}
	text, e := StartupText(h, original)
	if e != nil {
		return e
	}
	restore, e := mountWrite(platform)
	if e != nil {
		return e
	}
	defer func() {
		if x := restore(); x != nil && err == nil {
			err = x
		}
	}()
	// Keep the previous executable until the new daemon answers a versioned status call.
	hadOld := regular(Binary)
	if hadOld {
		old, e := os.ReadFile(Binary)
		if e != nil {
			return e
		}
		if e = atomicFile(Binary+".previous", old, 0700); e != nil {
			return e
		}
	}
	data, e := os.ReadFile(executable)
	if e != nil {
		return e
	}
	stopped := false
	defer func() {
		if err != nil && stopped && hadOld {
			waitControl("shutdown")
			time.Sleep(300 * time.Millisecond)
			if old, x := os.ReadFile(Binary + ".previous"); x == nil {
				atomicFile(Binary, old, 0700)
				Start()
			}
		}
	}()
	if running {
		if _, e = waitControl("shutdown"); e != nil {
			return e
		}
		stopped = true
		time.Sleep(500 * time.Millisecond)
	}
	if e = atomicFile(Binary, data, 0700); e != nil {
		return e
	}
	manifestData, e := os.ReadFile(manifest)
	if e != nil {
		return e
	}
	if e = atomicFile(Base+"/release.json", manifestData, 0600); e != nil {
		return e
	}
	if h.Path != "" {
		if e = atomicFile(h.Path, text, 0755); e != nil {
			return e
		}
	}
	meta, _ := json.Marshal(h)
	if e = atomicFile(Base+"/startup.json", meta, 0600); e != nil {
		return e
	}
	syscall.Sync()
	if e = restore(); e != nil {
		return e
	}
	restore = func() error { return nil }
	if e = Start(); e != nil {
		return errors.New("new daemon did not start; previous executable retained")
	}

	result, e := Call(Base, Request{Method: "status"})
	if e != nil || result.Status.Version != version || result.Status.Build != build {
		return errors.New("daemon version check failed")
	}
	return nil
}
func Start() error {
	if _, e := Call(Base, Request{Method: "status"}); e == nil {
		return nil
	}
	log, e := os.OpenFile(Base+"/agent.log", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
	if e != nil {
		return e
	}
	defer log.Close()
	cmd := exec.Command(Binary, "serve")
	cmd.Stdin = nil
	cmd.Stdout = log
	cmd.Stderr = log
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if e = cmd.Start(); e != nil {
		return e
	}
	cmd.Process.Release()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if _, e := Call(Base, Request{Method: "status"}); e == nil {
			return nil
		}
		time.Sleep(200 * time.Millisecond)
	}
	return errors.New("daemon startup timeout")
}
func Uninstall() (err error) {
	if os.Geteuid() != 0 {
		return errors.New("root required")
	}
	if _, e := waitControl("pre-update"); e != nil {
		return e
	}
	data, e := os.ReadFile(Base + "/startup.json")
	if e != nil {
		return e
	}
	var h Startup
	if e = json.Unmarshal(data, &h); e != nil {
		return e
	}
	platform, e := Detect()
	if e != nil {
		return e
	}
	expected, e := hook(platform)
	if e != nil || h != expected {
		return errors.New("unrecognized startup entry")
	}
	restore, e := mountWrite(platform)
	if e != nil {
		return e
	}
	defer func() {
		if x := restore(); x != nil && err == nil {
			err = x
		}
	}()
	if h.Path != "" {
		data, e = os.ReadFile(h.Path)
		if e != nil {
			return e
		}
	}
	stopped := false
	defer func() {
		if err != nil && stopped && regular(Binary) {
			Start()
		}
	}()
	if _, e = waitControl("shutdown"); e != nil {
		return e
	}
	stopped = true
	time.Sleep(500 * time.Millisecond)
	if h.Path == "" {
		// Runtime-only Android install has no boot entry to remove.
	} else if h.Whole {
		expectedText, _ := StartupText(h, nil)
		if string(data) != string(expectedText) {
			return errors.New("startup script modified; automatic removal refused")
		}
		if e = os.Remove(h.Path); e != nil {
			return e
		}
	} else {
		lines := strings.Split(string(data), "\n")
		out := lines[:0]
		for _, l := range lines {
			if l != h.Line {
				out = append(out, l)
			}
		}
		if e = atomicFile(h.Path, []byte(strings.Join(out, "\n")), 0755); e != nil {
			return e
		}
	}
	for _, n := range []string{"agent", "agent.previous", "config.json", "release.json", "startup.json", "agent.log", "agent.log.old", "control.sock", "lock", "update.lock"} {
		if e = os.Remove(filepath.Join(Base, n)); e != nil && !os.IsNotExist(e) {
			return fmt.Errorf("remove %s: %w", n, e)
		}
	}
	os.Remove(Base)
	return nil
}
