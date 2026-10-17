package provision

import "fmt"

// The node-install.sh scripts this app build runs, each a file at a fixed
// commit and its SHA-256. The script decides which repository's node-v*
// releases the server's core and its updater come from, so choosing a
// script chooses the node's core.
//
// Pinned* is this repository's own deploy/node-install.sh; TestPinnedScriptHash
// keeps its hash in step with the file: when the script changes, commit it,
// then point PinnedCommit at that commit. Official* is kept equal for now;
// a fork points Pinned* at its own releases and keeps Official* here.
const (
	PinnedRepo   = "p1neappleXpress/OpenFlux"
	PinnedCommit = "07dc53b56cb2fa1a834b750009a51a347bb1a180"
	PinnedSHA256 = "eecd9b7d6c5893d80298f2a19e3d9ac66ad838f55ca85f296f68e00c7d6c793b"

	OfficialRepo   = "p1neappleXpress/OpenFlux"
	OfficialCommit = "07dc53b56cb2fa1a834b750009a51a347bb1a180"
	OfficialSHA256 = "eecd9b7d6c5893d80298f2a19e3d9ac66ad838f55ca85f296f68e00c7d6c793b"
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
