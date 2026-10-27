package hostprofile

import "testing"

func TestProfileStartsUnconstrainedAndIsOneWay(t *testing.T) {
	t.Cleanup(ResetForTest)
	ResetForTest()
	if BuffersAreConstrained() {
		t.Fatal("a fresh process must not start constrained")
	}
	UseConstrainedBuffers()
	if !BuffersAreConstrained() {
		t.Fatal("BuffersAreConstrained() = false after UseConstrainedBuffers()")
	}
	// Calling it again is harmless: a host may announce its shape more than once.
	UseConstrainedBuffers()
	if !BuffersAreConstrained() {
		t.Fatal("the profile did not stay constrained")
	}
}
