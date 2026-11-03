package gate

import (
	"encoding/base64"
	"errors"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// ai-generated: whole file, cover for the scrubber and the logs it writes.

func logPrintf(format string, a ...any) { log.Printf(format, a...) }

func TestScrubReplacesRoomsAndKeys(t *testing.T) {
	key := strings.Repeat("ab", 32)
	room := "https://meet.example.org/gate-7f3a9c"
	in := "jitsi: joining MUC meet.example.org/gate-7f3a9c as X … key=" + key + " room=" + room
	out := Scrub(in, key, room, "gate-7f3a9c")
	if strings.Contains(out, key) || strings.Contains(out, "gate-7f3a9c") {
		t.Fatalf("scrubbed = %q", out)
	}
	if !strings.Contains(out, "<key>") || !strings.Contains(out, "<room>") {
		t.Fatalf("placeholders missing: %q", out)
	}
	if Scrub("plain", "") != "plain" {
		t.Fatal("empty secret must be ignored")
	}
}

// A slug replaced before its URL would leave the URL's host behind, so the
// longest secret wins wherever two start at the same place, whatever order
// they were given in.
func TestScrubReplacesTheLongestSecretWhole(t *testing.T) {
	room := "https://meet.example.org/gate-7f3a9c"
	got := Scrub("room="+room+" slug=gate-7f3a9c channel=gate-7f3a9c-ch", "gate-7f3a9c", room, "gate-7f3a9c-ch")
	if want := "room=<room> slug=<room> channel=<room>"; got != want {
		t.Fatalf("Scrub = %q, want %q", got, want)
	}
}

// ai-generated: a secret a server's debug log quotes base64-encoded, as an
// XMPP stanza id carries the JID with the Jitsi host in it, is withheld too,
// at whatever byte of the encoded run it starts and in either alphabet: no
// reading of what is left decodes to four bytes of it.
func TestScrubWithholdsTheBase64FormsOfASecret(t *testing.T) {
	host := "meet.example.invalid"
	for offset := range 3 {
		jid := strings.Repeat("u", offset) + "fake-endpoint@" + host + "/fake-resource\x00fake-nonce"
		for _, enc := range []struct{ encode, decode *base64.Encoding }{
			{base64.StdEncoding, base64.RawStdEncoding}, {base64.RawURLEncoding, base64.RawURLEncoding},
		} {
			line := "[xmpp:loop] <- <iq id='" + enc.encode.EncodeToString([]byte(jid)) + "' type='result'/>"
			if decodedPart(line, enc.decode, host) == "" {
				t.Fatalf("offset %d: the check reads no secret in the raw line %q", offset, line)
			}
			out := Scrub(line, host)
			if !strings.Contains(out, "<room>") {
				t.Fatalf("offset %d: nothing withheld in %q", offset, out)
			}
			if part := decodedPart(out, enc.decode, host); part != "" {
				t.Fatalf("offset %d: %q still decodes to %q of the secret", offset, out, part)
			}
		}
	}
	// A secret of a few bytes has no encoded form: it would match text that
	// is not the secret.
	if forms := base64Forms("abcd"); len(forms) != 0 {
		t.Fatalf("a 4-byte secret has encoded forms %q", forms)
	}
}

// decodedPart is a 4-byte run of secret that a base64 run of text, read
// from any of its first four characters, decodes to; "" when none does.
func decodedPart(text string, dec *base64.Encoding, secret string) string {
	for _, run := range regexp.MustCompile(`[A-Za-z0-9+/_-]+`).FindAllString(text, -1) {
		for shift := 0; shift < 4 && shift < len(run); shift++ {
			q := run[shift:]
			decoded, _ := dec.DecodeString(q[:len(q)/4*4]) // what decodes before a bad byte counts
			for i := 0; i+4 <= len(secret); i++ {
				if strings.Contains(string(decoded), secret[i:i+4]) {
					return secret[i : i+4]
				}
			}
		}
	}
	return ""
}

func TestCellLogWriteScrubbed(t *testing.T) {
	c := StartCapture()
	defer c.Stop()
	l := c.Begin()
	logPrintf("joined room secret-room-1 with %s", strings.Repeat("cd", 32))
	p := filepath.Join(t.TempDir(), "cell.log")
	if err := l.WriteScrubbed(p, strings.Repeat("cd", 32), "secret-room-1"); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(p)
	if strings.Contains(string(raw), "secret-room-1") || strings.Contains(string(raw), "cdcd") {
		t.Fatalf("file = %q", string(raw))
	}
	if !strings.Contains(string(raw), "joined room <room> with <key>") {
		t.Fatalf("file lost the line: %q", string(raw))
	}
}

// A server's raw log stays in a private directory; what is uploaded is the
// copy ScrubFile writes, clean of the run's room, key, channel and token.
func TestScrubFileLeavesNoSecretOfTheRun(t *testing.T) {
	key := strings.Repeat("0f", 32)
	slug := "gate-0123456789ab"
	room := "https://meet.example.invalid/" + slug
	channel := "gate-ba9876543210"
	token := "fake-wb-token-not-a-real-one"
	// ai-generated: an override host given with a port reaches a log bare.
	override := "https://Jitsi.Example.Invalid:8443/"
	raw := strings.Join([]string{
		"2026/09/18 10:00:00 Connecting transport=datachannel provider=jitsi ...",
		"2026/09/18 10:00:00 jitsi: joining MUC meet.example.invalid/" + slug + " as olcrtc …",
		"2026/09/18 10:00:01 j: rejoin joining room " + slug + " as olcrtc",
		"2026/09/18 10:00:02 room=" + room + " key=" + key + " channel=" + channel + " token=" + token,
		"2026/09/18 10:00:03 Link connected",
		"2026/09/18 10:00:04 dial wss://jitsi.example.invalid:8443/xmpp-websocket: " +
			"lookup jitsi.example.invalid on 8.8.8.8:53: no such host",
		"2026/09/18 10:00:05 <service host='jitsi.example.invalid' port='443' type='turns'/>",
	}, "\n") + "\n"
	dir := t.TempDir()
	src, dst := filepath.Join(dir, "srv-raw.log"), filepath.Join(dir, "srv.log")
	if err := os.WriteFile(src, []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}
	secrets := append(localSecrets(LocalOptions{JitsiHosts: []string{override}}), room, key, slug, channel, token)
	if err := ScrubFile(src, dst, secrets...); err != nil {
		t.Fatal(err)
	}
	out, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{key, slug, channel, token, "0123456789ab", "jitsi.example.invalid"} {
		if strings.Contains(strings.ToLower(string(out)), secret) {
			t.Fatalf("scrubbed log still carries %q:\n%s", secret, out)
		}
	}
	for _, kept := range []string{
		"joining MUC meet.example.invalid/<room> as olcrtc", "room=<room> key=<key>", "Link connected",
		"dial wss://<room>/xmpp-websocket: lookup <room> on 8.8.8.8:53", "<service host='<room>' port='443'",
	} {
		if !strings.Contains(string(out), kept) {
			t.Fatalf("scrubbed log lost %q:\n%s", kept, out)
		}
	}
	if err := ScrubFile(filepath.Join(dir, "missing.log"), dst); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("ScrubFile of a missing log = %v, want fs.ErrNotExist", err)
	}
}
