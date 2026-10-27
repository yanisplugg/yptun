package session

import (
	"bytes"
	"log"
	"strings"
	"testing"
)

// The server's per-stream traffic line keeps the session and the byte counts
// and drops the destination: an exit's log is no record of where its users
// go. ai-generated: the whole test (egress hardening).
func TestLogTrafficNamesNoDestination(t *testing.T) {
	var buf bytes.Buffer
	oldWriter, oldFlags := log.Writer(), log.Flags()
	log.SetOutput(&buf)
	log.SetFlags(0)
	t.Cleanup(func() {
		log.SetOutput(oldWriter)
		log.SetFlags(oldFlags)
	})

	logTraffic("sid-1", "secret-destination.test:4321", 11, 17)

	got := buf.String()
	if got != "traffic: session=sid-1 in=11 out=17\n" {
		t.Fatalf("traffic line = %q", got)
	}
	if strings.Contains(got, "secret-destination") || strings.Contains(got, "4321") {
		t.Fatalf("traffic line names the destination: %q", got)
	}
}
