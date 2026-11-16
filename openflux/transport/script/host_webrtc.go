package script

import (
	"os"
	"strconv"
	"time"

	"github.com/dop251/goja"
	"github.com/pion/sctp"
	"github.com/pion/transport/v5/stdnet"
	"github.com/pion/webrtc/v4"

	"github.com/p1neappleXpress/OpenFlux/utils"
)

// registerWebRTC exposes a minimal PeerConnection/DataChannel surface backed
// by pion/webrtc (already a dependency via transport/oneme's native
// implementation - no new module). This is the same class of exception as
// crypto.solvePow: ICE/DTLS/SCTP genuinely cannot be implemented in JS, so
// that layer stays native. Everything ABOVE it - signaling, when to
// createOffer/createAnswer, how candidates get to the other side, retry
// policy - lives entirely in the script, exactly like every other
// transport. No restriction beyond what pion itself exposes: a script can
// dial any ICE server it likes.
func registerWebRTC(vm *goja.Runtime, t *ScriptTransport) {
	webrtcObj := vm.NewObject()
	webrtcObj.Set("newPeerConnection", func(call goja.FunctionCall) goja.Value {
		return newPeerConnectionJS(vm, t, call)
	})
	vm.Set("webrtc", webrtcObj)
}

func newPeerConnectionJS(vm *goja.Runtime, t *ScriptTransport, call goja.FunctionCall) goja.Value {
	config := webrtc.Configuration{}
	var settings webrtc.SettingEngine
	var pace *paceConfig
	if opts, ok := call.Argument(0).(*goja.Object); ok {
		pace = tuneConnection(&settings, t, opts)
		if v := opts.Get("iceServers"); v != nil && !goja.IsUndefined(v) {
			if arr, ok := v.Export().([]interface{}); ok {
				for _, raw := range arr {
					m, ok := raw.(map[string]interface{})
					if !ok {
						continue
					}
					config.ICEServers = append(config.ICEServers, parseICEServer(m))
				}
			}
		}
		if v := opts.Get("iceTransportPolicy"); v != nil && !goja.IsUndefined(v) && v.String() == "relay" {
			config.ICETransportPolicy = webrtc.ICETransportPolicyRelay
		}
	}

	if opts, ok := call.Argument(0).(*goja.Object); !ok || opts == nil {
		pace = tuneConnection(&settings, t, nil)
	}
	pc, err := webrtc.NewAPI(webrtc.WithSettingEngine(settings)).NewPeerConnection(config)
	if err != nil {
		panic(vm.NewGoError(err))
	}
	t.addCloser(func() { _ = pc.Close() })

	obj := vm.NewObject()

	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		t.loop.RunOnLoop(func(vm *goja.Runtime) {
			fn, ok := goja.AssertFunction(obj.Get("onicecandidate"))
			if !ok {
				return
			}
			if c == nil {
				_, _ = fn(obj, goja.Null())
				return
			}
			init := c.ToJSON()
			out := vm.NewObject()
			out.Set("candidate", init.Candidate)
			if init.SDPMid != nil {
				out.Set("sdpMid", *init.SDPMid)
			}
			if init.SDPMLineIndex != nil {
				out.Set("sdpMLineIndex", *init.SDPMLineIndex)
			}
			_, _ = fn(obj, out)
		})
	})
	pc.OnICEConnectionStateChange(func(s webrtc.ICEConnectionState) {
		t.loop.RunOnLoop(func(vm *goja.Runtime) {
			if fn, ok := goja.AssertFunction(obj.Get("oniceconnectionstatechange")); ok {
				_, _ = fn(obj, vm.ToValue(s.String()))
			}
		})
	})
	pc.OnConnectionStateChange(func(s webrtc.PeerConnectionState) {
		t.loop.RunOnLoop(func(vm *goja.Runtime) {
			if fn, ok := goja.AssertFunction(obj.Get("onconnectionstatechange")); ok {
				_, _ = fn(obj, vm.ToValue(s.String()))
			}
		})
	})
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		t.loop.RunOnLoop(func(vm *goja.Runtime) {
			fn, ok := goja.AssertFunction(obj.Get("ondatachannel"))
			if !ok {
				return
			}
			djs := newDataChannelJS(vm, t, dc, pace)
			_, _ = fn(obj, djs)
		})
	})

	obj.Set("createDataChannel", func(call goja.FunctionCall) goja.Value {
		label := call.Argument(0).String()
		init := &webrtc.DataChannelInit{}
		if o, ok := call.Argument(1).(*goja.Object); ok {
			if v := o.Get("ordered"); v != nil && !goja.IsUndefined(v) {
				ordered := v.ToBoolean()
				init.Ordered = &ordered
			}
			if v := o.Get("maxRetransmits"); v != nil && !goja.IsUndefined(v) {
				mr := uint16(v.ToInteger())
				init.MaxRetransmits = &mr
			}
		}
		dc, err := pc.CreateDataChannel(label, init)
		if err != nil {
			panic(vm.NewGoError(err))
		}
		return newDataChannelJS(vm, t, dc, pace)
	})

	// addTransceiver(kind, {direction}) puts an RTP m-line into the next offer. A data-channel-only
	// offer is refused by servers that expect a conference client (MTS-Link's SFU wants an audio
	// line); "recvonly" asks for nothing to be sent, so no media ever flows from here.
	obj.Set("addTransceiver", func(call goja.FunctionCall) goja.Value {
		kind := webrtc.NewRTPCodecType(call.Argument(0).String())
		if kind == 0 {
			panic(vm.NewTypeError(`addTransceiver(kind, init): kind must be "audio" or "video"`))
		}
		init := webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionSendrecv}
		if o, ok := call.Argument(1).(*goja.Object); ok {
			if v := o.Get("direction"); v != nil && !goja.IsUndefined(v) {
				d := webrtc.NewRTPTransceiverDirection(v.String())
				if d == 0 {
					panic(vm.NewTypeError(`addTransceiver(kind, init): direction must be "sendrecv", "sendonly", "recvonly" or "inactive"`))
				}
				init.Direction = d
			}
		}
		if _, err := pc.AddTransceiverFromKind(kind, init); err != nil {
			panic(vm.NewGoError(err))
		}
		return goja.Undefined()
	})

	obj.Set("createOffer", func(call goja.FunctionCall) goja.Value {
		promise, resolve, reject := vm.NewPromise()
		desc, err := pc.CreateOffer(nil)
		if err != nil {
			_ = reject(vm.NewGoError(err))
		} else {
			_ = resolve(desc.SDP)
		}
		return vm.ToValue(promise)
	})
	obj.Set("createAnswer", func(call goja.FunctionCall) goja.Value {
		promise, resolve, reject := vm.NewPromise()
		desc, err := pc.CreateAnswer(nil)
		if err != nil {
			_ = reject(vm.NewGoError(err))
		} else {
			_ = resolve(desc.SDP)
		}
		return vm.ToValue(promise)
	})
	obj.Set("setLocalDescription", func(call goja.FunctionCall) goja.Value {
		return setDescription(vm, call, pc.SetLocalDescription)
	})
	obj.Set("setRemoteDescription", func(call goja.FunctionCall) goja.Value {
		return setDescription(vm, call, pc.SetRemoteDescription)
	})
	obj.Set("addIceCandidate", func(call goja.FunctionCall) goja.Value {
		promise, resolve, reject := vm.NewPromise()
		o, _ := call.Argument(0).(*goja.Object)
		if o == nil {
			_ = reject(vm.NewTypeError("addIceCandidate(init): init must be an object"))
			return vm.ToValue(promise)
		}
		init := webrtc.ICECandidateInit{Candidate: o.Get("candidate").String()}
		if v := o.Get("sdpMid"); v != nil && !goja.IsUndefined(v) {
			s := v.String()
			init.SDPMid = &s
		}
		if v := o.Get("sdpMLineIndex"); v != nil && !goja.IsUndefined(v) {
			idx := uint16(v.ToInteger())
			init.SDPMLineIndex = &idx
		}
		if err := pc.AddICECandidate(init); err != nil {
			_ = reject(vm.NewGoError(err))
		} else {
			_ = resolve(goja.Undefined())
		}
		return vm.ToValue(promise)
	})
	obj.Set("close", func(call goja.FunctionCall) goja.Value {
		_ = pc.Close()
		return goja.Undefined()
	})

	return obj
}

func setDescription(vm *goja.Runtime, call goja.FunctionCall, setFn func(webrtc.SessionDescription) error) goja.Value {
	promise, resolve, reject := vm.NewPromise()
	var sdpType webrtc.SDPType
	switch call.Argument(0).String() {
	case "offer":
		sdpType = webrtc.SDPTypeOffer
	case "answer":
		sdpType = webrtc.SDPTypeAnswer
	default:
		_ = reject(vm.NewTypeError(`setDescription(type, sdp): type must be "offer" or "answer"`))
		return vm.ToValue(promise)
	}
	if err := setFn(webrtc.SessionDescription{Type: sdpType, SDP: call.Argument(1).String()}); err != nil {
		_ = reject(vm.NewGoError(err))
	} else {
		_ = resolve(goja.Undefined())
	}
	return vm.ToValue(promise)
}

func parseICEServer(m map[string]interface{}) webrtc.ICEServer {
	s := webrtc.ICEServer{}
	if urls, ok := m["urls"].([]interface{}); ok {
		for _, u := range urls {
			if str, ok := u.(string); ok {
				s.URLs = append(s.URLs, str)
			}
		}
	} else if u, ok := m["urls"].(string); ok {
		s.URLs = []string{u}
	}
	if v, ok := m["username"].(string); ok {
		s.Username = v
	}
	if v, ok := m["credential"].(string); ok {
		s.Credential = v
		s.CredentialType = webrtc.ICECredentialTypePassword
	}
	return s
}

// newDataChannelJS wraps a *webrtc.DataChannel - one created locally via
// createDataChannel, or one delivered through ondatachannel for a
// remote-initiated channel. send() auto-detects bytes-like vs. string, same
// convention as the ws.open Socket wrapper in host.go.
func newDataChannelJS(vm *goja.Runtime, t *ScriptTransport, dc *webrtc.DataChannel, pace *paceConfig) *goja.Object {
	obj := vm.NewObject()
	obj.Set("label", dc.Label())

	dc.OnOpen(func() {
		t.loop.RunOnLoop(func(vm *goja.Runtime) {
			if fn, ok := goja.AssertFunction(obj.Get("onopen")); ok {
				_, _ = fn(obj)
			}
		})
	})
	dc.OnClose(func() {
		t.loop.RunOnLoop(func(vm *goja.Runtime) {
			if fn, ok := goja.AssertFunction(obj.Get("onclose")); ok {
				_, _ = fn(obj)
			}
		})
	})
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		data := msg.Data
		isString := msg.IsString
		t.loop.RunOnLoop(func(vm *goja.Runtime) {
			fn, ok := goja.AssertFunction(obj.Get("onmessage"))
			if !ok {
				return
			}
			// Always an ArrayBuffer; the second argument says whether the peer sent it as text, for
			// a channel that carries signaling (strings) next to the tunnel's packets (binary).
			_, _ = fn(obj, vm.ToValue(vm.NewArrayBuffer(data)), vm.ToValue(isString))
		})
	})

	obj.Set("send", func(call goja.FunctionCall) goja.Value {
		arg := call.Argument(0)
		if _, isObj := arg.(*goja.Object); isObj {
			if b, err := bytesArg(vm, arg); err == nil {
				if pace != nil {
					if limit := pace.dcbuf.Load(); limit > 0 && int64(dc.BufferedAmount()) > limit {
						pace.dcDropped.Add(1)
						return goja.Undefined()
					}
				}
				if err := dc.Send(b); err != nil {
					panic(vm.NewGoError(err))
				}
				return goja.Undefined()
			}
		}
		if err := dc.SendText(arg.String()); err != nil {
			panic(vm.NewGoError(err))
		}
		return goja.Undefined()
	})
	obj.Set("close", func(call goja.FunctionCall) goja.Value {
		_ = dc.Close()
		return goja.Undefined()
	})

	return obj
}

// tuneConnection applies the knobs that decide how the connection behaves on a lossy relayed path:
//
//	paceKBps      spaces the datagrams written to a TURN server out to this many KB/s (see paceConfig)
//	paceQueue     datagrams that may wait for their turn (60), paceBurst datagrams that may leave together (1)
//	dcBufKB       the most a data channel may hold unsent or unacknowledged before send() drops (no limit)
//	sctpRtoMaxMs  caps SCTP's retransmission backoff (60 s by default: a channel that lost a window stays
//	              dead for about that long)
//	sctpMinCwnd   the least SCTP's congestion window may shrink to, bytes. Over a path that drops in bursts
//	              SCTP reads every loss as congestion and slows to one packet per round trip, while the TCP
//	              inside the tunnel has its own congestion control and needs no second one below it
//	sctpMtu       the size of the packets SCTP sends (1191 by default, the largest that always fits); the
//	              datagram is this plus 69 bytes of DTLS, TURN, UDP and IP
//
// OPENFLUX_PACE_FILE (rate=, queue=, burst=, dcbuf=), OPENFLUX_SCTP_RTOMAX_MS and OPENFLUX_SCTP_MINCWND
// override them for a test, without a new script. The result is the pacing configuration the connection's
// data channels read their limit from, or nil.
func tuneConnection(se *webrtc.SettingEngine, t *ScriptTransport, opts *goja.Object) *paceConfig {
	var pace *paceConfig
	rtoMax, minCwnd, mtu := 0, 0, 0
	if opts != nil {
		if v := opts.Get("paceKBps"); v != nil && !goja.IsUndefined(v) && !goja.IsNull(v) && v.ToInteger() > 0 {
			pace = &paceConfig{}
			pace.rate.Store(v.ToInteger() * 1000)
			pace.queue.Store(60)
			pace.burst.Store(1)
			if q := opts.Get("paceQueue"); q != nil && !goja.IsUndefined(q) && q.ToInteger() > 0 {
				pace.queue.Store(q.ToInteger())
			}
			if b := opts.Get("paceBurst"); b != nil && !goja.IsUndefined(b) && b.ToInteger() > 0 {
				pace.burst.Store(b.ToInteger())
			}
		}
		if v := opts.Get("dcBufKB"); v != nil && !goja.IsUndefined(v) && !goja.IsNull(v) && v.ToInteger() > 0 {
			if pace == nil {
				pace = &paceConfig{}
			}
			pace.dcbuf.Store(v.ToInteger() * 1000)
		}
		if v := opts.Get("sctpRtoMaxMs"); v != nil && !goja.IsUndefined(v) && !goja.IsNull(v) {
			rtoMax = int(v.ToInteger())
		}
		if v := opts.Get("sctpMinCwnd"); v != nil && !goja.IsUndefined(v) && !goja.IsNull(v) {
			minCwnd = int(v.ToInteger())
		}
		if v := opts.Get("sctpMtu"); v != nil && !goja.IsUndefined(v) && !goja.IsNull(v) {
			mtu = int(v.ToInteger())
		}
	}
	if env := paceFromEnv(); env != nil {
		pace = env
	}
	if v, err := strconv.Atoi(os.Getenv("OPENFLUX_SCTP_RTOMAX_MS")); err == nil && v > 0 {
		rtoMax = v
	}
	if v, err := strconv.Atoi(os.Getenv("OPENFLUX_SCTP_MINCWND")); err == nil && v > 0 {
		minCwnd = v
	}
	if v, err := strconv.Atoi(os.Getenv("OPENFLUX_SCTP_MTU")); err == nil && v > 0 {
		mtu = v
	}
	if pace != nil {
		if n, err := stdnet.NewNet(); err == nil {
			se.SetNet(&pacedNet{Net: n, cfg: pace, name: t.name})
		}
	}
	if rtoMax > 0 {
		se.SetSCTPRTOMax(time.Duration(rtoMax) * time.Millisecond)
	}
	if minCwnd > 0 {
		se.SetSCTPMinCwnd(uint32(minCwnd))
	}
	if mtu > 0 {
		if err := sctp.SetOutboundMTU(uint32(mtu)); err != nil {
			utils.Debugf("[SCRIPT:%s] sctpMtu %d refused: %v", t.name, mtu, err)
		}
	}
	return pace
}
