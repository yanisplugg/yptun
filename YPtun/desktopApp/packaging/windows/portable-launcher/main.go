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
	"strings"
	"syscall"
	"unsafe"
)

// Set at build time: -ldflags "-X main.version=3.2.1".
var version = "dev"

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
	target := filepath.Join(base, "YPtun", "portable", version)

	if _, err := os.Stat(filepath.Join(target, readyMarker)); err == nil {
		if _, err := os.Stat(filepath.Join(target, appExe)); err == nil {
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
	if err := os.WriteFile(filepath.Join(staging, readyMarker), []byte(version), 0o644); err != nil {
		return "", err
	}
	_ = os.RemoveAll(target)
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return "", err
	}
	if err := os.Rename(staging, target); err != nil {
		return "", err
	}
	return target, nil
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
	getLastError      = kernel32.NewProc("GetLastError")
	messageBoxW       = user32.NewProc("MessageBoxW")
	createWindowExW   = user32.NewProc("CreateWindowExW")
	destroyWindow     = user32.NewProc("DestroyWindow")
	sendMessageW      = user32.NewProc("SendMessageW")
	updateWindow      = user32.NewProc("UpdateWindow")
	getSystemMetrics  = user32.NewProc("GetSystemMetrics")
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

	mbIconError = 0x00000010
)

// claimSingleInstance returns false when another launcher is already running (so this one must not
// unpack on top of it).
func claimSingleInstance() bool {
	name, err := syscall.UTF16PtrFromString("Local\\YPtunPortableLauncher")
	if err != nil {
		return true
	}
	handle, _, _ := createMutexW.Call(0, 1, uintptr(unsafe.Pointer(name)))
	if handle == 0 {
		return true
	}
	last, _, _ := getLastError.Call()
	return last != errAlreadyExists
}

type progressBar struct{ hwnd uintptr }

// showProgress puts a bare progress bar on screen for the first-run unpack. It is a predefined
// control class used as a top-level window, so there is no window class to register and no message
// loop to run — and if any of it fails, unpacking simply proceeds without it.
func showProgress(total int) *progressBar {
	if total <= 0 {
		return &progressBar{}
	}
	var icc struct {
		size, flags uint32
	}
	icc.size = uint32(unsafe.Sizeof(icc))
	icc.flags = 0x20 // ICC_PROGRESS_CLASS
	initCommonControl.Call(uintptr(unsafe.Pointer(&icc)))

	class, err := syscall.UTF16PtrFromString("msctls_progress32")
	if err != nil {
		return &progressBar{}
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
	if hwnd == 0 {
		return &progressBar{}
	}
	sendMessageW.Call(hwnd, pbmSetRange32, 0, uintptr(total))
	updateWindow.Call(hwnd)
	return &progressBar{hwnd: hwnd}
}

func (p *progressBar) set(done int) {
	if p.hwnd == 0 {
		return
	}
	sendMessageW.Call(p.hwnd, pbmSetPos, uintptr(done), 0)
	updateWindow.Call(p.hwnd)
}

func (p *progressBar) close() {
	if p.hwnd == 0 {
		return
	}
	destroyWindow.Call(p.hwnd)
	p.hwnd = 0
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
