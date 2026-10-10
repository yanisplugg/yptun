// Single-file portable launcher for YPtun.
//
// The old portable was a 7-Zip SFX: it unpacked the whole 160 MB app image into a fresh temp
// directory on EVERY launch, which is the "распаковка" the user did not want (slow start, a new
// copy of the app left behind each time, and the app's own paths changing under it).
//
// This launcher carries the app image as a zip appended to its own .exe and unpacks it exactly
// ONCE, into %LOCALAPPDATA%\YPtun\portable\<version>. Every later launch finds that directory
// ready and starts the app immediately — so it stays one file to carry around, and only the very
// first run pays for unpacking. A JVM app with native DLLs cannot be executed from inside an .exe
// at all (Windows loads DLLs and the JRE from the filesystem, never from a container), so
// "unpack once, then never again" is as close to no-unpacking as this can get.
//
// Layout of the shipped file:
//
//	[ launcher .exe ][ app-image zip ][ uint64 zip size ][ "YPTUNPKG" ]( [ 0-7 zero pad ][ signature ] )
//
// The part in parentheses exists once the file is Authenticode-signed (see dataEnd).
package main

import (
	"fmt"
	"archive/tar"
	"debug/pe"
	"encoding/binary"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"

	"github.com/klauspost/compress/zstd"
)

// Set at build time: -ldflags "-X main.version=3.2.1 -X main.buildID=<hash>".
var version = "dev"

// Fingerprint of the payload this launcher carries, from build-portable.ps1 (the first 16 hex
// digits of the app image's SHA-256).
//
// The unpack directory is keyed on it, NOT on the version: two builds of the SAME version are the
// normal case here - the user asks for fixes "не меняя версию" - and keying on the version alone
// meant a freshly built portable found the previous build's directory already marked ready and
// started THAT one. The new code never ran, and it looked like the fixes had not been made.
var buildID = "dev"

const (
	trailerMagic = "YPTUNPKG"
	trailerSize  = 16 // uint64 payload size + 8 magic bytes
	appExe       = "YPtun.exe"
	// Written next to the app so it can tell itself apart from an installed copy
	// (org.olcbox.app.desktop.DesktopRuntimeMode).
	portableMarker = ".portable"
	readyMarker    = ".ready"
	// Tells the app which file the user actually double-clicked, so a self-update can replace THAT
	// file (the app itself runs from the unpacked copy under %LOCALAPPDATA%, not from this .exe).
	portableExeEnv = "YPTUN_PORTABLE_EXE"
)

func main() {
	// One unpack at a time: a first launch takes a few seconds and users double-click.
	if !claimSingleInstance() {
		return
	}

	// The app image goes to the first place that both unpacks and starts: antivirus or folder rules
	// on one profile folder must not make the portable unusable (issue #54).
	var history strings.Builder
	lastErr, lastDir, lastReason := "", "", ""
	for _, base := range unpackBases() {
		target, err := ensureUnpacked(base)
		if err != nil {
			lastErr = err.Error()
			fmt.Fprintf(&history, "unpacking into %s failed: %v\n", base, err)
			continue
		}
		reason, err := launch(filepath.Join(target, appExe))
		if err == nil && reason == "" {
			return
		}
		if err != nil {
			reason = "could not start " + appExe + ": " + err.Error()
		}
		lastDir, lastReason, lastErr = target, reason, ""
		fmt.Fprintf(&history, "starting from %s failed: %s\n", target, reason)
		if out := startupOutput(); out != "" {
			history.WriteString(out + "\n")
		}
	}
	if lastDir == "" {
		fatal(lastErr + "\n\n" + history.String())
		return
	}
	reportStartFailure(lastDir, lastReason, history.String())
}

// unpackBases lists where the app image may live, in order of preference: the per-user app data
// folder, then the temp folder, then the folder of the portable .exe itself.
func unpackBases() []string {
	var bases []string
	if b := os.Getenv("LOCALAPPDATA"); b != "" {
		bases = append(bases, b)
	}
	if t := os.TempDir(); t != "" {
		bases = append(bases, t)
	}
	if self, err := os.Executable(); err == nil {
		bases = append(bases, filepath.Dir(self))
	}
	return bases
}

// ensureUnpacked returns the directory holding a ready-to-run app image, unpacking it first if
// this is the first launch of this version.
func ensureUnpacked(base string) (string, error) {
	root := filepath.Join(base, "YPtun", "portable")
	target := filepath.Join(root, version+"-"+buildID)

	if stamp, err := os.ReadFile(filepath.Join(target, readyMarker)); err == nil {
		if _, err := os.Stat(filepath.Join(target, appExe)); err == nil && string(stamp) == buildID {
			return target, nil // already unpacked — the common case
		}
	}

	self, err := os.Executable()
	if err != nil {
		return "", err
	}
	f, err := os.Open(self)
	if err != nil {
		return "", err
	}
	defer f.Close()

	size, offset, err := payloadRange(f)
	if err != nil {
		return "", err
	}

	// Unpack beside the final directory and rename, so an interrupted first run cannot leave a
	// half-written app image that later launches would happily start.
	staging := target + ".tmp"
	_ = os.RemoveAll(staging)
	if err := os.MkdirAll(staging, 0o755); err != nil {
		return "", err
	}

	// The payload is one zstd stream holding a tar of the app image (see cmd/pack). Decoding is
	// sequential but fast (the whole image in about a second); the disk writes are what take time,
	// so those fan out over every core.
	counter := &countingReader{r: io.NewSectionReader(f, offset, size)}
	dec, err := zstd.NewReader(counter, zstd.WithDecoderMaxWindow(1<<28))
	if err != nil {
		return "", err
	}
	defer dec.Close()
	tr := tar.NewReader(dec)

	const progressSteps = 1000
	progress := showProgress(progressSteps)
	type job struct {
		name string
		data []byte
	}
	jobs := make(chan job, 4)
	var firstErr error
	var errOnce sync.Once
	var failed atomic.Bool
	fail := func(err error) {
		errOnce.Do(func() { firstErr = err })
		failed.Store(true)
	}
	var wg sync.WaitGroup
	for w := 0; w < runtime.NumCPU(); w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range jobs {
				if err := writeEntry(staging, j.name, j.data); err != nil {
					fail(err)
				}
			}
		}()
	}
	for !failed.Load() {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			fail(err)
			break
		}
		if hdr.Typeflag == tar.TypeDir {
			if err := writeEntry(staging, hdr.Name, nil); err != nil {
				fail(err)
			}
			continue
		}
		data := make([]byte, hdr.Size)
		if _, err := io.ReadFull(tr, data); err != nil {
			fail(err)
			break
		}
		jobs <- job{hdr.Name, data}
		progress.set(int(int64(progressSteps) * counter.n.Load() / size))
	}
	close(jobs)
	wg.Wait()
	progress.close()
	if firstErr != nil {
		_ = os.RemoveAll(staging)
		return "", firstErr
	}

	if err := os.WriteFile(filepath.Join(staging, portableMarker), nil, 0o644); err != nil {
		return "", err
	}
	if err := os.WriteFile(filepath.Join(staging, readyMarker), []byte(buildID), 0o644); err != nil {
		return "", err
	}
	_ = os.RemoveAll(target)
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return "", err
	}
	if err := os.Rename(staging, target); err != nil {
		return "", err
	}
	// Previous builds are dead weight now — several unpacked app images are ~170 MB each.
	removeOtherBuilds(root, filepath.Base(target))
	return target, nil
}

// removeOtherBuilds drops every unpacked image except [keep]. Best-effort: one still in use by a
// running copy simply stays.
func removeOtherBuilds(root, keep string) {
	entries, err := os.ReadDir(root)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() && entry.Name() != keep {
			_ = os.RemoveAll(filepath.Join(root, entry.Name()))
		}
	}
}

// payloadRange reads the trailer and returns the appended zip's size and offset.
func payloadRange(f *os.File) (size int64, offset int64, err error) {
	info, err := f.Stat()
	if err != nil {
		return 0, 0, err
	}
	return findPayload(f, dataEnd(f, info.Size()))
}

// dataEnd is where this file's own bytes stop. Unsigned, that is the end of the file. Signing the
// .exe (Authenticode) appends a certificate table AFTER the trailer, so the trailer then sits right
// before the table — whose file offset the PE security directory records.
func dataEnd(f io.ReaderAt, fileSize int64) int64 {
	img, err := pe.NewFile(f)
	if err != nil {
		return fileSize
	}
	var dirs []pe.DataDirectory
	switch h := img.OptionalHeader.(type) {
	case *pe.OptionalHeader64:
		dirs = h.DataDirectory[:min(int(h.NumberOfRvaAndSizes), len(h.DataDirectory))]
	case *pe.OptionalHeader32:
		dirs = h.DataDirectory[:min(int(h.NumberOfRvaAndSizes), len(h.DataDirectory))]
	}
	if len(dirs) <= pe.IMAGE_DIRECTORY_ENTRY_SECURITY {
		return fileSize
	}
	// For the security directory VirtualAddress is a FILE offset, not an RVA.
	cert := dirs[pe.IMAGE_DIRECTORY_ENTRY_SECURITY]
	if cert.VirtualAddress == 0 || cert.Size == 0 || int64(cert.VirtualAddress) > fileSize {
		return fileSize
	}
	return int64(cert.VirtualAddress)
}

// findPayload locates the trailer ending at [end]. signtool pads the file to an 8-byte boundary with
// zeros before the certificate table, so up to 7 zero bytes may sit between trailer and [end].
func findPayload(r io.ReaderAt, end int64) (size int64, offset int64, err error) {
	trailer := make([]byte, trailerSize)
	for pad := int64(0); pad < 8; pad++ {
		at := end - pad - trailerSize
		if at < 0 {
			break
		}
		if _, err := r.ReadAt(trailer, at); err != nil {
			return 0, 0, err
		}
		if string(trailer[8:]) != trailerMagic {
			continue
		}
		size = int64(binary.LittleEndian.Uint64(trailer[:8]))
		offset = at - size
		if offset < 0 {
			return 0, 0, errString("the appended app image is truncated")
		}
		return size, offset, nil
	}
	return 0, 0, errString("this launcher carries no app image (rebuild it with build-portable.ps1)")
}

// writeEntry stores one tar entry under root; a nil data on a directory name just creates it.
func writeEntry(root, name string, data []byte) error {
	// Reject anything that would escape the target directory (zip-slip).
	clean := filepath.Clean(strings.ReplaceAll(name, "/", string(os.PathSeparator)))
	if strings.HasPrefix(clean, "..") || filepath.IsAbs(clean) {
		return errString("refusing to unpack " + name)
	}
	path := filepath.Join(root, clean)
	if strings.HasSuffix(name, "/") {
		return os.MkdirAll(path, 0o755)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}

// countingReader tracks how much of the compressed payload has been consumed, which is what the
// progress bar shows.
type countingReader struct {
	r io.Reader
	n atomic.Int64
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n.Add(int64(n))
	return n, err
}

// withoutJavaOptions drops the env vars every JVM silently prepends its options from. The app
// ships its own JRE 21, but a machine with an old Java 8 stack often still has something like
// JAVA_TOOL_OPTIONS=-XX:+UseConcMarkSweepGC set - removed in JDK 14, so the bundled JVM refuses to
// start and jpackage reports a bare "Failed to launch JVM".
func withoutJavaOptions(env []string) []string {
	out := make([]string, 0, len(env))
	for _, kv := range env {
		name, _, _ := strings.Cut(kv, "=")
		switch strings.ToUpper(name) {
		case "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS":
			continue
		}
		out = append(out, kv)
	}
	return out
}

// launchEnv is the environment the app starts with: no JVM option variables, plus the path of this
// launcher so the app can replace it on update.
func launchEnv(env []string) []string {
	env = withoutJavaOptions(env)
	if self, err := os.Executable(); err == nil {
		env = append(env, portableExeEnv+"="+self)
	}
	return env
}

// cleanPath removes PATH entries that hold another Java (java.exe / jvm.dll) and puts the app's own
// runtimein first. The bundled JRE never needs any of them, and an old Java 8 install on PATH is the
// classic companion of "Failed to launch JVM".
func cleanPath(env []string, runtimeBin string) []string {
	out := make([]string, 0, len(env))
	for _, kv := range env {
		name, value, _ := strings.Cut(kv, "=")
		if !strings.EqualFold(name, "PATH") {
			out = append(out, kv)
			continue
		}
		kept := []string{runtimeBin}
		for _, dir := range filepath.SplitList(value) {
			if dir == "" || holdsJava(dir) {
				continue
			}
			kept = append(kept, dir)
		}
		out = append(out, name+"="+strings.Join(kept, string(os.PathListSeparator)))
	}
	return out
}

func holdsJava(dir string) bool {
	for _, f := range []string{"java.exe", "javaw.exe", "jvm.dll"} {
		if _, err := os.Stat(filepath.Join(dir, f)); err == nil {
			return true
		}
	}
	return false
}

// launch starts the app and watches its first seconds (see watchStartup): the launcher leaves as
// soon as the app shows a window, so it does not linger as a parent for the whole session, but a
// failed start gets an explanation instead of a bare "Failed to launch JVM".
func launch(exe string) (failure string, err error) {
	dir := filepath.Dir(exe)
	// stdout/stderr into a file: if the JVM cannot start, its own message is the only real clue.
	var out *os.File
	if path := startupOutputPath(); path != "" {
		out, _ = os.Create(path)
	}
	attr := &os.ProcAttr{
		Dir:   dir,
		Env:   cleanPath(launchEnv(os.Environ()), filepath.Join(dir, "runtime", "bin")),
		Files: []*os.File{nil, out, out},
		Sys:   &syscall.SysProcAttr{HideWindow: true},
	}
	proc, err := os.StartProcess(exe, append([]string{exe}, os.Args[1:]...), attr)
	if err != nil {
		return "", err
	}
	pid := proc.Pid
	if err := proc.Release(); err != nil {
		return "", err
	}
	return watchStartup(pid, dir), nil
}

// ---------------------------------------------------------------------------------------------
// Win32 bits

var (
	kernel32          = syscall.NewLazyDLL("kernel32.dll")
	user32            = syscall.NewLazyDLL("user32.dll")
	comctl32          = syscall.NewLazyDLL("comctl32.dll")
	createMutexW      = kernel32.NewProc("CreateMutexW")
	messageBoxW       = user32.NewProc("MessageBoxW")
	createWindowExW   = user32.NewProc("CreateWindowExW")
	destroyWindow     = user32.NewProc("DestroyWindow")
	sendMessageW      = user32.NewProc("SendMessageW")
	getSystemMetrics  = user32.NewProc("GetSystemMetrics")
	peekMessageW      = user32.NewProc("PeekMessageW")
	translateMessage  = user32.NewProc("TranslateMessage")
	dispatchMessageW  = user32.NewProc("DispatchMessageW")
	initCommonControl = comctl32.NewProc("InitCommonControlsEx")
)

const (
	errAlreadyExists = 183

	wsPopup     = 0x80000000
	wsVisible   = 0x10000000
	wsBorder    = 0x00800000
	wsExTopmost = 0x00000008
	wsExToolWin = 0x00000080
	wsExLayered = 0x00080000

	pbmSetRange32 = 0x0406
	pbmSetPos     = 0x0402

	smCxScreen = 0
	smCyScreen = 1

	pmRemove = 0x0001

	mbIconError = 0x00000010
)

// claimSingleInstance returns false when another launcher is already running (so this one must not
// unpack on top of it).
//
// The "already exists" answer is taken from the error CreateMutexW itself returned. Asking
// GetLastError afterwards, through a second call, is unreliable: anything the Go runtime does in
// between (it is free to switch OS threads) can overwrite the thread's last error — which is how
// the first version managed to decide a first launch was a duplicate and exit without a word.
func claimSingleInstance() bool {
	name, err := syscall.UTF16PtrFromString("Local\\YPtunPortableLauncher")
	if err != nil {
		return true
	}
	handle, _, callErr := createMutexW.Call(0, 1, uintptr(unsafe.Pointer(name)))
	if handle == 0 {
		return true
	}
	if errno, ok := callErr.(syscall.Errno); ok && uintptr(errno) == errAlreadyExists {
		return false
	}
	return true
}

func fatal(message string) {
	title, _ := syscall.UTF16PtrFromString("YPtun")
	text, err := syscall.UTF16PtrFromString(message)
	if err != nil {
		return
	}
	messageBoxW.Call(0, uintptr(unsafe.Pointer(text)), uintptr(unsafe.Pointer(title)), mbIconError)
}

type errString string

func (e errString) Error() string { return string(e) }
