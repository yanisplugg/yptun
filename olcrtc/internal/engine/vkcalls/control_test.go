package vkcalls

import (
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"testing"
)

// The registry vectors are frames captured from the live SFU (spike
// hybrid-09): kind-1 maps of a camera stream description to its compact id.
func TestParseRegistry(t *testing.T) {
	cases := []struct {
		name    string
		b64     string
		want    Registry
		wantErr bool
	}{
		{
			name: "camera stream compact 1",
			// 01 81 b5 <21-byte key "u588003930589:sCAMERA"> 01, live capture
			b64:  "AYG1dTU4ODAwMzkzMDU4OTpzQ0FNRVJBAQ==",
			want: Registry{"u588003930589:sCAMERA": 1},
		},
		{
			// The shape the SFU sends when a layout asked for several streams
			// by string key at once (live 2026-09-28): one fixmap, many
			// entries. Constructed with test ids in that shape.
			name: "three cameras in one frame",
			b64: base64.StdEncoding.EncodeToString(append(append(append([]byte{0x01, 0x83},
				mpEntry("u100000000001:sCAMERA", 0x00)...), mpEntry("u100000000002:sCAMERA", 0x01)...),
				mpEntry("u100000000003", 0x02)...)),
			want: Registry{"u100000000001:sCAMERA": 0, "u100000000002:sCAMERA": 1, "u100000000003": 2},
		},
		{
			name: "map16 with a uint8 compact id",
			b64: base64.StdEncoding.EncodeToString(append(append([]byte{0x01, 0xDE, 0x00, 0x02},
				mpEntry("u1:sCAMERA", 0x05)...), append(mpStr("u2:sCAMERA"), 0xCC, 0x80)...)),
			want: Registry{"u1:sCAMERA": 5, "u2:sCAMERA": 128},
		},
		{
			name:    "second entry truncated",
			b64:     base64.StdEncoding.EncodeToString(append([]byte{0x01, 0x82}, mpEntry("u1:sCAMERA", 0x00)...)),
			wantErr: true,
		},
		{
			name:    "empty frame",
			b64:     "",
			wantErr: true,
		},
		{
			name:    "truncated",
			b64:     "AQE=",
			wantErr: true,
		},
		{
			name:    "not a map",
			b64:     "AZAA",
			wantErr: true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			raw, err := base64.StdEncoding.DecodeString(tc.b64)
			if err != nil {
				t.Fatal(err)
			}
			got, err := ParseRegistry(raw)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if len(got) != len(tc.want) {
				t.Fatalf("registry size %d, want %d", len(got), len(tc.want))
			}
			for key, id := range tc.want {
				if got[key] != id {
					t.Fatalf("key %q: compact %d, want %d", key, got[key], id)
				}
			}
		})
	}
}

// mpStr is a msgpack fixstr; mpEntry one registry entry with a fixint id.
func mpStr(key string) []byte {
	return append([]byte{0xA0 | byte(len(key)&0x1F)}, key...)
}

func mpEntry(key string, id byte) []byte { return append(mpStr(key), id) }

// TestParseRegistryOtherKind checks non-registry frames are marked
// distinctly: the notification channel carries several kinds.
func TestParseRegistryOtherKind(t *testing.T) {
	if _, err := ParseRegistry([]byte{0x02, 0x91, 0x00}); !errors.Is(err, ErrNotRegistry) {
		t.Fatalf("want ErrNotRegistry, got %v", err)
	}
}

// The golden bytes are the compact-addressed form the live SFU accepted
// (spike hybrid-11): update-display-layout, sequence 1, one stream at
// compact id 0, priority 1, 640x360, fit=cv.
func TestEncodeLayoutGolden(t *testing.T) {
	frame, err := EncodeLayout(1, []LayoutEntry{{Key: "u604532170515:sCAMERA", Compact: 0, ByCompact: true, Priority: 1, Width: 640, Height: 360}})
	if err != nil {
		t.Fatal(err)
	}
	const golden = "000001c091c40a000001d10280d1016800c0"
	if got := hexOf(frame); got != golden {
		t.Fatalf("frame %s, want %s", got, golden)
	}
}

// TestEncodeLayoutStringKey pins the string-addressed form the SDK falls back
// to without a registry: priority 0 is a set value, encoded as fixint 0.
func TestEncodeLayoutStringKey(t *testing.T) {
	frame, err := EncodeLayout(2, []LayoutEntry{{Key: "42:sCAMERA", Priority: 0}})
	if err != nil {
		t.Fatal(err)
	}
	const golden = "000002c091c410aa34323a7343414d4552410000c0c000c0"
	if got := hexOf(frame); got != golden {
		t.Fatalf("frame %s, want %s", got, golden)
	}
}

func TestEncodeLayoutEmpty(t *testing.T) {
	if _, err := EncodeLayout(1, nil); err == nil {
		t.Fatal("expected error for empty layout")
	}
}

// TestRegistryLayoutFor pins the subscribe-all policy: every registry stream,
// compact-addressed, stable key order.
func TestRegistryLayoutFor(t *testing.T) {
	r := Registry{"b:sCAMERA": 1, "a:sCAMERA": 0}
	entries, err := r.LayoutFor()
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 {
		t.Fatalf("entries %d, want 2", len(entries))
	}
	if entries[0].Key != "a:sCAMERA" || !entries[0].ByCompact || entries[0].Compact != 0 {
		t.Fatalf("first entry %+v", entries[0])
	}
	if entries[1].Key != "b:sCAMERA" || entries[1].Compact != 1 {
		t.Fatalf("second entry %+v", entries[1])
	}
	if entries[0].Priority != 1 || entries[0].Width != 640 || entries[0].Height != 360 {
		t.Fatalf("layout fields %+v", entries[0])
	}
}

func hexOf(b []byte) string {
	const digits = "0123456789abcdef"
	out := make([]byte, 0, len(b)*2)
	for _, v := range b {
		out = append(out, digits[v>>4], digits[v&0x0f])
	}
	return string(out)
}

// TestLayoutEntriesAskUnknownCamerasByKey pins how a consumer subscribes: a
// stream the registry names goes by its compact id; a participant's camera
// the registry does not name yet goes by its string key, which is how the
// SFU hands out a compact id for it (live 2026-09-28: without this the
// engine never received a real client's camera).
func TestLayoutEntriesAskUnknownCamerasByKey(t *testing.T) {
	r := Registry{"u1:sCAMERA": 0, "u3": 2}
	entries := layoutEntries(r, []string{"3", "1", "2"})
	var got []string
	for _, e := range entries {
		if e.ByCompact {
			got = append(got, fmt.Sprintf("%s#%d", e.Key, e.Compact))
		} else {
			got = append(got, e.Key+"#str")
		}
		if e.Priority != 1 || e.Width != 640 || e.Height != 360 {
			t.Fatalf("layout fields %+v", e)
		}
	}
	want := []string{"u1:sCAMERA#0", "u3#2", "u2:sCAMERA#str", "u3:sCAMERA#str"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("entries %v, want %v", got, want)
	}
	if entries := layoutEntries(Registry{}, nil); len(entries) != 0 {
		t.Fatalf("nothing to ask for, got %v", entries)
	}
}

// TestEncodeLayoutManyEntries: sixteen streams and more take the array16
// form instead of failing the whole subscription.
func TestEncodeLayoutManyEntries(t *testing.T) {
	entries := make([]LayoutEntry, 17)
	for i := range entries {
		entries[i] = LayoutEntry{Compact: uint8(i), ByCompact: true, Priority: 1, Width: 640, Height: 360}
	}
	frame, err := EncodeLayout(3, entries)
	if err != nil {
		t.Fatal(err)
	}
	if frame[3] != 0xC0 || frame[4] != 0xDC || frame[5] != 0x00 || frame[6] != 17 {
		t.Fatalf("header % x, want array16 of 17", frame[:7])
	}
}

// TestGenerationTracksParticipants: a participant noted is asked for by its
// camera key until the registry names it; one who hangs up leaves both the
// asked set and the registry. Layouts built from several goroutines at once
// stay race-free (run with -race).
func TestGenerationTracksParticipants(t *testing.T) {
	gen := &generation{registry: Registry{"u7:sCAMERA": 3, "u7": 4, "u8:sCAMERA": 5}}
	if !gen.noteParticipant("9") || gen.noteParticipant("9") {
		t.Fatal("noteParticipant reports a new id once")
	}
	gen.noteParticipant("7")
	keys := func() string {
		gen.registryMu.Lock()
		defer gen.registryMu.Unlock()
		entries := layoutEntries(gen.registry, gen.participantList())
		out := make([]string, 0, len(entries))
		for _, e := range entries {
			out = append(out, e.Key)
		}
		return strings.Join(out, ",")
	}
	if got := keys(); got != "u7,u7:sCAMERA,u8:sCAMERA,u9:sCAMERA" {
		t.Fatalf("layout keys %s", got)
	}
	gen.removeStream("7")
	gen.removeStream("9")
	if got := keys(); got != "u8:sCAMERA" {
		t.Fatalf("after hang-ups: %s", got)
	}
	done := make(chan struct{})
	for range 4 {
		go func() { gen.subscribeAll(); done <- struct{}{} }()
	}
	for range 4 {
		<-done
	}
}
