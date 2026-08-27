package config

import (
	"encoding/hex"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
)

const (
	docsExampleKey = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
	// docsExampleKeyPlaceholder is what the docs print where a real PSK goes.
	docsExampleKeyPlaceholder = "REPLACE_ME_WITH_64_HEX_CHARS"
)

// TestMain registers the built-in providers, engines and transports so the
// documented configs are validated against the same registry the CLI has.
func TestMain(m *testing.M) {
	session.RegisterDefaults()
	os.Exit(m.Run())
}

func TestDocumentationExamplesLoadStrictly(t *testing.T) {
	examplesRoot := filepath.Join("..", "..", "docs", "examples")
	err := filepath.WalkDir(examplesRoot, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() || filepath.Ext(path) != ".yaml" {
			return nil
		}
		t.Run(filepath.ToSlash(path), func(t *testing.T) {
			loadDocumentationExample(t, path)
		})
		return nil
	})
	if err != nil {
		t.Fatalf("walk documentation examples: %v", err)
	}
}

func TestDocumentationYAMLBlocksLoadStrictly(t *testing.T) {
	paths, err := filepath.Glob(filepath.Join("..", "..", "docs", "*.md"))
	if err != nil {
		t.Fatalf("glob documentation: %v", err)
	}
	for _, path := range paths {
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			t.Fatalf("read %s: %v", path, readErr)
		}
		for index, block := range documentationYAMLBlocks(string(data)) {
			name := fmt.Sprintf("%s/yaml-%d", filepath.Base(path), index+1)
			t.Run(name, func(t *testing.T) {
				loadDocumentationYAML(t, filepath.Base(path), []byte(block), true)
			})
		}
	}
}

func loadDocumentationExample(t *testing.T, path string) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read example: %v", err)
	}
	loadDocumentationYAML(t, filepath.Base(path), data, false)
}

// loadDocumentationYAML parses one documented config and runs it through the
// CLI's own gate. placeholderKey allows prose to keep a readable stand-in
// where a real PSK goes; files under docs/examples must be copy-paste ready
// and keep theirs.
func loadDocumentationYAML(t *testing.T, name string, data []byte, placeholderKey bool) {
	t.Helper()
	content := strings.ReplaceAll(string(data),
		`key_file: "./olcrtc.key"`, `key: "`+docsExampleKey+`"`)
	content = strings.ReplaceAll(content,
		`key_file: ./olcrtc.key`, `key: "`+docsExampleKey+`"`)
	content = strings.ReplaceAll(content, docsExampleKeyPlaceholder, docsExampleKey)
	tempPath := filepath.Join(t.TempDir(), name+".yaml")
	// #nosec G703 -- filepath.Base confines the generated file to t.TempDir.
	if err := os.WriteFile(tempPath, []byte(content), 0o600); err != nil {
		t.Fatalf("write temporary example: %v", err)
	}
	file, err := Load(tempPath)
	if err != nil {
		t.Fatalf("Load(%s): %v", name, err)
	}
	if placeholderKey && !validDocumentationKey(file.Crypto.Key) {
		file.Crypto.Key = docsExampleKey
	}
	validateDocumentationConfig(t, name, file)
}

func validDocumentationKey(key string) bool {
	decoded, err := hex.DecodeString(key)
	return err == nil && len(decoded) == 32
}

// validateDocumentationConfig runs a documented config through the same gate
// the CLI applies. Parsing alone was not enough: examples that parse but fail
// validation - a client block with no net.dns or no socks listener - read as
// complete and only fail once someone copies them.
//
// Blocks without a mode are field fragments, not runnable configs.
func validateDocumentationConfig(t *testing.T, name string, file File) {
	t.Helper()
	base := Apply(file)
	if base.Mode == "" {
		return
	}
	// Mirror the CLI: with profiles configured it is the merged profiles that
	// have to be complete, and the top level is only a set of shared defaults.
	if len(file.Profiles) > 0 {
		for index, profile := range file.Profiles {
			merged := session.ApplyDefaults(ApplyProfile(base, profile))
			if err := session.Validate(merged); err != nil {
				t.Fatalf("Validate(%s profile %d): %v", name, index+1, err)
			}
		}
		return
	}
	if err := session.Validate(session.ApplyDefaults(base)); err != nil {
		t.Fatalf("Validate(%s): %v", name, err)
	}
}

func documentationYAMLBlocks(source string) []string {
	var blocks []string
	var current []string
	inYAML := false
	for _, line := range strings.Split(source, "\n") {
		switch strings.TrimSpace(line) {
		case "```yaml":
			inYAML = true
			current = current[:0]
		case "```":
			if inYAML {
				blocks = append(blocks, strings.Join(current, "\n"))
				inYAML = false
			}
		default:
			if inYAML {
				current = append(current, line)
			}
		}
	}
	return blocks
}
