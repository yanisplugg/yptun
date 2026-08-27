package names

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func restoreDictionaries(t *testing.T) {
	t.Helper()

	saved := dictionaries.Load()
	t.Cleanup(func() { dictionaries.Store(saved) })
}

func TestParseLines(t *testing.T) {
	got := parseLines(" Alice \n\n Bob\n")
	want := []string{"Alice", "Bob"}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parseLines() = %#v, want %#v", got, want)
	}
}

func TestLoadFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "names.txt")

	if err := os.WriteFile(path, []byte(" Alice \n\nBob\n"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	got, err := loadFile(path)
	if err != nil {
		t.Fatalf("loadFile() error = %v", err)
	}

	want := []string{"Alice", "Bob"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("loadFile() = %#v, want %#v", got, want)
	}
}

func TestLoadFileRejectsEmpty(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "empty.txt")

	if err := os.WriteFile(path, []byte("\n  \n"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	if _, err := loadFile(path); !errors.Is(err, ErrEmptyDictionary) {
		t.Fatalf("loadFile() error = %v, want ErrEmptyDictionary", err)
	}
}

func TestLoadNameFilesOverridesDictionaries(t *testing.T) {
	restoreDictionaries(t)

	dir := t.TempDir()
	first := filepath.Join(dir, "first.txt")
	last := filepath.Join(dir, "last.txt")

	if err := os.WriteFile(first, []byte("Neo\n"), 0o600); err != nil {
		t.Fatalf("WriteFile(first) error = %v", err)
	}

	if err := os.WriteFile(last, []byte("Anderson\n"), 0o600); err != nil {
		t.Fatalf("WriteFile(last) error = %v", err)
	}

	if err := LoadNameFiles(first, last); err != nil {
		t.Fatalf("LoadNameFiles() error = %v", err)
	}

	if got := Generate(); got != "Neo Anderson" {
		t.Fatalf("Generate() = %q, want %q", got, "Neo Anderson")
	}
}

func TestLoadNameFilesReportsMissingFiles(t *testing.T) {
	restoreDictionaries(t)

	err := LoadNameFiles("missing-first", "missing-last")
	if err == nil {
		t.Fatal("LoadNameFiles() error = nil, want failure for a missing file")
	}

	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("LoadNameFiles() error = %v, want os.ErrNotExist", err)
	}
}

func TestLoadNameFilesKeepsDictionariesOnFailure(t *testing.T) {
	restoreDictionaries(t)

	dir := t.TempDir()
	first := filepath.Join(dir, "first.txt")

	if err := os.WriteFile(first, []byte("Neo\n"), 0o600); err != nil {
		t.Fatalf("WriteFile(first) error = %v", err)
	}

	before := dictionaries.Load()

	if err := LoadNameFiles(first, filepath.Join(dir, "missing.txt")); err == nil {
		t.Fatal("LoadNameFiles() error = nil, want failure")
	}

	if dictionaries.Load() != before {
		t.Fatal("LoadNameFiles() replaced the dictionaries despite failing")
	}
}

func TestGenerateFallsBackWhenDictionariesEmpty(t *testing.T) {
	restoreDictionaries(t)

	dictionaries.Store(&pool{})

	if got := Generate(); got != "anonymous user" {
		t.Fatalf("Generate() = %q, want anonymous user", got)
	}
}

func TestEmbeddedDictionariesDiffer(t *testing.T) {
	if embeddedNames == embeddedSurnames {
		t.Fatal("embedded surnames are a copy of the given names")
	}
}

func TestRandomIndexBounds(t *testing.T) {
	for range 20 {
		got := randomIndex(2)
		if got < 0 || got > 1 {
			t.Fatalf("randomIndex(2) = %d, out of range", got)
		}
	}

	if got := randomIndex(0); got != 0 {
		t.Fatalf("randomIndex(0) = %d, want 0", got)
	}
}
