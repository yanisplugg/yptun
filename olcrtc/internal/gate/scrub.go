package gate

import (
	"cmp"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"slices"
	"strings"
)

// ai-generated: the whole file (the scrubber every log passes before it
// leaves the run).

// What stands in for a secret of the run in a log that leaves it.
const (
	placeholderKey  = "<key>"
	placeholderRoom = "<room>"
)

// keyLen is the length of a key in hex: 32 bytes.
const keyLen = 64

// minBase64Form is the shortest base64 form of a secret Scrub replaces: a
// shorter one stands for a secret of a few bytes and would match text that
// is not the secret.
const minBase64Form = 8

// Scrub replaces every secret of the run in text: a key (64 hex) becomes
// <key>, every other secret (a room URL, a room id, a slug, a channel id, a
// token) becomes <room>. A secret goes as written and as it reads inside a
// base64 run (see base64Forms): a server's debug log quotes XMPP stanza ids,
// base64 of a JID that holds the Jitsi host. Empty secrets are ignored. Text
// is scanned once from the left and each form is replaced whole where it
// starts, the longer one where two start at the same place, so a slug cannot
// leave the rest of its room URL behind and a placeholder is never scrubbed
// again.
func Scrub(text string, secrets ...string) string {
	// ai-generated: each secret's base64 forms join the secret itself.
	type form struct{ old, placeholder string }
	forms := make([]form, 0, 7*len(secrets))
	for _, s := range secrets {
		if s == "" {
			continue
		}
		p := placeholder(s)
		forms = append(forms, form{s, p})
		for _, f := range base64Forms(s) {
			forms = append(forms, form{f, p})
		}
	}
	if len(forms) == 0 {
		return text
	}
	slices.SortStableFunc(forms, func(a, b form) int { return cmp.Compare(len(b.old), len(a.old)) })
	pairs := make([]string, 0, 2*len(forms))
	for _, f := range forms {
		pairs = append(pairs, f.old, f.placeholder)
	}
	return strings.NewReplacer(pairs...).Replace(text)
}

// base64Forms is how a secret reads inside any longer base64 run, a stanza
// id or a token's payload: at each of the three byte offsets it may start
// at, in the standard and the URL-safe alphabet, the characters its own
// bytes alone decide. A form shorter than minBase64Form is left out.
func base64Forms(secret string) []string {
	// ai-generated: the encoded forms Scrub withholds with the secret.
	out := make([]string, 0, 6)
	for offset := range 3 {
		raw := make([]byte, offset+len(secret))
		copy(raw[offset:], secret)
		// The first characters also carry the bytes before the secret, the
		// last ones the bytes after it: neither is the secret's alone.
		from, to := (4*offset+2)/3, len(raw)*4/3
		if to-from < minBase64Form {
			continue
		}
		for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.URLEncoding} {
			if f := enc.EncodeToString(raw)[from:to]; !slices.Contains(out, f) {
				out = append(out, f)
			}
		}
	}
	return out
}

// placeholder is <key> for a 64-hex key and <room> for any other secret.
func placeholder(secret string) string {
	if len(secret) != keyLen {
		return placeholderRoom
	}
	if _, err := hex.DecodeString(secret); err != nil {
		return placeholderRoom
	}
	return placeholderKey
}

// ScrubFile writes to dst a copy of the log at src with the run's secrets
// replaced: how a raw log kept in a private directory becomes an artifact.
func ScrubFile(src, dst string, secrets ...string) error {
	raw, err := os.ReadFile(src)
	if err != nil {
		return fmt.Errorf("read log: %w", err)
	}
	return writeScrubbed(dst, string(raw), secrets)
}

// writeScrubbed stores text at path with the secrets replaced.
func writeScrubbed(path, text string, secrets []string) error {
	clean := []byte(Scrub(text, secrets...))
	if err := os.WriteFile(path, clean, 0o644); err != nil { //nolint:gosec // scrubbed: an artifact anyone may read
		return fmt.Errorf("write scrubbed log: %w", err)
	}
	return nil
}
