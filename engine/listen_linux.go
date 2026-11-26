package main

import (
	"errors"
	"net"
	"os"
	"syscall"
)

// rawListener is a localhost TCP listener that uses the plain accept(2)
// syscall. Go's net package (and syscall.Accept) always use accept4(2),
// which does not exist on old ARM kernels such as 2.6.32 ("function not
// implemented"). Accepted sockets are handed to the normal Go netpoller
// via net.FileConn, so everything else works as usual.
type rawListener struct {
	fd   int
	addr *net.TCPAddr
}

func listenLocal(port int) (net.Listener, error) {
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, 0)
	if err != nil {
		return nil, err
	}
	syscall.CloseOnExec(fd)
	syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
	if err := syscall.Bind(fd, &syscall.SockaddrInet4{Port: port, Addr: [4]byte{127, 0, 0, 1}}); err != nil {
		syscall.Close(fd)
		return nil, err
	}
	if err := syscall.Listen(fd, 64); err != nil {
		syscall.Close(fd)
		return nil, err
	}
	sa, err := syscall.Getsockname(fd)
	if err != nil {
		syscall.Close(fd)
		return nil, err
	}
	in4, ok := sa.(*syscall.SockaddrInet4)
	if !ok {
		syscall.Close(fd)
		return nil, errors.New("unexpected socket address")
	}
	return &rawListener{fd: fd, addr: &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: in4.Port}}, nil
}

func (l *rawListener) Accept() (net.Conn, error) {
	for {
		// Blocking accept(2) on a dedicated OS thread; no address needed.
		r, _, e := syscall.Syscall(syscall.SYS_ACCEPT, uintptr(l.fd), 0, 0)
		if e == syscall.EINTR || e == syscall.ECONNABORTED || e == syscall.EAGAIN {
			continue
		}
		if e != 0 {
			return nil, e
		}
		nfd := int(r)
		syscall.CloseOnExec(nfd)
		f := os.NewFile(uintptr(nfd), "conn")
		c, err := net.FileConn(f) // dups the fd and registers it with the netpoller
		f.Close()
		if err != nil {
			continue
		}
		return c, nil
	}
}

func (l *rawListener) Close() error   { return syscall.Close(l.fd) }
func (l *rawListener) Addr() net.Addr { return l.addr }
