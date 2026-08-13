// Package clientid хранит постоянный идентификатор клиента между запусками.
//
// ID участвует в allowlist сервера (-clients-file) и служит seed'ом отпечатка
// VK-персоны, поэтому ротация на каждый запуск ломает и авторизацию, и
// стабильность fingerprint. Пути-кандидаты задаёт хост: у desktop это каталог
// рядом с бинарём, у мобильного приложения - его private storage.
package clientid

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

const fileName = "client_config.json"

type fileFormat struct {
	ClientID string `json:"client_id"`
}

// Resolve возвращает ID клиента: заданный явно, прочитанный из первого
// читаемого файла или новый сгенерированный.
//
// persisted=false, когда новый ID не удалось записать ни по одному пути -
// вызывающий решает, шуметь ли об этом (такой ID живёт до перезапуска).
func Resolve(id string, paths []string) (resolved string, persisted bool, err error) {
	if id != "" {
		return id, true, nil
	}

	for _, path := range paths {
		b, rerr := os.ReadFile(path) //nolint:gosec // имя фиксировано, каталог даёт хост
		if rerr != nil {
			continue
		}
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

	// Маршалинг структуры из одной строки не падает.
	b, _ := json.MarshalIndent(fileFormat{ClientID: newID}, "", "  ")
	for _, path := range paths {
		if os.MkdirAll(filepath.Dir(path), 0o700) != nil {
			continue
		}
		if os.WriteFile(path, b, 0o600) == nil { //nolint:gosec // 0o600 для auth-токена
			return newID, true, nil
		}
	}
	return newID, false, nil
}

// DefaultPaths - кандидаты в порядке предпочтения: рядом с бинарём (desktop,
// переносимость), затем per-user UserConfigDir и TempDir. На Android каталог
// бинаря read-only, поэтому запись падает на последние варианты.
func DefaultPaths() []string {
	seen := map[string]bool{}
	var dirs []string
	add := func(d string) {
		if d == "" || seen[d] {
			return
		}
		seen[d] = true
		dirs = append(dirs, d)
	}
	if exe, err := os.Executable(); err == nil {
		add(filepath.Dir(exe))
	}
	add(filepath.Dir(os.Args[0]))
	if cfgDir, err := os.UserConfigDir(); err == nil {
		add(filepath.Join(cfgDir, "free-turn-proxy"))
	}
	add(os.TempDir())

	paths := make([]string, 0, len(dirs))
	for _, d := range dirs {
		paths = append(paths, filepath.Join(d, fileName))
	}
	return paths
}
