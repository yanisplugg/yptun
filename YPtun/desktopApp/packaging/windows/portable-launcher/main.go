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
//	[ launcher .exe ][ app-image zip ][ uint64 zip size ][ "YPTUNPKG" ]
package main

import (
	"archive/zip"
	"encoding/binary"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
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

	progress := showProgress(len(reader.File))
	for i, entry := range reader.File {
		if err := extract(entry, staging); err != nil {
			_ = os.RemoveAll(staging)
			progress.close()
			return "", err
		}
		progress.set(i + 1)
	}
	progress.close()

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
	trailer := make([]byte, trailerSize)
	if _, err := f.ReadAt(trailer, info.Size()-trailerSize); err != nil {
		return 0, 0, err
	}
	if string(trailer[8:]) != trailerMagic {
		return 0, 0, errString("this launcher carries no app image (rebuild it with build-portable.ps1)")
	}
	size = int64(binary.LittleEndian.Uint64(trailer[:8]))
	offset = info.Size() - trailerSize - size
	if offset < 0 {
		return 0, 0, errString("the appended app image is truncated")
	}
	return size, offset, nil
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

// launch starts the app detached and returns immediately: the launcher must not linger as a parent
// process (it would keep a console-less stub alive for the whole session and show up in the tree).
func launch(exe string) error {
	attr := &os.ProcAttr{
		Dir:   filepath.Dir(exe),
		Env:   os.Environ(),
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

// progressBar is driven by a channel, never by cross-thread window calls.
//
// A window belongs to the thread that created it, and SendMessage from any OTHER thread blocks
// until that thread pumps its message queue. Go moves goroutines between OS threads freely, so the
// first version — create the window here, SendMessage from the extraction loop — deadlocked on the
// very first file and the portable just hung with no window at all. Everything Win32 now happens on
// one locked OS thread that runs a real message pump; the extraction loop only sends numbers.
type progressBar struct {
	updates chan int
	done    chan struct{}
}

func showProgress(total int) *progressBar {
	p := &progressBar{}
	if total <= 0 {
		return p
	}
	p.updates = make(chan int, 64)
	p.done = make(chan struct{})
	ready := make(chan struct{})
	go p.run(total, ready)
	<-ready
	return p
}

func (p *progressBar) run(total int, ready chan struct{}) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	defer close(p.done)

	hwnd := createProgressWindow()
	close(ready)
	if hwnd != 0 {
		sendMessageW.Call(hwnd, pbmSetRange32, 0, uintptr(total))
	}
	defer func() {
		if hwnd != 0 {
			destroyWindow.Call(hwnd)
		}
	}()

	var msg [48]byte // MSG is 48 bytes on amd64/arm64; we never read its fields
	for {
		select {
		case done, ok := <-p.updates:
			if !ok {
				return
			}
			if hwnd != 0 {
				sendMessageW.Call(hwnd, pbmSetPos, uintptr(done), 0)
			}
		default:
		}
		// Keep the bar painting without ever blocking on the queue.
		for {
			got, _, _ := peekMessageW.Call(uintptr(unsafe.Pointer(&msg[0])), 0, 0, 0, pmRemove)
			if got == 0 {
				break
			}
			translateMessage.Call(uintptr(unsafe.Pointer(&msg[0])))
			dispatchMessageW.Call(uintptr(unsafe.Pointer(&msg[0])))
		}
		select {
		case done, ok := <-p.updates:
			if !ok {
				return
			}
			if hwnd != 0 {
				sendMessageW.Call(hwnd, pbmSetPos, uintptr(done), 0)
			}
		case <-time.After(30 * time.Millisecond):
		}
	}
}

// createProgressWindow uses a predefined control class as a top-level window, so there is no window
// class to register and no WndProc callback. Returns 0 if anything fails — unpacking then simply
// proceeds without a bar.
func createProgressWindow() uintptr {
	var icc struct {
		size, flags uint32
	}
	icc.size = uint32(unsafe.Sizeof(icc))
	icc.flags = 0x20 // ICC_PROGRESS_CLASS
	initCommonControl.Call(uintptr(unsafe.Pointer(&icc)))

	class, err := syscall.UTF16PtrFromString("msctls_progress32")
	if err != nil {
		return 0
	}
	empty, _ := syscall.UTF16PtrFromString("")
	const w, h = 360, 24
	screenW, _, _ := getSystemMetrics.Call(smCxScreen)
	screenH, _, _ := getSystemMetrics.Call(smCyScreen)
	hwnd, _, _ := createWindowExW.Call(
		wsExTopmost|wsExToolWin,
		uintptr(unsafe.Pointer(class)),
		uintptr(unsafe.Pointer(empty)),
		wsPopup|wsVisible|wsBorder,
		(screenW-w)/2, (screenH-h)/2, w, h,
		0, 0, 0, 0,
	)
	return hwnd
}

func (p *progressBar) set(done int) {
	if p.updates == nil {
		return
	}
	select {
	case p.updates <- done:
	default: // the bar is behind; dropping a tick is better than slowing the unpack
	}
}

func (p *progressBar) close() {
	if p.updates == nil {
		return
	}
	close(p.updates)
	<-p.done
	p.updates = nil
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
