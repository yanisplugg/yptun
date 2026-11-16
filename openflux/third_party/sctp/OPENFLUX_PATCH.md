# OpenFlux patch to pion/sctp v1.11.3

Upstream: https://github.com/pion/sctp (MIT, see LICENSE). Tests and examples are left out of this copy.

One change, in `association.go`: an exported setter, `SetOutboundMTU`, overrides the MTU of outgoing packets of
every association created after it (1191 unless the setter says otherwise). `pion/webrtc` passes
`sctp.WithMTU(1191)` (its `sctpOutboundMTU` constant) to every association it builds and has no setting for it,
so the Config cannot be what decides; there was no way to make the packets of a data channel fuller. The
OpenFlux script host calls the setter before it opens a connection (see `transport/script/host_webrtc.go`).

To drop this copy: pion/webrtc would have to expose `sctp.WithMTU` through its `SettingEngine`.
