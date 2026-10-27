# Governance

olcRTC is maintained as part of the Ghostlane project. The authoritative
governance document, including roles, how decisions are made and how maintainers
are added, lives in the application repository:

<https://github.com/ghostlane-project/ghostlane/blob/main/GOVERNANCE.md>

What is specific to this repository:

- **Scope.** This is the transport engine: a tunnel that travels inside a WebRTC
  media session, as a Go library and a command-line program. It is useful on its
  own and it is what Ghostlane links on every platform.
- **Branches.** `proofkit` is the maintained branch and the default. `master` is
  an untouched copy of the archived upstream, kept for comparison; nothing is
  merged into it.
- **Releases.** The engine has no version numbers of its own. Consumers pin a
  commit; Ghostlane pins one explicitly and moves that pin deliberately, so a
  change here reaches users when the application is re-pinned and released.
- **The gate.** `internal/gate` runs real transfers through real relays and is
  the thing that decides whether a change is good. Its report is attached to
  every application release. Outside contributors are not expected to run it.

Funding, including the statement that maintainers and contributors may be paid
from project funds, is described once for both repositories:
<https://github.com/ghostlane-project/ghostlane/blob/main/FUNDING.md>
