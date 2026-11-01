package guardian

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
)

func TestAdbHealthAcceptsAuthenticationWithoutDisablingIt(t *testing.T) {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	go func() {
		c, e := l.Accept()
		if e != nil {
			return
		}
		defer c.Close()
		input := make([]byte, 31)
		io.ReadFull(c, input)
		h := make([]byte, 24)
		binary.LittleEndian.PutUint32(h, 0x48545541)
		binary.LittleEndian.PutUint32(h[20:], 0x48545541^0xffffffff)
		c.Write(h)
	}()
	if !adbHealthy(l.Addr().String()) {
		t.Fatal("valid ADB AUTH rejected")
	}
}
func TestSshHealthRejectsUnrelatedListener(t *testing.T) {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	go func() {
		c, e := l.Accept()
		if e == nil {
			c.Write([]byte("HTTP/1.1 200 OK\r\n"))
			c.Close()
		}
	}()
	if sshHealthy(l.Addr().String()) {
		t.Fatal("non-SSH listener accepted")
	}
}
