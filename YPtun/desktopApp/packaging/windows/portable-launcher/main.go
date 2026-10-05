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
	"archive/zip"
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

	target, err := ensureUnpacked()
	if err != nil {
		fatal(err.Error())
		return
	}
	if err := launch(filepath.Join(target, appExe)); err != nil {
		fatal("Could not start " + appExe + ": " + err.Error())
	}
}

// ensureUnpacked returns the directory holding a ready-to-run app image, unpacking it first if
// this is the first launch of this version.
func ensureUnpacked() (string, error) {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		base = os.TempDir()
	}
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
	reader, err := zip.NewReader(io.NewSectionReader(f, offset, size), size)
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

	// One file after another left the first launch bound by a single inflate stream (~230 MB of
	// jars + JRE); the entries are independent, so unpack them on every core.
	progress := showProgress(len(reader.File))
	jobs := make(chan *zip.File)
	var done atomic.Int64
	var firstErr error
	var errOnce sync.Once
	var wg sync.WaitGroup
	for w := 0; w < runtime.NumCPU(); w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for entry := range jobs {
				if err := extract(entry, staging); err != nil {
					errOnce.Do(func() { firstErr = err })
					continue
				}
				progress.set(int(done.Add(1)))
			}
		}()
	}
	for _, entry := range reader.File {
		jobs <- entry
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

func extract(entry *zip.File, root string) error {
	// Reject anything that would escape the target directory (zip-slip).
	clean := filepath.Clean(strings.ReplaceAll(entry.Name, "/", string(os.PathSeparator)))
	if strings.HasPrefix(clean, "..") || filepath.IsAbs(clean) {
		return errString("refusing to unpack " + entry.Name)
	}
	path := filepath.Join(root, clean)
	if entry.FileInfo().IsDir() {
		return os.MkdirAll(path, 0o755)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	src, err := entry.Open()
	if err != nil {
		return err
	}
	defer src.Close()
	dst, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, entry.Mode()|0o200)
	if err != nil {
		return err
	}
	defer dst.Close()
	_, err = io.Copy(dst, src)
	return err
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

// launch starts the app detached and returns immediately: the launcher must not linger as a parent
// process (it would keep a console-less stub alive for the whole session and show up in the tree).
func launch(exe string) error {
	attr := &os.ProcAttr{
		Dir:   filepath.Dir(exe),
		Env:   launchEnv(os.Environ()),
		Files: []*os.File{nil, nil, nil},
		Sys:   &syscall.SysProcAttr{HideWindow: true},
	}
	proc, err := os.StartProcess(exe, append([]string{exe}, os.Args[1:]...), attr)
	if err != nil {
		return err
	}
	return proc.Release()
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
