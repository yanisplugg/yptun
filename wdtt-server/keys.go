package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/crypto/curve25519"
)

// ==================== Ключи ====================

type wgKeys struct {
	serverPrivate, serverPublic, clientPrivate, clientPublic string
}

func b64ToHex(s string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return "", err
	}
	if len(b) != 32 {
		return "", fmt.Errorf("key length %d != 32", len(b))
	}
	return hex.EncodeToString(b), nil
}

func generateKeyPair() (privB64, pubB64 string, err error) {
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		return "", "", err
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return "", "", err
	}
	return base64.StdEncoding.EncodeToString(priv[:]),
		base64.StdEncoding.EncodeToString(pub), nil
}

func loadOrGenerateKeys(dir string) (*wgKeys, error) {
	f := filepath.Join(dir, "wg-keys.dat")
	if data, err := os.ReadFile(f); err == nil {
		if err := os.Chmod(f, 0600); err != nil {
			return nil, fmt.Errorf("protect key file: %w", err)
		}
		lines := strings.Split(strings.TrimSpace(string(data)), "\n")
		if len(lines) >= 4 {
			keys := &wgKeys{
				serverPrivate: strings.TrimSpace(lines[0]),
				serverPublic:  strings.TrimSpace(lines[1]),
				clientPrivate: strings.TrimSpace(lines[2]),
				clientPublic:  strings.TrimSpace(lines[3]),
			}
			for _, k := range []string{keys.serverPrivate, keys.serverPublic,
				keys.clientPrivate, keys.clientPublic} {
				if _, err := b64ToHex(k); err != nil {
					goto generate
				}
			}
			log.Printf("[WG] Ключи загружены из %s", f)
			return keys, nil
		}
	}
generate:
	log.Println("[WG] Генерирую новые ключи...")
	sPriv, sPub, err := generateKeyPair()
	if err != nil {
		return nil, err
	}
	cPriv, cPub, err := generateKeyPair()
	if err != nil {
		return nil, err
	}
	keys := &wgKeys{sPriv, sPub, cPriv, cPub}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, fmt.Errorf("create key directory: %w", err)
	}
	if err := os.WriteFile(f, []byte(fmt.Sprintf("%s\n%s\n%s\n%s\n",
		keys.serverPrivate, keys.serverPublic,
		keys.clientPrivate, keys.clientPublic)), 0600); err != nil {
		return nil, fmt.Errorf("write key file: %w", err)
	}
	log.Printf("[WG] Ключи сохранены в %s", f)
	return keys, nil
}
