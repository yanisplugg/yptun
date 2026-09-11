package wdtt

import (
	"log"
	"time"
)

// rawDiagf логирует с миллисекундным timestamp в том же формате
// (HH:mm:ss.SSS), что и TunnelManager.addRawDiagLog на Android-стороне —
// нужно для сопоставления моментов между процессами (Kotlin/Go) при разборе
// таймлайна подъёма Raw TUN на устройствах, где он подвисает.
func rawDiagf(format string, args ...any) {
	ts := time.Now().Format("15:04:05.000")
	log.Printf("[RAW-DIAG %s] "+format, append([]any{ts}, args...)...)
}
