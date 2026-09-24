package utils

import (
	"fmt"
	"log"
	"os"
	"sync"
)

var (
	debugLog    *log.Logger
	verbose     bool
	logMu       sync.RWMutex
	logCallback func(string)
)

func SetLogCallback(cb func(string)) {
	logMu.Lock()
	defer logMu.Unlock()
	logCallback = cb
}

func SetVerbose(v bool) {
	verbose = v
	if v && debugLog == nil {
		debugLog = log.New(os.Stderr, "", log.LstdFlags|log.Lmicroseconds)
	}
}

func EnableDebug() {
	SetVerbose(true)
	log.SetFlags(log.LstdFlags | log.Lmicroseconds | log.Lshortfile)
}

func Debugf(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	logMu.RLock()
	cb := logCallback
	logMu.RUnlock()
	if cb != nil {
		cb(msg)
	}
	if verbose && debugLog != nil {
		debugLog.Output(2, msg)
	}
}

func IsVerbose() bool {
	return verbose
}

// SetDebug toggles verbose logging at runtime (off = Debugf becomes a no-op).
func SetDebug(on bool) {
	if on {
		EnableDebug()
		return
	}
	verbose = false
}

// SafeGo runs fn in a new goroutine, recovering from any panic so a crash in
// one worker cannot take down the whole process.
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
