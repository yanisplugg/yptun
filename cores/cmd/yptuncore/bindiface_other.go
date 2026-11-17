//go:build !windows && !linux

package main

import "syscall"

func bindSocketToInterface(_ syscall.RawConn, _ uint32) error {
	return nil
}

func setPlatformDllDirectory(_ string) {}
