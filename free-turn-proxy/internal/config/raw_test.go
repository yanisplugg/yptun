package config

import (
	"reflect"
	"strings"
	"testing"

	"github.com/samosvalishe/free-turn-proxy/internal/uri"
)

func TestNormalizeVKLinks(t *testing.T) {
	cases := []struct {
		name  string
		links string
		link  string
		want  []string
	}{
		{"empty", "", "", nil},
		{"legacy link only", "", "https://vk.ru/call/join/CODE", []string{"CODE"}},
		{"links win over legacy", "https://vk.ru/call/join/NEW", "https://vk.ru/call/join/OLD", []string{"NEW"}},
		{"query stripped", "", "https://vk.ru/call/join/CODE?utm=1", []string{"CODE"}},
		{"path stripped", "", "https://vk.ru/call/join/CODE/extra", []string{"CODE"}},
		{"fragment stripped", "", "https://vk.ru/call/join/CODE#x", []string{"CODE"}},
		{"bare code", "", "CODE", []string{"CODE"}},
		{"spaces and blanks", " A , ,B ", "", []string{"A", "B"}},
		{"only separators", ",,", "", nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := normalizeVKLinks(tc.links, tc.link); !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("normalizeVKLinks(%q, %q) = %v, want %v", tc.links, tc.link, got, tc.want)
			}
		})
	}
}

func TestAssembleRejectsRawEnums(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(*raw)
		want   string
	}{
		{"transport", func(r *raw) { r.Transport = "sctp" }, "invalid -transport"},
		{"mode", func(r *raw) { r.Mode = "quic" }, "invalid -mode"},
		{"bond without tcp", func(r *raw) { r.Bond = true }, "-bond requires"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := defaultRaw()
			tc.mutate(&r)
			_, err := assemble(r)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("assemble() error = %v, want mention of %q", err, tc.want)
			}
		})
	}
}

func TestAssembleGenKeySkipsKeyDecode(t *testing.T) {
	r := defaultRaw()
	r.GenObfKey = true
	r.ObfProfile = "bogus"
	r.ObfKey = "not-hex"

	c, err := assemble(r)
	if err != nil {
		t.Fatalf("assemble() error = %v, want nil for -gen-obf-key", err)
	}
	if c.Obf.Key != nil {
		t.Errorf("Obf.Key = %v, want nil", c.Obf.Key)
	}
}

func TestApplyURIOverridesOnlyPresentFields(t *testing.T) {
	r := defaultRaw()
	r.Peer = "flag-peer:1"
	r.ClientID = "flag-id"
	r.Links = "https://vk.ru/call/join/FROMFLAG"

	r.applyURI(&uri.Config{
		Peer:      "uri-peer:2",
		Transport: TransportUDP,
		Mode:      ModeTCP,
		Bond:      true,
		N:         7,
	})

	if r.Peer != "uri-peer:2" {
		t.Errorf("Peer = %q, want URI value", r.Peer)
	}
	if r.Transport != TransportUDP || r.Mode != ModeTCP || !r.Bond || r.N != 7 {
		t.Errorf("URI fields not applied: %+v", r)
	}
	// URI не несёт ссылок и client-id - значения источника обязаны уцелеть.
	if r.ClientID != "flag-id" || r.Links != "https://vk.ru/call/join/FROMFLAG" {
		t.Errorf("URI wiped fields it does not carry: %+v", r)
	}
}

func TestApplyURIIgnoresZeroValues(t *testing.T) {
	r := defaultRaw()
	r.N = 5
	r.StreamsPerCred = 3
	r.Bond = true
	r.Mode = ModeTCP

	r.applyURI(&uri.Config{})

	if r.N != 5 || r.StreamsPerCred != 3 || !r.Bond || r.Mode != ModeTCP {
		t.Errorf("empty URI must not reset fields: %+v", r)
	}
}
