// SPDX-License-Identifier: WTFPL

package protect

import (
	"context"
	"errors"
	"net"
	"reflect"
	"syscall"
	"testing"

	"github.com/pion/transport/v4"
)

func TestIsTunInterface(t *testing.T) {
	t.Parallel()

	cases := map[string]bool{
		"tun0":   true,
		"tun":    true,
		"ppp0":   true,
		"pptp0":  true,
		"wlan0":  false,
		"eth0":   false,
		"rmnet0": false,
		"lo":     false,
	}
	for name, want := range cases {
		if got := isTunInterface(name); got != want {
			t.Errorf("isTunInterface(%q) = %v, want %v", name, got, want)
		}
	}
}

func TestInterfacesHidesTun(t *testing.T) {
	t.Parallel()

	n, err := NewProtectedNet()
	if err != nil {
		t.Fatalf("NewProtectedNet: %v", err)
	}
	ifaces, err := n.Interfaces()
	if err != nil {
		t.Fatalf("Interfaces: %v", err)
	}
	for _, ifc := range ifaces {
		if isTunInterface(ifc.Name) {
			t.Errorf("Interfaces returned tun device %q", ifc.Name)
		}
	}
}

func TestInterfaceByNameRejectsTun(t *testing.T) {
	t.Parallel()

	n, err := NewProtectedNet()
	if err != nil {
		t.Fatalf("NewProtectedNet: %v", err)
	}
	if _, err := n.InterfaceByName("tun0"); !errors.Is(err, transport.ErrInterfaceNotFound) {
		t.Errorf("InterfaceByName(tun0) error = %v, want %v", err, transport.ErrInterfaceNotFound)
	}
}

// TestControlFuncFailClosed verifies that Protector can reject a socket.
func TestControlFuncFailClosed(t *testing.T) {
	old := Protector
	t.Cleanup(func() { Protector = old })

	Protector = func(int) bool { return false }
	lc := net.ListenConfig{Control: controlFunc}
	pc, err := lc.ListenPacket(context.Background(), "udp", "127.0.0.1:0")
	if err == nil {
		_ = pc.Close()
		t.Fatal("expected protected ListenPacket to fail when Protector rejects fd")
	}
}

// TestControlFuncProtects verifies that Protector receives a real fd.
func TestControlFuncProtects(t *testing.T) {
	old := Protector
	t.Cleanup(func() { Protector = old })

	var calls int
	Protector = func(fd int) bool {
		if fd < 0 {
			t.Errorf("protector got negative fd %d", fd)
		}
		calls++
		return true
	}
	lc := net.ListenConfig{Control: controlFunc}
	pc, err := lc.ListenPacket(context.Background(), "udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("ListenPacket: %v", err)
	}
	defer func() { _ = pc.Close() }()
	if calls == 0 {
		t.Error("protector was not invoked")
	}
}

// TestCreateDialerProtectsAndChains verifies that CreateDialer copies the
// caller's Dialer and keeps the caller's Control hook.
func TestCreateDialerProtectsAndChains(t *testing.T) {
	old := Protector
	t.Cleanup(func() { Protector = old })

	var protectorRan bool
	Protector = func(int) bool { protectorRan = true; return true }

	n, err := NewProtectedNet()
	if err != nil {
		t.Fatalf("NewProtectedNet: %v", err)
	}

	// Dial a local TCP listener.
	ln, err := (&net.ListenConfig{}).Listen(context.Background(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer func() { _ = ln.Close() }()
	go func() {
		if c, aerr := ln.Accept(); aerr == nil {
			_ = c.Close()
		}
	}()

	var callerControlRan bool
	caller := &net.Dialer{
		Control: func(_, _ string, _ syscall.RawConn) error {
			callerControlRan = true
			return nil
		},
	}
	callerControl := caller.Control

	dialer := n.CreateDialer(caller)

	// Keep the caller's Control unchanged.
	if reflect.ValueOf(caller.Control).Pointer() != reflect.ValueOf(callerControl).Pointer() {
		t.Error("CreateDialer mutated the caller's Dialer.Control")
	}

	conn, err := dialer.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial via CreateDialer: %v", err)
	}
	_ = conn.Close()

	if !protectorRan {
		t.Error("protector hook did not run for the CreateDialer dialer")
	}
	if !callerControlRan {
		t.Error("caller's Control hook did not run (chain dropped it)")
	}
}

// TestCreateDialerProtectsAndChainsControlContext verifies that CreateDialer
// keeps the caller's ControlContext hook.
func TestCreateDialerProtectsAndChainsControlContext(t *testing.T) {
	old := Protector
	t.Cleanup(func() { Protector = old })

	var protectorRan bool
	Protector = func(int) bool { protectorRan = true; return true }

	n, err := NewProtectedNet()
	if err != nil {
		t.Fatalf("NewProtectedNet: %v", err)
	}

	ln, err := (&net.ListenConfig{}).Listen(context.Background(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer func() { _ = ln.Close() }()
	go func() {
		if c, aerr := ln.Accept(); aerr == nil {
			_ = c.Close()
		}
	}()

	var callerControlContextRan bool
	caller := &net.Dialer{
		ControlContext: func(_ context.Context, _, _ string, _ syscall.RawConn) error {
			callerControlContextRan = true
			return nil
		},
	}
	callerControlContext := caller.ControlContext

	dialer := n.CreateDialer(caller)

	if reflect.ValueOf(caller.ControlContext).Pointer() != reflect.ValueOf(callerControlContext).Pointer() {
		t.Error("CreateDialer mutated the caller's Dialer.ControlContext")
	}

	conn, err := dialer.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial via CreateDialer: %v", err)
	}
	_ = conn.Close()

	if !protectorRan {
		t.Error("protector hook did not run for the CreateDialer dialer")
	}
	if !callerControlContextRan {
		t.Error("caller's ControlContext hook did not run (chain dropped it)")
	}
}
