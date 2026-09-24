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
