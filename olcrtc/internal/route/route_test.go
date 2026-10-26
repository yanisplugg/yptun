package route

import (
	"errors"
	"fmt"
	"net/netip"
	"strings"
	"testing"
)

func TestParseEmptyIsOff(t *testing.T) {
	for _, text := range []string{"", "\n\n", "# only a comment\n", "  \n#a\n  # b"} {
		r, err := Parse(text)
		if err != nil || r != nil {
			t.Fatalf("Parse(%q) = %v, %v; want nil, nil", text, r, err)
		}
	}
	var off *Rules
	if off.MatchDomain("example.ru") || off.MatchIP(netip.MustParseAddr("10.0.0.1")) || off.MatchHost("x") {
		t.Fatal("nil rules matched")
	}
	if got := off.Summary(); got != "off" {
		t.Fatalf("nil Summary() = %q", got)
	}
}

func TestParseNamesAndSuffixes(t *testing.T) {
	r, err := Parse("domain:ru\nfull:api.example.com\n# c\n\n domain:Example.Org. \nfull:Bare.\n")
	if err != nil {
		t.Fatal(err)
	}
	for name, want := range map[string]bool{
		"ru": true, "yandex.ru": true, "mail.Yandex.RU.": true, "notru": false, "ru.com": false,
		"api.example.com": true, "www.api.example.com": false, "example.com": false,
		"example.org": true, "a.example.org": true, "bare": true, "": false, ".": false,
	} {
		if got := r.MatchDomain(name); got != want {
			t.Errorf("MatchDomain(%q) = %v, want %v", name, got, want)
		}
	}
	if got := r.Summary(); got != "4 names, 0 prefixes" {
		t.Errorf("Summary() = %q", got)
	}
}

func TestParsePrefixesMergeAndMatch(t *testing.T) {
	r, err := Parse("10.0.0.0/8\n10.1.0.0/16\n192.168.1.0/24\n192.168.2.0/24\n5.6.7.8\n2001:db8::/32\n")
	if err != nil {
		t.Fatal(err)
	}
	for addr, want := range map[string]bool{
		"10.200.1.1": true, "9.255.255.255": false, "11.0.0.0": false,
		"192.168.1.255": true, "192.168.2.0": true, "192.168.3.0": false, "192.168.0.255": false,
		"5.6.7.8": true, "5.6.7.9": false, "5.6.7.7": false,
		"2001:db8::1": true, "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff": true, "2001:db9::1": false,
		"::ffff:10.0.0.1": true, "::1": false,
	} {
		if got := r.MatchIP(netip.MustParseAddr(addr)); got != want {
			t.Errorf("MatchIP(%s) = %v, want %v", addr, got, want)
		}
	}
	// Six lines, two of them inside 10/8 or adjacent to a neighbour: the
	// count says what was written, the ranges say what is matched.
	if got := r.Summary(); got != "0 names, 6 prefixes" {
		t.Errorf("Summary() = %q", got)
	}
	if len(r.ranges) != 4 {
		t.Errorf("ranges = %d, want 4 (10/8, 192.168.1-2, 5.6.7.8, 2001:db8::/32)", len(r.ranges))
	}
}

func TestMatchHostPicksByShape(t *testing.T) {
	r, err := Parse("domain:ru\n10.0.0.0/8\n")
	if err != nil {
		t.Fatal(err)
	}
	for host, want := range map[string]bool{
		"ozon.ru": true, "10.9.8.7": true, "[10.9.8.7]": true, "::ffff:10.1.1.1": true,
		"example.com": false, "8.8.8.8": false, "fe80::1%en0": false,
	} {
		if got := r.MatchHost(host); got != want {
			t.Errorf("MatchHost(%q) = %v, want %v", host, got, want)
		}
	}
}

func TestParseRejectsUnknownLines(t *testing.T) {
	for _, text := range []string{
		"keyword:ru", "regexp:.*", "example.ru", "domain:", "full: ", "10.0.0.0/33", "not an ip",
		"domain:a b", "geoip:ru", "domain:ru extra",
	} {
		_, err := Parse("domain:ru\n" + text + "\n")
		if !errors.Is(err, ErrRule) {
			t.Errorf("Parse(%q) error = %v, want ErrRule", text, err)
			continue
		}
		if !strings.Contains(err.Error(), "line 2") {
			t.Errorf("Parse(%q) error %q does not name line 2", text, err)
		}
	}
}

func TestParseKeepsWindowsLineEndings(t *testing.T) {
	r, err := Parse("domain:ru\r\n1.2.3.0/24\r\n")
	if err != nil {
		t.Fatal(err)
	}
	if !r.MatchDomain("a.ru") || !r.MatchIP(netip.MustParseAddr("1.2.3.4")) {
		t.Fatal("rules with CRLF endings did not match")
	}
}

func BenchmarkMatchIP(b *testing.B) {
	var sb strings.Builder
	for i := range 12000 {
		_, _ = fmt.Fprintf(&sb, "%s/24\n", netip.AddrFrom4([4]byte{10, byte(i / 256), byte(i % 256), 0}))
	}
	r, err := Parse(sb.String())
	if err != nil {
		b.Fatal(err)
	}
	ip := netip.MustParseAddr("10.20.7.9")
	b.ReportAllocs()
	b.ResetTimer()
	for range b.N {
		if !r.MatchIP(ip) {
			b.Fatal("no match")
		}
	}
}

func BenchmarkMatchDomain(b *testing.B) {
	r, err := Parse("domain:ru\nfull:api.example.com\n")
	if err != nil {
		b.Fatal(err)
	}
	b.ReportAllocs()
	b.ResetTimer()
	for range b.N {
		if !r.MatchDomain("static.cdn.mail.yandex.ru") {
			b.Fatal("no match")
		}
	}
}
