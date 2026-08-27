//go:build !windows

package main

import (
	"bufio"
	"io"
	"os"
	"sync"

	"golang.org/x/sys/unix"
)

var (
	stderrFilterOnce   sync.Once     //nolint:gochecknoglobals // process-wide stderr fd filter
	stderrPipeWriter   *os.File      //nolint:gochecknoglobals // process-wide stderr fd filter
	stderrFilterDone   chan struct{} //nolint:gochecknoglobals // process-wide stderr fd filter
	stderrFilterActive bool          //nolint:gochecknoglobals // process-wide stderr fd filter
	stderrOrigFD       int           //nolint:gochecknoglobals // process-wide stderr fd filter
)

// installStderrFilter redirects fd 2 into a pipe drained by a goroutine that
// drops third-party noise. os.Stderr is deliberately left alone: reassigning
// it drops the last reference to the original *os.File, whose finalizer then
// closes fd 2 out from under the process.
func installStderrFilter() {
	stderrFilterOnce.Do(func() {
		origFD, err := unix.Dup(int(os.Stderr.Fd()))
		if err != nil {
			return
		}
		reader, writer, err := os.Pipe()
		if err != nil {
			_ = unix.Close(origFD)
			return
		}
		if err := unix.Dup2(int(writer.Fd()), int(os.Stderr.Fd())); err != nil {
			_ = reader.Close()
			_ = writer.Close()
			_ = unix.Close(origFD)
			return
		}
		stderrPipeWriter = writer
		stderrFilterDone = make(chan struct{})
		stderrFilterActive = true
		stderrOrigFD = origFD
		orig := os.NewFile(uintptr(origFD), "/dev/stderr-original")
		go func() {
			defer close(stderrFilterDone)
			copyFilteredStderr(reader, orig)
		}()
	})
}

// flushStderrFilter puts the real stderr back on fd 2, then closes the pipe
// write end so the filter goroutine sees EOF and drains what is left. The
// restore has to come first: it drops the last extra reference to the pipe
// (so the reader really gets EOF) and it keeps fd 2 usable afterwards, which
// closing it outright would not.
func flushStderrFilter() {
	if !stderrFilterActive {
		return
	}
	_ = unix.Dup2(stderrOrigFD, unix.Stderr)
	_ = stderrPipeWriter.Close()
	<-stderrFilterDone
}

func copyFilteredStderr(reader *os.File, out io.Writer) {
	defer func() { _ = reader.Close() }()
	br := bufio.NewReader(reader)
	for {
		line, err := br.ReadBytes('\n')
		if len(line) > 0 && !isNoisyLogLine(line) {
			if _, writeErr := out.Write(line); writeErr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}
