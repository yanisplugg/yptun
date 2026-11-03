package roomhint

import "testing"

func TestSplit(t *testing.T) {
	const room = "https://my.mts-link.ru/j/123/456"
	for _, tc := range []struct{ in, room, mode string }{
		{room, room, ICEDefault},
		{room + "?ice=tcp", room, ICETCP},
		{room + "?ice=both", room, ICEBoth},
		{room + "?ice=udp", room, ICEUDP},
		{" " + room + "?ice=tcp ", room, ICETCP},
		{room + "?x=1&ice=tcp", room + "?x=1", ICETCP},
		// unknown modes and lookalikes leave the link as it is
		{room + "?ice=quic", room + "?ice=quic", ICEDefault},
		{room + "?ice=tcp&x=1", room + "?ice=tcp&x=1", ICEDefault},
		{"https://telemost.yandex.ru/j/123", "https://telemost.yandex.ru/j/123", ICEDefault},
	} {
		gotRoom, gotMode := Split(tc.in)
		if gotRoom != tc.room || gotMode != tc.mode {
			t.Errorf("Split(%q) = %q, %q; want %q, %q", tc.in, gotRoom, gotMode, tc.room, tc.mode)
		}
	}
}

// ai-generated: a mode handed in by a caller is held to the words a link's hint is.
func TestModeKeepsOnlyTheModesThisPackageKnows(t *testing.T) {
	for _, tc := range []struct{ in, want string }{
		{ICEUDP, ICEUDP}, {ICETCP, ICETCP}, {ICEBoth, ICEBoth},
		{"", ICEDefault}, {"TCP", ICEDefault}, {"sideways", ICEDefault}, {"tcp ", ICEDefault},
	} {
		if got := Mode(tc.in); got != tc.want {
			t.Errorf("Mode(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}
