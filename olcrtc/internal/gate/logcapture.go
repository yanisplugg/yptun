package gate

import (
	"os"
	"strings"
	"sync"

	"github.com/openlibrecommunity/olcrtc/mobile"
)

// ai-generated: the whole file (the engine log captured per cell).

// CellLog is every engine log line written while a cell was current. It is
// safe for concurrent use: the engine writes while the runner reads.
type CellLog struct {
	mu    sync.Mutex
	lines []string
}

func (l *CellLog) add(line string) {
	l.mu.Lock()
	l.lines = append(l.lines, line)
	l.mu.Unlock()
}

// Count returns how many captured lines contain substr.
func (l *CellLog) Count(substr string) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := 0
	for _, line := range l.lines {
		if strings.Contains(line, substr) {
			n++
		}
	}
	return n
}

// Text returns the captured lines as they were written, newlines included.
func (l *CellLog) Text() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return strings.Join(l.lines, "")
}

// WriteScrubbed stores the lines at path with the run's secrets replaced
// (see Scrub); a cell's log leaves the run in no other form.
func (l *CellLog) WriteScrubbed(path string, secrets ...string) error {
	return writeScrubbed(path, l.Text(), secrets)
}

// Capture routes the process log to the current cell. The engine's logger
// writes through the standard log package, and mobile.SetLogWriter is the
// engine's own switch for that output, so one capture serves both flavours.
// A line goes to exactly one cell, the one current when it was written; the
// runner keeps cells in sequence so that is the cell it belongs to.
type Capture struct {
	mu      sync.Mutex
	current *CellLog
}

// StartCapture installs the capture; lines before the first Begin go to stderr.
func StartCapture() *Capture {
	c := &Capture{}
	mobile.SetLogWriter(c)
	return c
}

// WriteLog implements mobile.LogWriter.
func (c *Capture) WriteLog(msg string) {
	c.mu.Lock()
	cur := c.current
	c.mu.Unlock()
	if cur == nil {
		_, _ = os.Stderr.WriteString(msg)
		return
	}
	cur.add(msg)
}

// Begin makes a fresh log the current cell's and returns it.
func (c *Capture) Begin() *CellLog {
	l := &CellLog{}
	c.mu.Lock()
	c.current = l
	c.mu.Unlock()
	return l
}

// Stop sends the process log back to stderr.
func (c *Capture) Stop() {
	c.mu.Lock()
	c.current = nil
	c.mu.Unlock()
	mobile.SetLogWriter(nil)
}
