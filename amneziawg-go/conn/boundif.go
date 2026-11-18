/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2017-2025 WireGuard LLC. All Rights Reserved.
 */

package conn

import "errors"

func (s *StdNetBind) PeekLookAtSocketFd4() (fd int, err error) {
	if s == nil || s.ipv4 == nil {
		return -1, errors.New("ipv4 socket is not open")
	}
	sysconn, err := s.ipv4.SyscallConn()
	if err != nil {
		return -1, err
	}
	err = sysconn.Control(func(f uintptr) {
		fd = int(f)
	})
	if err != nil {
		return -1, err
	}
	return
}

func (s *StdNetBind) PeekLookAtSocketFd6() (fd int, err error) {
	if s == nil || s.ipv6 == nil {
		return -1, errors.New("ipv6 socket is not open")
	}
	sysconn, err := s.ipv6.SyscallConn()
	if err != nil {
		return -1, err
	}
	err = sysconn.Control(func(f uintptr) {
		fd = int(f)
	})
	if err != nil {
		return -1, err
	}
	return
}
