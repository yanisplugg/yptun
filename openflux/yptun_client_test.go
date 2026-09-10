package main

import "testing"

func TestDnsServerAddr(t *testing.T) {
	for in, want := range map[string]string{
		"":                "",
		" 1.1.1.1 ":       "1.1.1.1:53",
		"1.1.1.1:5353":    "1.1.1.1:5353",
		"2001:db8::1":     "[2001:db8::1]:53",
		"[2001:db8::1]":   "[2001:db8::1]:53",
		"[2001:db8::1]:5": "[2001:db8::1]:5",
		"dns.example":     "dns.example:53",
	} {
		if got := dnsServerAddr(in); got != want {
			t.Errorf("dnsServerAddr(%q) = %q, want %q", in, got, want)
		}
	}
}
