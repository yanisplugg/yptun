package gate

import (
	"errors"
	"strings"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

// ai-generated: whole file, cover for the one-question DNS query.

func TestBuildDNSQueryAndReadID(t *testing.T) {
	q := BuildDNSQuery(0xbeef, "example.com")
	if len(q) != 12+13+4 { // header + 1+7+1+3+1 labels + qtype/qclass
		t.Fatalf("query length %d", len(q))
	}
	if q[0] != 0xbe || q[1] != 0xef || q[2] != 0x01 || q[5] != 1 {
		t.Fatalf("header = %v", q[:6])
	}
	if id, ok := DNSResponseID(q); !ok || id != 0xbeef {
		t.Fatalf("DNSResponseID = %x %v", id, ok)
	}
	if _, ok := DNSResponseID([]byte{1}); ok {
		t.Fatal("short packet accepted")
	}
}

func TestBuildDNSQueryParsesAsOneRecursiveAQuestion(t *testing.T) {
	label63 := strings.Repeat("x", 63)
	longest := strings.Repeat(label63+".", 3) + strings.Repeat("y", 61) // 253 bytes, 255 on the wire
	for _, name := range []string{"gate7.example.com", "gate7.example.com.", longest} {
		var p dnsmessage.Parser
		h, err := p.Start(BuildDNSQuery(7, name))
		if err != nil {
			t.Fatalf("%q: header: %v", name, err)
		}
		q, err := p.Question()
		if err != nil {
			t.Fatalf("%q: question: %v", name, err)
		}
		want := strings.TrimSuffix(name, ".") + "."
		if h.ID != 7 || h.Response || !h.RecursionDesired || q.Name.String() != want ||
			q.Type != dnsmessage.TypeA || q.Class != dnsmessage.ClassINET {
			t.Fatalf("%q parsed as %+v %+v", name, h, q)
		}
		if _, err := p.Question(); !errors.Is(err, dnsmessage.ErrSectionDone) {
			t.Fatalf("%q: a second question: %v", name, err)
		}
	}
}

func TestBuildDNSQueryPanicsOnANameDNSCannotCarry(t *testing.T) {
	label63 := strings.Repeat("x", 63)
	for _, name := range []string{
		"",
		".",
		"gate..example.com",
		label63 + "x.example.com", // a label of 64 bytes
		strings.Repeat(label63+".", 3) + strings.Repeat("y", 62), // 254 bytes, 256 on the wire
	} {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("BuildDNSQuery(%q) returned, want a panic", name)
				}
			}()
			BuildDNSQuery(1, name)
		}()
	}
}
