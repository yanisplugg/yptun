package utils

import (
	"fmt"
	"io"
	"log"
	"os"
)

var (
	debugLog *log.Logger
	verbose  bool
	output   io.Writer = os.Stderr

	logCallback func(string)
)

func SetLogCallback(cb func(string)) {
	logCallback = cb
}

func SetVerbose(v bool) {
	SetDebug(v)
}

// SetOutput redirects all debug and standard log output to w.
// Used by the mobile bridge to pipe logs into the app UI.
func SetOutput(w io.Writer) {
	output = w
	log.SetOutput(w)
	if debugLog != nil {
		debugLog.SetOutput(w)
	}
}

func EnableDebug() {
	verbose = true
	debugLog = log.New(output, "", log.LstdFlags|log.Lmicroseconds)
	log.SetOutput(output)
	log.SetFlags(log.LstdFlags | log.Lmicroseconds | log.Lshortfile)
}

func Debugf(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	if cb := logCallback; cb != nil {
		cb(msg)
	}
	if verbose && debugLog != nil {
		debugLog.Output(2, msg)
	}
}

// SetDebug toggles verbose logging at runtime (off = Debugf becomes a no-op).
func SetDebug(on bool) {
	if on {
		EnableDebug()
		return
	}
	verbose = false
}

func IsVerbose() bool {
	return verbose
}

// SafeGo runs fn in a new goroutine, recovering from any panic so a crash in
// one worker cannot take down the whole process (critical when this code runs
// embedded as a library inside a mobile app).
func SafeGo(name string, fn func()) {
	go func() {
		defer func() {
			if r := recover(); r != nil {
				Debugf("[PANIC] recovered in %s: %v", name, r)
			}
		}()
		fn()
	}()
}
