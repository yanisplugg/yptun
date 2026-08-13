package clientid

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestResolveKeepsExplicitID(t *testing.T) {
	id, persisted, err := Resolve("explicit", nil)
	if err != nil || id != "explicit" || !persisted {
		t.Fatalf("Resolve() = %q, %v, %v", id, persisted, err)
	}
}

func TestResolveReadsFirstValidFile(t *testing.T) {
	dir := t.TempDir()
	broken := filepath.Join(dir, "broken.json")
	good := filepath.Join(dir, fileName)
	if err := os.WriteFile(broken, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(good, []byte(`{"client_id":"stored"}`), 0o600); err != nil {
		t.Fatal(err)
	}

	id, persisted, err := Resolve("", []string{broken, good})
	if err != nil || id != "stored" || !persisted {
		t.Fatalf("Resolve() = %q, %v, %v", id, persisted, err)
	}
}

func TestResolveOverwritesFileWithEmptyID(t *testing.T) {
	empty := filepath.Join(t.TempDir(), fileName)
	if err := os.WriteFile(empty, []byte(`{"client_id":""}`), 0o600); err != nil {
		t.Fatal(err)
	}

	id, persisted, err := Resolve("", []string{empty})
	if err != nil || !persisted {
		t.Fatalf("Resolve() = %q, %v, %v", id, persisted, err)
	}
	if len(id) != 32 {
		t.Fatalf("generated id = %q, want 32 hex chars", id)
	}
	assertStoredID(t, empty, id)
}

func TestResolveCreatesMissingDirectory(t *testing.T) {
	target := filepath.Join(t.TempDir(), "nested", "dir", fileName)

	id, persisted, err := Resolve("", []string{target})
	if err != nil || !persisted {
		t.Fatalf("Resolve() = %q, %v, %v", id, persisted, err)
	}
	assertStoredID(t, target, id)
}

func assertStoredID(t *testing.T, path, want string) {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("id not persisted: %v", err)
	}
	var f fileFormat
	if json.Unmarshal(b, &f) != nil || f.ClientID != want {
		t.Fatalf("persisted file = %s, want client_id %q", b, want)
	}
}

func TestResolveGeneratedIDIsStableAcrossCalls(t *testing.T) {
	paths := []string{filepath.Join(t.TempDir(), fileName)}

	first, _, err := Resolve("", paths)
	if err != nil {
		t.Fatal(err)
	}
	second, _, err := Resolve("", paths)
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatalf("id rotated between calls: %q != %q", first, second)
	}
}

func TestResolveReportsUnpersistedID(t *testing.T) {
	id, persisted, err := Resolve("", nil)
	if err != nil {
		t.Fatal(err)
	}
	if persisted {
		t.Fatal("persisted = true without any writable path")
	}
	if len(id) != 32 {
		t.Fatalf("generated id = %q, want 32 hex chars", id)
	}
}

func TestDefaultPathsAreUniqueAndNamed(t *testing.T) {
	paths := DefaultPaths()
	if len(paths) == 0 {
		t.Fatal("DefaultPaths() is empty")
	}
	seen := map[string]bool{}
	for _, p := range paths {
		if filepath.Base(p) != fileName {
			t.Fatalf("path %q does not end with %q", p, fileName)
		}
		if seen[p] {
			t.Fatalf("duplicate path %q", p)
		}
		seen[p] = true
	}
}
