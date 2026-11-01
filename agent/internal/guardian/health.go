package guardian

import (
	"encoding/binary"
	"io"
	"net"
	"strings"
	"time"
)

func adbHealthy(address string) bool {
	c, e := net.DialTimeout("tcp", address, 500*time.Millisecond)
	if e != nil {
		return false
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(time.Second))
	body := []byte("host::\x00")
	header := make([]byte, 24)
	command := uint32(0x4e584e43)
	sum := uint32(0)
	for _, b := range body {
		sum += uint32(b)
	}
	for i, v := range []uint32{command, 0x01000000, 4096, uint32(len(body)), sum, command ^ 0xffffffff} {
		binary.LittleEndian.PutUint32(header[i*4:], v)
	}
	if _, e = c.Write(append(header, body...)); e != nil {
		return false
	}
	if _, e = io.ReadFull(c, header); e != nil {
		return false
	}
	reply := binary.LittleEndian.Uint32(header)
	return (reply == command || reply == 0x48545541) && binary.LittleEndian.Uint32(header[20:]) == reply^0xffffffff && binary.LittleEndian.Uint32(header[12:]) <= 4096
}
func sshHealthy(address string) bool {
	c, e := net.DialTimeout("tcp", address, 500*time.Millisecond)
	if e != nil {
		return false
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(time.Second))
	var data [256]byte
	n, e := c.Read(data[:])
	return e == nil && n > 0 && strings.HasPrefix(string(data[:n]), "SSH-")
}
