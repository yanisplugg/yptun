package wdtt

import (
	"testing"
	"time"
)

func TestSetCustomVKCredentialsAddsIndependentLegacyProvider(t *testing.T) {
	originalCredentials := append([]VKCredentials(nil), vkCredentialsList...)
	originalCustomCredentials := customVKCredentials
	defer func() {
		vkCredentialsList = originalCredentials
		customVKCredentials = originalCustomCredentials
	}()
	vkCredentialsList = []VKCredentials{
		{ClientID: "6287487", ClientSecret: "built-in-one"},
		{ClientID: "8202606", ClientSecret: "built-in-two"},
	}
	customVKCredentials = nil

	if err := SetCustomVKCredentials("12345678", "test-only-secret"); err != nil {
		t.Fatalf("SetCustomVKCredentials returned error: %v", err)
	}
	candidates := legacyVKCredentialCandidates()
	if len(candidates) != 3 {
		t.Fatalf("candidate count = %d, want 3", len(candidates))
	}
	if candidates[0].Provider != legacyVKProviderCustom || candidates[0].Credentials.ClientID != "12345678" {
		t.Fatalf("first candidate = %#v, want custom credentials", candidates[0])
	}
	if candidates[1].Provider != legacyVKProviderBuiltIn || candidates[1].Credentials.ClientID != "6287487" {
		t.Fatalf("second candidate = %#v, want first built-in credentials", candidates[1])
	}
	if candidates[2].Provider != legacyVKProviderBuiltIn || candidates[2].Credentials.ClientID != "8202606" {
		t.Fatalf("third candidate = %#v, want second built-in credentials", candidates[2])
	}
	if vkCallsClientID != "8093730" {
		t.Fatalf("fixed VK Calls client ID = %q", vkCallsClientID)
	}
}

func TestSetCustomVKCredentialsRejectsIncompleteValues(t *testing.T) {
	tests := []struct {
		name   string
		id     string
		secret string
	}{
		{name: "empty id", secret: "test-only-secret"},
		{name: "non numeric id", id: "123abc", secret: "test-only-secret"},
		{name: "empty secret", id: "12345678"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := SetCustomVKCredentials(test.id, test.secret); err == nil {
				t.Fatal("expected validation error")
			}
		})
	}
}

func TestCredentialCacheLifetime(t *testing.T) {
	tests := []struct {
		name     string
		reported time.Duration
		want     time.Duration
	}{
		{name: "legacy fallback", want: 9 * time.Minute},
		{name: "server lifetime", reported: 10 * time.Minute, want: 9 * time.Minute},
		{name: "short lifetime", reported: 100 * time.Second, want: 80 * time.Second},
		{name: "short safe margin", reported: 20 * time.Second, want: 16 * time.Second},
		{name: "bounded", reported: 48 * time.Hour, want: 23*time.Hour + 59*time.Minute},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := credentialCacheLifetime(test.reported); got != test.want {
				t.Fatalf("cache lifetime = %v, want %v", got, test.want)
			}
		})
	}
}
