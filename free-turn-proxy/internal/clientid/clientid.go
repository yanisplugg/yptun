// Package clientid управляет персистентным ID клиента (allowlist сервера, seed отпечатка персоны).
package clientid

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"

	"github.com/samosvalishe/free-turn-proxy/internal/statedir"
)

const fileName = "client_config.json"

type fileFormat struct {
	ClientID string `json:"client_id"`
}

// Resolve возвращает существующий или сгенерированный ID. persisted=false при ошибке записи на диск.
func Resolve(id string, paths []string) (resolved string, persisted bool, err error) {
	if id != "" {
		return id, true, nil
	}

	for _, b := range statedir.ReadEach(paths) {
		var f fileFormat
		if json.Unmarshal(b, &f) == nil && f.ClientID != "" {
			return f.ClientID, true, nil
		}
	}

	buf := make([]byte, 16)
	if _, rerr := rand.Read(buf); rerr != nil {
		return "", false, fmt.Errorf("generate client id: %w", rerr)
	}
	newID := hex.EncodeToString(buf)

	b, _ := json.MarshalIndent(fileFormat{ClientID: newID}, "", "  ")
	return newID, statedir.WriteFirst(paths, b), nil
}

func DefaultPaths() []string { return statedir.Paths(fileName) }
