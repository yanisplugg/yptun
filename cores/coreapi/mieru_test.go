package coreapi

import "testing"

func TestMieruProfileFromSimpleLink(t *testing.T) {
	profile, err := mieruProfile("mierus://user:p%40ss@203.0.113.9?profile=fast&mtu=1400&multiplexing=MULTIPLEXING_HIGH&port=4443-4450&protocol=TCP#Tokyo")
	if err != nil {
		t.Fatal(err)
	}
	if profile.GetProfileName() != "fast" {
		t.Fatalf("profile name = %q", profile.GetProfileName())
	}
	if profile.GetUser().GetName() != "user" || profile.GetUser().GetPassword() != "p@ss" {
		t.Fatalf("unexpected credentials: %#v", profile.GetUser())
	}
	if len(profile.GetServers()) != 1 || profile.GetServers()[0].GetIpAddress() != "203.0.113.9" {
		t.Fatalf("unexpected servers: %#v", profile.GetServers())
	}
}
