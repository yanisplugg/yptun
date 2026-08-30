module github.com/olc/awgproxy

go 1.25.5

require (
	github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828
	golang.org/x/crypto v0.42.0
)

replace github.com/amnezia-vpn/amneziawg-go/v3 => ../amneziawg-go

// The gvisor snapshot go mod tidy resolves on its own has a packaging bug (a stray
// bridge_test.go in the stack package -> "found packages stack and bridge"), confirmed still
// present as of the amneziawg-go v3.1.20260814 update (2026-08-28). Pin a newer clean snapshot.
replace gvisor.dev/gvisor => gvisor.dev/gvisor v0.0.0-20260122175437-89a5d21be8f0

require (
	github.com/google/btree v1.1.3 // indirect
	golang.org/x/exp v0.0.0-20240506185415-9bf2ced13842 // indirect
	golang.org/x/net v0.44.0 // indirect
	golang.org/x/sys v0.36.0 // indirect
	golang.org/x/time v0.12.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	gvisor.dev/gvisor v0.0.0-20260122175437-89a5d21be8f0 // indirect
)
