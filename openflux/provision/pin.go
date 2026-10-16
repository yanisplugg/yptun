package provision

// Pinned is the node-install.sh this app build runs: the file at a fixed
// commit of the app's own repository and its SHA-256. TestPinnedScriptHash
// keeps the hash in step with deploy/node-install.sh; when the script
// changes, commit it, then point PinnedCommit at that commit.
const (
	PinnedRepo   = "p1neappleXpress/OpenFlux"
	PinnedCommit = "fd5b7c3ad7f186826dadccba82bd7bdc2ca9cb38"
	PinnedSHA256 = "2bb7bbbd5249e1890c6fcbcc1d1d7def725f2a65c72dae138e43b2f53208cccb"
)

// Pinned returns the script location for this build.
func Pinned() Script {
	return Script{
		URL:    "https://raw.githubusercontent.com/" + PinnedRepo + "/" + PinnedCommit + "/deploy/node-install.sh",
		SHA256: PinnedSHA256,
	}
}
