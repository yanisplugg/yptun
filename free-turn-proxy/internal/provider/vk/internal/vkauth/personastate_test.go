package vkauth

import (
	"os"
	"path/filepath"
	"testing"
)

func statePaths(t *testing.T) []string {
	t.Helper()
	return []string{filepath.Join(t.TempDir(), PersonaStateFile)}
}

func TestBurnedPersonaSurvivesRestart(t *testing.T) {
	paths := statePaths(t)

	c := New(Config{FingerprintSeed: "install-1", StatePaths: paths})
	burned := c.currentPersona()
	c.burnPersona(1)
	fresh := c.currentPersona()

	restarted := New(Config{FingerprintSeed: "install-1", StatePaths: paths})
	if got := restarted.currentPersona(); got.VisitorID == burned.VisitorID {
		t.Fatal("restart resurrected the burned persona")
	} else if got.VisitorID != fresh.VisitorID {
		t.Fatal("restart did not continue from the persisted generation")
	}
}

func TestPersonaStateIsSeedScoped(t *testing.T) {
	paths := statePaths(t)

	c := New(Config{FingerprintSeed: "install-1", StatePaths: paths})
	c.burnPersona(1)

	other := New(Config{FingerprintSeed: "install-2", StatePaths: paths})
	if other.identity.Gen != 0 {
		t.Fatalf("gen = %d for a foreign seed, want 0", other.identity.Gen)
	}
}

func TestPersonaStateSurvivesCorruptFile(t *testing.T) {
	dir := t.TempDir()
	broken, good := filepath.Join(dir, "broken.json"), filepath.Join(dir, PersonaStateFile)
	if err := os.WriteFile(broken, []byte("{"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(good, []byte(`{"seed":"install-1","gen":4}`), 0o600); err != nil {
		t.Fatal(err)
	}

	c := New(Config{FingerprintSeed: "install-1", StatePaths: []string{broken, good}})
	if c.identity.Gen != 4 {
		t.Fatalf("gen = %d, want 4", c.identity.Gen)
	}
}

func TestGenStoreKeepsHighestGen(t *testing.T) {
	s := genStore{paths: statePaths(t)}
	if !s.save("install-1", 3) || !s.save("install-1", 2) {
		t.Fatal("save reported failure")
	}
	if got := s.load("install-1"); got != 3 {
		t.Fatalf("gen = %d, want 3", got)
	}
}

func TestGenStoreWithoutPathsIsNoop(t *testing.T) {
	var s genStore
	if s.save("install-1", 1) || s.load("install-1") != 0 {
		t.Fatal("empty genStore touched state")
	}
}
