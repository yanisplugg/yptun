package common

import "testing"

// ai-generated: the room hint is no part of the binding.
func TestBindingTokenIgnoresRoomHint(t *testing.T) {
	const room = "https://my.mts-link.ru/j/123/456"
	want := BindingToken("", room)
	for _, hinted := range []string{room + "?ice=tcp", room + "?ice=both", room + "?ice=udp"} {
		if got := BindingToken("", hinted); got != want {
			t.Errorf("BindingToken(%q) = %08x, want %08x as for the plain link", hinted, got, want)
		}
	}
	if BindingToken("", room+"?ice=quic") == want {
		t.Error("an unknown hint should stay part of the link")
	}
}
