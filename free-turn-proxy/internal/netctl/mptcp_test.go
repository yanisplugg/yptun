package netctl

import (
	"net"
	"testing"
)

// Go 1.24+ включает MPTCP на слушающих сокетах по умолчанию. Это расширяет
// поверхность атаки на баг ядра CVE-2026-98432, поэтому в go.mod стоит
// godebug multipathtcp=0, а тест не даёт его случайно убрать.
func TestListenersDoNotUseMPTCP(t *testing.T) {
	lc := net.ListenConfig{}
	if lc.MultipathTCP() {
		t.Fatal("MPTCP enabled by default for listeners; check godebug multipathtcp=0 in go.mod")
	}
}
