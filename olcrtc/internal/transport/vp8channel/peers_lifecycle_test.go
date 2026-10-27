package vp8channel

import (
	"sync"
	"testing"
)

// ai-generated: race a retained peer lookup with table teardown and check for orphaned control KCP.
func TestPeerControlCreationDuringClose(t *testing.T) {
	for i := range 2000 {
		p := &streamTransport{control: newKCPPlane(16, nil)}
		peer := newStubPeerSession(t, 1)
		p.peers.add(peer)
		start := make(chan struct{})
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			<-start
			p.peerControlFor(1)
		}()
		go func() {
			defer wg.Done()
			<-start
			p.peers.closeAll()
		}()
		close(start)
		wg.Wait()

		control := peer.controlRuntime()
		if control == nil {
			continue
		}
		select {
		case <-control.conn.closed:
		default:
			control.close()
			t.Fatalf("iteration %d: live control KCP attached after peer/table close", i)
		}
	}
}
