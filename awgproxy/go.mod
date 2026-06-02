module github.com/olc/awgproxy

go 1.25.5

require github.com/amnezia-vpn/amneziawg-go v1.0.4

// amneziawg-go v1.0.4 pins a gvisor snapshot with a packaging bug (a stray bridge_test.go in the
// stack package → "found packages stack and bridge"). Pin a newer clean upstream snapshot and use
// a local amneziawg-go fork that adapts the one drifted API (pkt.IsNil → pkt == nil).
replace gvisor.dev/gvisor => gvisor.dev/gvisor v0.0.0-20260122175437-89a5d21be8f0

replace github.com/amnezia-vpn/amneziawg-go => ../amneziawg-go

require (
	github.com/google/btree v1.1.3 // indirect
	github.com/tevino/abool v1.2.0 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	golang.org/x/crypto v0.42.0 // indirect
	golang.org/x/exp v0.0.0-20231110203233-9a3e6036ecaa // indirect
	golang.org/x/net v0.44.0 // indirect
	golang.org/x/sys v0.36.0 // indirect
	golang.org/x/time v0.12.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	gvisor.dev/gvisor v0.0.0-20250606233247-e3c4c4cad86f // indirect
)
