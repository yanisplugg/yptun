// Package names generates human-looking display names for SFU peers so a
// tunnel participant is indistinguishable from an ordinary attendee.
//
// The embedded dictionaries are always available; LoadNameFiles replaces them
// with on-disk ones when an operator wants a different pool.
package names

import (
	"bufio"
	"crypto/rand"
	_ "embed"
	"errors"
	"fmt"
	"math/big"
	"os"
	"strings"
	"sync/atomic"
)

//go:embed data/names
var embeddedNames string

//go:embed data/surnames
var embeddedSurnames string

// ErrEmptyDictionary reports a dictionary file that contains no usable entries.
var ErrEmptyDictionary = errors.New("names: dictionary file is empty")

// dictionaries is swapped atomically so LoadNameFiles can run while other
// goroutines call Generate.
//
//nolint:gochecknoglobals // process-wide dictionary pool, swapped atomically
var dictionaries atomic.Pointer[pool]

type pool struct {
	first []string
	last  []string
}

//nolint:gochecknoinits // seeds the embedded dictionaries before first use
func init() {
	dictionaries.Store(&pool{
		first: parseLines(embeddedNames),
		last:  parseLines(embeddedSurnames),
	})
}

func parseLines(raw string) []string {
	lines := strings.Split(raw, "\n")
	out := make([]string, 0, len(lines))

	for _, line := range lines {
		if line = strings.TrimSpace(line); line != "" {
			out = append(out, line)
		}
	}

	return out
}

func loadFile(path string) ([]string, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open names file %q: %w", path, err)
	}
	defer func() {
		_ = file.Close()
	}()

	var out []string

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		if line := strings.TrimSpace(scanner.Text()); line != "" {
			out = append(out, line)
		}
	}

	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("scan names file %q: %w", path, err)
	}

	if len(out) == 0 {
		return nil, fmt.Errorf("%w: %s", ErrEmptyDictionary, path)
	}

	return out, nil
}

// LoadNameFiles replaces the embedded dictionaries with the given files.
// Unlike the embedded fallback these are operator-supplied, so an unreadable
// or empty file is reported instead of being silently ignored.
func LoadNameFiles(firstPath, lastPath string) error {
	first, err := loadFile(firstPath)
	if err != nil {
		return err
	}

	last, err := loadFile(lastPath)
	if err != nil {
		return err
	}

	dictionaries.Store(&pool{first: first, last: last})

	return nil
}

// Generate returns a random display name assembled from the active dictionaries.
func Generate() string {
	p := dictionaries.Load()
	if p == nil || len(p.first) == 0 || len(p.last) == 0 {
		return "anonymous user"
	}

	return p.first[randomIndex(len(p.first))] + " " + p.last[randomIndex(len(p.last))]
}

func randomIndex(limit int) int {
	if limit <= 1 {
		return 0
	}

	n, err := rand.Int(rand.Reader, big.NewInt(int64(limit)))
	if err != nil {
		return 0
	}

	return int(n.Int64())
}
