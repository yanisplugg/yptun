package runtime

import "testing"

func TestSmuxConfigUsesServerWindowsByDefault(t *testing.T) {
	t.Cleanup(ResetBufferProfileForTest)
	ResetBufferProfileForTest()

	cfg := SmuxConfig(0)
	if cfg.MaxReceiveBuffer != smuxMaxReceiveBuffer || cfg.MaxStreamBuffer != smuxMaxStreamBuffer {
		t.Fatalf("default windows = %d/%d, want %d/%d",
			cfg.MaxReceiveBuffer, cfg.MaxStreamBuffer, smuxMaxReceiveBuffer, smuxMaxStreamBuffer)
	}
	if BuffersAreConstrained() {
		t.Fatal("a fresh process must not start constrained")
	}
}

// An iOS packet tunnel extension is given about 50 MB and killed for
// exceeding it, so the session buffer alone has to be a fraction of that.
func TestConstrainedBuffersFitAMobileExtension(t *testing.T) {
	t.Cleanup(ResetBufferProfileForTest)
	ResetBufferProfileForTest()

	UseConstrainedBuffers()
	if !BuffersAreConstrained() {
		t.Fatal("BuffersAreConstrained() = false after UseConstrainedBuffers()")
	}
	cfg := SmuxConfig(0)
	if cfg.MaxReceiveBuffer != smuxConstrainedReceiveBuffer || cfg.MaxStreamBuffer != smuxConstrainedStreamBuffer {
		t.Fatalf("constrained windows = %d/%d, want %d/%d",
			cfg.MaxReceiveBuffer, cfg.MaxStreamBuffer, smuxConstrainedReceiveBuffer, smuxConstrainedStreamBuffer)
	}
	const extensionBudget = 50 * 1024 * 1024
	if cfg.MaxReceiveBuffer >= extensionBudget/4 {
		t.Fatalf("session buffer %d is not a fraction of the %d byte budget", cfg.MaxReceiveBuffer, extensionBudget)
	}
	// A window still has to cover the bandwidth-delay product of the relays
	// this runs over: ~200 KB at 5 Mbit/s and 300 ms.
	const bandwidthDelayProduct = 200 * 1024
	if cfg.MaxStreamBuffer < bandwidthDelayProduct {
		t.Fatalf("stream window %d is below the bandwidth-delay product %d", cfg.MaxStreamBuffer, bandwidthDelayProduct)
	}
	// Long keep-alive config must carry the same windows.
	if long := SmuxConfigLong(0); long.MaxReceiveBuffer != cfg.MaxReceiveBuffer {
		t.Fatalf("SmuxConfigLong receive buffer = %d, want %d", long.MaxReceiveBuffer, cfg.MaxReceiveBuffer)
	}
}
