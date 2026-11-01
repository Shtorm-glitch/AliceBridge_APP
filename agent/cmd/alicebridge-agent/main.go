package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"alicebridge/agent/assets"
	"alicebridge/agent/internal/guardian"
)

var version = "1.0.0"
var buildNumber = "1"

func main() {
	if e := mainErr(); e != nil {
		fmt.Fprintln(os.Stderr, "AliceBridge Agent:", e)
		os.Exit(1)
	}
}
func mainErr() error {
	os.Setenv("PATH", "/data/local/tmp/alicebridge-tools:/data/local/bin:/usr/local/bin:/data/opt/bin:/opt/bin:/opt/sbin:/system/bin:/system/xbin:/usr/bin:/bin:/usr/sbin:/sbin")
	build, e := strconv.Atoi(buildNumber)
	if e != nil {
		return e
	}
	if len(os.Args) < 2 {
		return errors.New("use version, status, rpc, install, start, stop, uninstall or serve")
	}
	switch os.Args[1] {
	case "licenses":
		for _, name := range []string{"Go-LICENSE.txt", "OTA-LICENSE.txt", "ATTRIBUTION.md"} {
			data, e := assets.Scripts.ReadFile(name)
			if e != nil {
				return e
			}
			fmt.Println(string(data))
		}
		return nil
	case "version":
		return json.NewEncoder(os.Stdout).Encode(map[string]any{"version": version, "build": build, "protocol": guardian.Protocol})
	case "install":
		options := flag.NewFlagSet("install", flag.ContinueOnError)
		manifest := options.String("manifest", "", "signed manifest file")
		if e = options.Parse(os.Args[2:]); e != nil {
			return e
		}
		if *manifest == "" {
			return errors.New("signed manifest required")
		}
		exe, e := os.Executable()
		if e != nil {
			return e
		}
		if e = guardian.Install(exe, *manifest, version, build); e != nil {
			return e
		}
		fmt.Println("AB_AGENT_INSTALLED")
		return nil
	case "start":
		if os.Geteuid() != 0 {
			return errors.New("root required")
		}
		return guardian.Start()
	case "stop":
		_, e = guardian.Call(guardian.Base, guardian.Request{Method: "shutdown"})
		return e
	case "uninstall":
		if e = guardian.Uninstall(); e != nil {
			return e
		}
		fmt.Println("AB_AGENT_REMOVED")
		return nil
	case "status":
		r, e := guardian.Call(guardian.Base, guardian.Request{Method: "status"})
		json.NewEncoder(os.Stdout).Encode(r)
		return e
	case "rpc":
		var r guardian.Request
		if e = json.NewDecoder(io.LimitReader(os.Stdin, 8192)).Decode(&r); e != nil {
			return e
		}
		result, e := guardian.Call(guardian.Base, r)
		json.NewEncoder(os.Stdout).Encode(result)
		return e
	case "serve":
		return serve(build)
	default:
		return errors.New("unknown command")
	}
}
func serve(build int) error {
	if os.Geteuid() != 0 {
		return errors.New("root required")
	}
	syscall.Umask(0077)
	lock, e := os.OpenFile(guardian.Base+"/lock", os.O_CREATE|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	defer lock.Close()
	if e = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		return errors.New("agent already running")
	}
	platform, e := guardian.Detect()
	if e != nil {
		return e
	}
	boot, _ := os.ReadFile("/proc/sys/kernel/random/boot_id")
	device := &guardian.Device{Platform: platform, Iface: "wlan0"}
	engine, e := guardian.New(device, guardian.Store{File: guardian.Base + "/config.json"}, version, build, strings.TrimSpace(string(boot)), platform)
	if e != nil {
		return e
	}
	stop := make(chan struct{})
	shutdown := sync.OnceFunc(func() { close(stop) })
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	defer signal.Stop(signals)
	go func() {
		select {
		case <-signals:
			shutdown()
		case <-stop:
		}
	}()
	go func() {
		engine.Recover()
		previous := ""
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			engine.Tick()
			s := engine.Status()
			summary := s.ADB + "/" + s.SSH + "/" + s.OTA + "/" + s.LastError
			if summary != previous {
				fmt.Println(time.Now().UTC().Format(time.RFC3339), "health", summary)
				previous = summary
			}
			// Bound log storage without leaking Wi-Fi credentials or full network state.
			if st, e := os.Stat(guardian.Base + "/agent.log"); e == nil && st.Size() > 1024*1024 {
				os.Truncate(guardian.Base+"/agent.log", 0)
			}
			select {
			case <-stop:
				return
			case <-ticker.C:
			}
		}
	}()
	fmt.Println(time.Now().UTC().Format(time.RFC3339), "AliceBridge Agent", version, "boot", strings.TrimSpace(string(boot)))
	return guardian.Serve(engine, stop, shutdown)
}
