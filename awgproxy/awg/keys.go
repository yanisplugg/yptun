package awg

import (
	"crypto/rand"
	"encoding/base64"

	"golang.org/x/crypto/curve25519"
)

// GenerateKeyPair creates a fresh Curve25519 WireGuard/AmneziaWG keypair and returns it as
// "<privateBase64>|<publicBase64>" (standard wg key encoding). Used by the Telegram-over-WARP flow
// to register a device with Cloudflare; doing the crypto in Go avoids Android's inconsistent X25519
// provider support across API levels. Returns "" only if the system RNG fails (never in practice).
func GenerateKeyPair() string {
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		return ""
	}
	// Clamp per RFC 7748 so the scalar is a valid Curve25519 private key.
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64

	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return ""
	}
	enc := base64.StdEncoding
	return enc.EncodeToString(priv[:]) + "|" + enc.EncodeToString(pub)
}
