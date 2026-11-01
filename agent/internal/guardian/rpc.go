package guardian

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"time"
)

type Request struct {
	Method  string   `json:"method"`
	ID      string   `json:"id,omitempty"`
	Network *Network `json:"network,omitempty"`
}
type Response struct {
	OK     bool   `json:"ok"`
	Error  string `json:"error,omitempty"`
	Status Status `json:"status"`
}

func Call(base string, r Request) (Response, error) {
	var result Response
	c, e := net.DialTimeout("unix", base+"/control.sock", 3*time.Second)
	if e != nil {
		return result, e
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	if e = json.NewEncoder(c).Encode(r); e == nil {
		e = json.NewDecoder(io.LimitReader(c, 32768)).Decode(&result)
	}
	if e == nil && !result.OK {
		e = errors.New(result.Error)
	}
	return result, e
}
func (g *Engine) Adopt() (Status, error) {
	if !g.op.TryLock() {
		return g.Status(), errors.New("operation pending")
	}
	defer g.op.Unlock()
	if g.paused {
		return g.Status(), errors.New("agent is stopping")
	}
	g.mu.Lock()
	busy := active(g.state.Tx)
	g.mu.Unlock()
	if busy {
		return g.Status(), errors.New("Wi-Fi transaction pending")
	}
	n, _, e := g.backend.Backup()
	if e != nil {
		return g.Status(), e
	}
	if e = n.Validate(); e != nil {
		return g.Status(), e
	}
	g.mu.Lock()
	old := g.state.Home
	g.state.Home = &n
	e = g.store.Save(g.state)
	if e != nil {
		g.state.Home = old
	}
	g.mu.Unlock()
	return g.Status(), e
}
func Serve(g *Engine, stop <-chan struct{}, shutdown func()) error {
	path := Base + "/control.sock"
	os.Remove(path)
	listener, e := net.Listen("unix", path)
	if e != nil {
		return e
	}
	defer listener.Close()
	defer os.Remove(path)
	if e = os.Chmod(path, 0600); e != nil {
		return e
	}
	go func() { <-stop; listener.Close() }()
	for {
		c, e := listener.Accept()
		if e != nil {
			select {
			case <-stop:
				return nil
			default:
				return e
			}
		}
		go func() {
			defer c.Close()
			c.SetDeadline(time.Now().Add(15 * time.Second))
			var r Request
			if json.NewDecoder(io.LimitReader(c, 8192)).Decode(&r) != nil {
				return
			}
			s := g.Status()
			var err error
			switch r.Method {
			case "status":
			case "ssh.pause":
				g.backend.SuspendSSH(2 * time.Minute)

			case "adopt":
				s, err = g.Adopt()
			case "wifi.begin":
				if r.Network == nil {
					err = errors.New("network missing")
				} else {
					s, err = g.Begin(r.ID, *r.Network)
				}
			case "wifi.confirm":
				s, err = g.Confirm(r.ID)
			case "wifi.rollback":
				s, err = g.Rollback()
			case "pre-update":
				if !g.CanStop() {
					err = errors.New("agent is busy; update/remove blocked")
				}
			case "shutdown":
				err = g.Quiesce()
				if err == nil {
					defer shutdown()
				}

			default:
				err = errors.New("unknown method")
			}
			result := Response{OK: err == nil, Status: s}
			if err != nil {
				result.Error = err.Error()
			}
			json.NewEncoder(c).Encode(result)
		}()
	}
}
