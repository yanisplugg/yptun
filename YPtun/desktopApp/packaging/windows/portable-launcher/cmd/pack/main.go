// pack builds the portable payload: the app image as a tar stream, zstd-compressed.
//
// Usage: pack <appDir> <out.zst>
//
// Jars are rewritten with STORED entries first. A jar is a Deflate zip, which no outer compressor
// can squeeze any further; stored, its contents (the Go cores, dlls, linux server binaries) are
// compressed together by zstd, which is the whole size win over a plain zip. The JVM reads stored
// jars just fine.
package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"github.com/klauspost/compress/zstd"
)

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: pack <appDir> <out.zst>")
		os.Exit(2)
	}
	if err := run(os.Args[1], os.Args[2]); err != nil {
		fmt.Fprintln(os.Stderr, "pack:", err)
		os.Exit(1)
	}
}

func run(root, out string) error {
	f, err := os.Create(out)
	if err != nil {
		return err
	}
	defer f.Close()
	enc, err := zstd.NewWriter(f,
		zstd.WithEncoderLevel(zstd.SpeedBestCompression),
		zstd.WithWindowSize(1<<27), // 128 MiB: the launcher's decoder is set to accept it
		zstd.WithEncoderConcurrency(1),
	)
	if err != nil {
		return err
	}
	tw := tar.NewWriter(enc)
	err = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil || path == root {
			return err
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		name := filepath.ToSlash(rel)
		if d.IsDir() {
			return tw.WriteHeader(&tar.Header{Name: name + "/", Typeflag: tar.TypeDir, Mode: 0o755})
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		if strings.HasSuffix(strings.ToLower(name), ".jar") {
			if data, err = storeJar(data); err != nil {
				return fmt.Errorf("%s: %w", name, err)
			}
		}
		if err := tw.WriteHeader(&tar.Header{Name: name, Size: int64(len(data)), Mode: 0o644}); err != nil {
			return err
		}
		_, err = tw.Write(data)
		return err
	})
	if err != nil {
		return err
	}
	if err := tw.Close(); err != nil {
		return err
	}
	if err := enc.Close(); err != nil {
		return err
	}
	return f.Close()
}

// storeJar returns the same jar with every entry stored uncompressed.
func storeJar(data []byte) ([]byte, error) {
	r, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, err
	}
	var out bytes.Buffer
	w := zip.NewWriter(&out)
	for _, e := range r.File {
		h := &zip.FileHeader{Name: e.Name, Method: zip.Store, Modified: e.Modified, Comment: e.Comment}
		h.SetMode(e.Mode())
		dst, err := w.CreateHeader(h)
		if err != nil {
			return nil, err
		}
		if e.FileInfo().IsDir() {
			continue
		}
		src, err := e.Open()
		if err != nil {
			return nil, err
		}
		_, err = io.Copy(dst, src)
		src.Close()
		if err != nil {
			return nil, err
		}
	}
	if err := w.Close(); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
