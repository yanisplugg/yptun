// Package statedir управляет путями сохранения состояния клиента между запусками.
package statedir

import (
	"os"
	"path/filepath"
	"sync/atomic"
)

//nolint:gochecknoglobals // ставится один раз на процесс до первого Paths
var dirOverride atomic.Pointer[string]

// SetDir переопределяет каталог состояния (актуально для Android app-uid).
func SetDir(dir string) {
	if dir == "" {
		dirOverride.Store(nil)
		return
	}
	dirOverride.Store(&dir)
}

// Paths возвращает список путей к файлу name в порядке приоритета.
func Paths(name string) []string {
	if dir := dirOverride.Load(); dir != nil {
		return []string{filepath.Join(*dir, name)}
	}
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
		paths = append(paths, filepath.Join(d, name))
	}
	return paths
}

// ReadEach возвращает содержимое всех читаемых файлов из paths.
func ReadEach(paths []string) [][]byte {
	out := make([][]byte, 0, len(paths))
	for _, path := range paths {
		b, err := os.ReadFile(path) //nolint:gosec // имя фиксировано, каталог даёт хост
		if err == nil {
			out = append(out, b)
		}
	}
	return out
}

// WriteFirst сохраняет data по первому доступному для записи пути.
func WriteFirst(paths []string, data []byte) bool {
	for _, path := range paths {
		if os.MkdirAll(filepath.Dir(path), 0o700) != nil {
			continue
		}
		if writeAtomic(path, data) == nil {
			return true
		}
	}
	return false
}

// writeAtomic атомарно перезаписывает файл через .tmp, предотвращая частичную запись.
func writeAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil { //nolint:gosec // 0o600 для файла с секретами
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}
