package main

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

// Start-up watchdog and diagnostics (issue #54: "Failed to launch JVM" with no clue why).
//
// jpackage's launcher reports a failed JVM start with a bare message box. Here the launcher keeps an
// eye on the first seconds of the app: if the process dies, or an error dialog of its own appears,
// it works out what it can (does jvm.dll load at all, and with which Windows error?), shows the
// answer with a link to the right download, and saves the same text next to the app data so it can
// be attached to a bug report. A healthy start costs nothing: the launcher leaves as soon as the
// app shows a normal window.

var (
	enumWindows            = user32.NewProc("EnumWindows")
	getClassNameW          = user32.NewProc("GetClassNameW")
	getWindowThreadProcess = user32.NewProc("GetWindowThreadProcessId")
	isWindowVisible        = user32.NewProc("IsWindowVisible")
	openProcess            = kernel32.NewProc("OpenProcess")
	getExitCodeProcess     = kernel32.NewProc("GetExitCodeProcess")
	closeHandle            = kernel32.NewProc("CloseHandle")
	terminateProcess       = kernel32.NewProc("TerminateProcess")
	createToolhelpSnapshot = kernel32.NewProc("CreateToolhelp32Snapshot")
	process32First         = kernel32.NewProc("Process32FirstW")
	process32Next          = kernel32.NewProc("Process32NextW")
	loadLibraryExW         = kernel32.NewProc("LoadLibraryExW")
	freeLibrary            = kernel32.NewProc("FreeLibrary")
	globalMemoryStatusEx   = kernel32.NewProc("GlobalMemoryStatusEx")
	rtlGetVersion          = syscall.NewLazyDLL("ntdll.dll").NewProc("RtlGetVersion")
)

const (
	stillActive              = 259
	processQueryLimitedInfo  = 0x1000
	loadWithAlteredSearchDir = 0x8
	dialogClass              = "#32770" // the window class of every standard Windows message box
	watchTimeout             = 25 * time.Second
)

// windowState reports, for the windows of one process, whether any visible error dialog and any
// other visible window exist. EnumWindows calls back on this thread, so plain package state is fine.
var (
	enumPIDs     map[uint32]bool
	enumDialog   bool
	enumOther    bool
	enumCallback = syscall.NewCallback(func(hwnd, _ uintptr) uintptr {
		var pid uint32
		getWindowThreadProcess.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
		if !enumPIDs[pid] {
			return 1
		}
		if visible, _, _ := isWindowVisible.Call(hwnd); visible == 0 {
			return 1
		}
		var class [64]uint16
		n, _, _ := getClassNameW.Call(hwnd, uintptr(unsafe.Pointer(&class[0])), uintptr(len(class)))
		if syscall.UTF16ToString(class[:n]) == dialogClass {
			enumDialog = true
		} else {
			enumOther = true
		}
		return 1
	})
)

// windowsOf looks at the visible windows of the app AND its children: jpackage's YPtun.exe starts
// the JVM as a child process, and the app window belongs to that child.
func windowsOf(root int) (dialog, other bool) {
	enumPIDs, enumDialog, enumOther = family(uint32(root)), false, false
	enumWindows.Call(enumCallback, 0)
	return enumDialog, enumOther
}

type processEntry struct {
	size, usage, pid uint32
	heapID           uintptr
	module, threads  uint32
	parent           uint32
	priClass         int32
	flags            uint32
	exe              [260]uint16
}

// family is root plus all of its descendants, from a process snapshot.
func family(root uint32) map[uint32]bool {
	set := map[uint32]bool{root: true}
	snap, _, _ := createToolhelpSnapshot.Call(2 /* TH32CS_SNAPPROCESS */, 0)
	if snap == ^uintptr(0) {
		return set
	}
	defer closeHandle.Call(snap)
	parents := map[uint32]uint32{}
	e := processEntry{}
	e.size = uint32(unsafe.Sizeof(e))
	ok, _, _ := process32First.Call(snap, uintptr(unsafe.Pointer(&e)))
	for ok != 0 {
		parents[e.pid] = e.parent
		ok, _, _ = process32Next.Call(snap, uintptr(unsafe.Pointer(&e)))
	}
	for changed := true; changed; { // children can appear in any order; repeat until stable
		changed = false
		for pid, parent := range parents {
			if set[parent] && !set[pid] {
				set[pid], changed = true, true
			}
		}
	}
	return set
}

// exitCodeOf returns (code, true) once the process has exited.
func exitCodeOf(handle uintptr) (uint32, bool) {
	var code uint32
	ok, _, _ := getExitCodeProcess.Call(handle, uintptr(unsafe.Pointer(&code)))
	if ok == 0 || code == stillActive {
		return 0, false
	}
	return code, true
}

// watchStartup returns "" as soon as the app has plainly started (a normal window) or after a
// timeout, and otherwise why it failed. An error box of the app (jpackage's "Failed to launch JVM")
// is closed by ending the app: the caller shows one explanation, or tries another folder.
func watchStartup(pid int, appDir string) string {
	handle, _, _ := openProcess.Call(processQueryLimitedInfo, 0, uintptr(pid))
	if handle == 0 {
		return ""
	}
	defer closeHandle.Call(handle)

	deadline := time.Now().Add(watchTimeout)
	for {
		if code, exited := exitCodeOf(handle); exited {
			if code != 0 {
				return fmt.Sprintf("the app exited with code %d (0x%X)", code, code)
			}
			return ""
		}
		dialog, other := windowsOf(pid)
		if dialog {
			killFamily(uint32(pid))
			time.Sleep(300 * time.Millisecond) // let the output file close
			return "the app showed an error dialog (Failed to launch JVM)"
		} else if other {
			return "" // a normal window: started fine
		}
		if time.Now().After(deadline) {
			return ""
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// killFamily ends the process and everything it started.
func killFamily(root uint32) {
	for pid := range family(root) {
		if h, _, _ := openProcess.Call(0x1 /* PROCESS_TERMINATE */, 0, uintptr(pid)); h != 0 {
			terminateProcess.Call(h, 1)
			closeHandle.Call(h)
		}
	}
}

// loadError tries to load jvm.dll the way the app's own launcher does and returns the Windows error.
func loadError(jvm string) (errno uintptr, ok bool) {
	p, err := syscall.UTF16PtrFromString(jvm)
	if err != nil {
		return 0, false
	}
	h, _, callErr := loadLibraryExW.Call(uintptr(unsafe.Pointer(p)), 0, loadWithAlteredSearchDir)
	if h != 0 {
		freeLibrary.Call(h)
		return 0, true
	}
	if e, isErrno := callErr.(syscall.Errno); isErrno {
		return uintptr(e), false
	}
	return 0, false
}

func isRussian() bool {
	lang, _, _ := getUserDefaultUILang.Call()
	p := lang & 0x3ff
	return p == 0x19 || p == 0x22 || p == 0x23
}

func osBuild() string {
	var v struct {
		size, major, minor, build, platform uint32
		csd                                 [128]uint16
	}
	v.size = uint32(unsafe.Sizeof(v))
	rtlGetVersion.Call(uintptr(unsafe.Pointer(&v)))
	return fmt.Sprintf("Windows %d.%d build %d", v.major, v.minor, v.build)
}

func freeRAM() string {
	var m struct {
		length, load                                                     uint32
		totalPhys, availPhys, totalPage, availPage, totalVirt, availVirt uint64
		availExt                                                         uint64
	}
	m.length = uint32(unsafe.Sizeof(m))
	if ok, _, _ := globalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&m))); ok == 0 {
		return "unknown"
	}
	return fmt.Sprintf("%d MB free of %d MB", m.availPhys>>20, m.totalPhys>>20)
}

func reportStartFailure(appDir, reason, history string) {
	jvm := filepath.Join(appDir, "runtime", "bin", "server", "jvm.dll")
	var b strings.Builder
	fmt.Fprintf(&b, "YPtun %s (%s)\n", version, buildID)
	fmt.Fprintf(&b, "Problem: %s\n", reason)
	fmt.Fprintf(&b, "System: %s, %s, RAM %s\n", osBuild(), runtime.GOARCH, freeRAM())
	fmt.Fprintf(&b, "App dir: %s\n", appDir)
	if _, err := os.Stat(jvm); err != nil {
		fmt.Fprintf(&b, "jvm.dll: MISSING (%v)\n", err)
	} else if errno, ok := loadError(jvm); ok {
		b.WriteString("jvm.dll: loads fine\n")
	} else {
		fmt.Fprintf(&b, "jvm.dll: failed to load, Windows error %d (%s)\n", errno, describeLoadError(errno))
	}
	if !isASCII(appDir) {
		b.WriteString("App dir contains non-ASCII characters\n")
	}
	if history != "" {
		b.WriteString("Attempts:\n" + history)
	}
	for _, name := range []string{"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"} {
		if v := os.Getenv(name); v != "" {
			fmt.Fprintf(&b, "%s was set to %q (the launcher removes it for the app)\n", name, v)
		}
	}
	details := b.String()

	logPath := ""
	if base := os.Getenv("LOCALAPPDATA"); base != "" {
		dir := filepath.Join(base, "YPtun")
		if os.MkdirAll(dir, 0o755) == nil {
			logPath = filepath.Join(dir, "launch-diagnostic.txt")
			_ = os.WriteFile(logPath, []byte(details), 0o644)
		}
	}

	redist := "https://aka.ms/vs/17/release/vc_redist.x64.exe"
	if runtime.GOARCH == "arm64" {
		redist = "https://aka.ms/vs/17/release/vc_redist.arm64.exe"
	}
	var text string
	if isRussian() {
		text = "YPtun не удалось запустить.\n\n" + details + "\nЧто попробовать:\n" +
			"- установить Microsoft Visual C++ Redistributable: " + redist + "\n" +
			"- временно отключить антивирус или добавить в исключения папку " + appDir + "\n\n"
		if logPath != "" {
			text += "Этот текст сохранён в " + logPath + "\n"
		}
		text += "Приложите его к issue: https://github.com/yanisplugg/yptun/issues/54"
	} else {
		text = "YPtun could not start.\n\n" + details + "\nWhat to try:\n" +
			"- install the Microsoft Visual C++ Redistributable: " + redist + "\n" +
			"- temporarily disable the antivirus, or exclude the folder " + appDir + "\n\n"
		if logPath != "" {
			text += "This text was saved to " + logPath + "\n"
		}
		text += "Please attach it to the issue: https://github.com/yanisplugg/yptun/issues/54"
	}
	fatal(text)
}

func describeLoadError(errno uintptr) string {
	switch errno {
	case 126:
		return "a DLL that jvm.dll depends on is missing - usually the Visual C++ Redistributable"
	case 127:
		return "a DLL is too old - update the Visual C++ Redistributable"
	case 193:
		return "wrong architecture - an x64 build on an ARM or 32-bit system, or a damaged file"
	case 1114:
		return "a DLL failed to initialise - antivirus or a conflicting DLL"
	case 5:
		return "access denied - antivirus or folder permissions"
	case 1455:
		return "the paging file is too small"
	default:
		return "see https://learn.microsoft.com/windows/win32/debug/system-error-codes"
	}
}

func isASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] >= 0x80 {
			return false
		}
	}
	return true
}

// startupOutputPath is where the app's stdout/stderr go (see launch): a JVM that cannot start prints
// the real reason there, while jpackage's own launcher only shows "Failed to launch JVM".
func startupOutputPath() string {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		return ""
	}
	dir := filepath.Join(base, "YPtun")
	if os.MkdirAll(dir, 0o755) != nil {
		return ""
	}
	return filepath.Join(dir, "launch-output.txt")
}

// startupOutput returns the last ~1.5 KB of what the app printed, or "".
func startupOutput() string {
	path := startupOutputPath()
	if path == "" {
		return ""
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	if len(b) > 1500 {
		b = b[len(b)-1500:]
	}
	return strings.TrimSpace(string(b))
}
