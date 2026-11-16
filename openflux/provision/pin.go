package provision

import "fmt"

// The node-install.sh scripts this app build runs, each a file at a fixed
// commit and its SHA-256. The script decides which repository's node-v*
// releases the server's core and its updater come from, so choosing a
// script chooses the node's core.
//
// Pinned* is this repository's own deploy/node-install.sh
// (meepo161/OpenFlux releases); TestPinnedScriptHash keeps its hash in step
// with the file: when the script changes, commit it, then point
// PinnedCommit at that commit. Official* is the same script following
// p1neappleXpress/OpenFlux releases.
const (
	PinnedRepo   = "meepo161/OpenFlux"
	PinnedCommit = "e9c29ad968606696802ee2921958a6be294c515d"
	PinnedSHA256 = "f13d05c9876e823a187ce7976d78278f117b725f259dd4f3e250389b538ad0e6"

	OfficialRepo   = "p1neappleXpress/OpenFlux"
	OfficialCommit = "630a0e552e49d6ff7fa193ae9d2e997c67917448"
	OfficialSHA256 = "133b1c091c28f653b09bf4aad95b0a4b4d13032c42a1481f575c5573d83323d4"
)

// Script sources the node wizard offers.
const (
	SourceFork     = "fork"
	SourceOfficial = "official"
)

// Pinned returns this build's own script (SourceFork).
func Pinned() Script {
	return Script{
		URL:    "https://raw.githubusercontent.com/" + PinnedRepo + "/" + PinnedCommit + "/deploy/node-install.sh",
		SHA256: PinnedSHA256,
	}
}

// PinnedFor returns the script for a source: SourceFork (also "") or
// SourceOfficial.
func PinnedFor(source string) (Script, error) {
	switch source {
	case "", SourceFork:
		return Pinned(), nil
	case SourceOfficial:
		return Script{
			URL:    "https://raw.githubusercontent.com/" + OfficialRepo + "/" + OfficialCommit + "/deploy/node-install.sh",
			SHA256: OfficialSHA256,
		}, nil
	}
	return Script{}, fmt.Errorf("неизвестный источник ядра %q", source)
}
