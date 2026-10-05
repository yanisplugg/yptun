package main

import (
	"path/filepath"
	"bytes"
	"encoding/binary"
	"os"
	"testing"
)

func portableBytes(stub, payload []byte) []byte {
	var b bytes.Buffer
	b.Write(stub)
	b.Write(payload)
	size := make([]byte, 8)
	binary.LittleEndian.PutUint64(size, uint64(len(payload)))
	b.Write(size)
	b.WriteString(trailerMagic)
	return b.Bytes()
}

// A signed portable: signtool pads to 8 bytes with zeros, then appends the certificate table.
func TestFindPayloadBeforeSignaturePadding(t *testing.T) {
	payload := []byte("PK-app-image")
	file := portableBytes([]byte("MZ-stub-bytes"), payload)
	for pad := 0; pad < 8; pad++ {
		signed := append(append(append([]byte{}, file...), make([]byte, pad)...), []byte("CERT-TABLE")...)
		end := int64(len(file) + pad) // what dataEnd reports: the certificate table's offset
		size, offset, err := findPayload(bytes.NewReader(signed), end)
		if err != nil {
			t.Fatalf("pad %d: %v", pad, err)
		}
		if got := signed[offset : offset+size]; !bytes.Equal(got, payload) {
			t.Fatalf("pad %d: payload %q", pad, got)
		}
	}
}

func TestFindPayloadRejectsPlainExe(t *testing.T) {
	if _, _, err := findPayload(bytes.NewReader([]byte("MZ just a program, nothing appended")), 35); err == nil {
		t.Fatal("expected an error for an exe without a trailer")
	}
}

// An unsigned PE (this test binary) has no certificate table: the data runs to the end of the file.
func TestDataEndOfUnsignedPe(t *testing.T) {
	self, err := os.Executable()
	if err != nil {
		t.Skip(err)
	}
	f, err := os.Open(self)
	if err != nil {
		t.Skip(err)
	}
	defer f.Close()
	info, _ := f.Stat()
	if got := dataEnd(f, info.Size()); got != info.Size() {
		t.Fatalf("dataEnd = %d, want file size %d", got, info.Size())
	}
}

func TestWithoutJavaOptions(t *testing.T) {
	in := []string{"PATH=x", "java_tool_options=-XX:+UseConcMarkSweepGC", "_JAVA_OPTIONS=-Xmx1g", "JDK_JAVA_OPTIONS=-Xss1m", "JAVA_HOME=jdk8"}
	got := withoutJavaOptions(in)
	if len(got) != 2 || got[0] != in[0] || got[1] != in[4] {
		t.Fatalf("got %q", got)
	}
}

func TestLaunchEnvCarriesLauncherPath(t *testing.T) {
	self, err := os.Executable()
	if err != nil {
		t.Skip(err)
	}
	got := launchEnv([]string{"PATH=x", "JAVA_TOOL_OPTIONS=-Xmx1g"})
	if len(got) != 2 || got[0] != "PATH=x" || got[1] != portableExeEnv+"="+self {
		t.Fatalf("got %q", got)
	}
}

func TestWriteEntryRejectsTraversal(t *testing.T) {
	root := t.TempDir()
	if err := writeEntry(root, "../evil.txt", []byte("x")); err == nil {
		t.Fatal("expected a path escaping the target to be refused")
	}
	if err := writeEntry(root, "app/ok.txt", []byte("x")); err != nil {
		t.Fatal(err)
	}
}

func TestCleanPathDropsOtherJavaAndPutsRuntimeFirst(t *testing.T) {
	javaDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(javaDir, "java.exe"), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	plain := t.TempDir()
	runtimeBin := t.TempDir()
	env := cleanPath([]string{"A=1", "Path=" + javaDir + ";" + plain}, runtimeBin)
	want := "Path=" + runtimeBin + ";" + plain
	if len(env) != 2 || env[0] != "A=1" || env[1] != want {
		t.Fatalf("got %v, want path %q", env, want)
	}
}
