package gate

import (
	"flag"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"
)

// ai-generated: whole file, the gate's docs pair (docs/gate.md and
// docs/gate.ru.md) held to what the gate reads: a flag, a CI secret, a
// threshold or a scenario added without a word in both languages fails
// here, and so does a variable of the list in gateVocabulary.

// gateVocabulary is every name a reader of the doc needs. The flags of the
// entry, the secrets the CI hands it, the thresholds a report shows and the
// scenarios are read from the code. The variables the entry reads and the
// prefix a bare Telemost id joins by are listed by hand: a variable the
// entry starts reading goes into the list too.
func gateVocabulary(t *testing.T, root string) []string {
	t.Helper()
	var names []string
	flag.VisitAll(func(f *flag.Flag) {
		if strings.HasPrefix(f.Name, "olcrtc.gate") {
			names = append(names, "-"+f.Name)
		}
	})
	names = append(names, envLink, envTelemostRooms, envWBStreamRooms, envJitsiHosts, EnvWBStreamToken, EnvMaxKeys,
		envEngineCommit, envEngineRef, envAppVersion, envRunNumber, envRunnerOS, envImageOS, telemostJoinPrefix)
	names = append(names, localProviders()...) // ai-generated: every provider a default run walks
	ci, err := os.ReadFile(filepath.Join(root, ".github", "workflows", "ci.yml"))
	if err != nil {
		t.Fatal(err)
	}
	secrets := regexp.MustCompile(`secrets\.(GATE_[A-Z0-9_]+)`).FindAllStringSubmatch(string(ci), -1)
	if len(secrets) == 0 {
		t.Fatal("ci.yml hands the gate no secret: the pattern or the workflow changed")
	}
	for _, m := range secrets {
		names = append(names, m[1])
	}
	for key := range Local.Map() {
		names = append(names, key)
	}
	for _, s := range Scenarios() {
		names = append(names, s.ID)
	}
	slices.Sort(names)
	return slices.Compact(names)
}

// mentions says whether text names name as a word of its own, so a flag is
// not found inside a longer one, nor a secret inside the variable that
// carries it.
func mentions(text, name string) bool {
	return regexp.MustCompile(`(^|[^-\w])` + regexp.QuoteMeta(name) + `([^-\w]|$)`).MatchString(text)
}

func TestGateDocsNameWhatTheGateReads(t *testing.T) {
	root := moduleRoot(t)
	vocabulary := gateVocabulary(t, root)
	for _, doc := range []string{"gate.md", "gate.ru.md"} { // every doc comes in both languages
		raw, err := os.ReadFile(filepath.Join(root, "docs", doc))
		if err != nil {
			t.Fatal(err)
		}
		for _, name := range vocabulary {
			if !mentions(string(raw), name) {
				t.Errorf("docs/%s does not name %s", doc, name)
			}
		}
	}
}

func TestMentionsFindsAWordOfItsOwn(t *testing.T) {
	for _, tc := range []struct {
		text, name string
		want       bool
	}{
		{"pass `-olcrtc.gate-dir`", "-olcrtc.gate-dir", true},
		{"-olcrtc.gate -olcrtc.gate-dry", "-olcrtc.gate", true},
		{"only -olcrtc.gate-dry here", "-olcrtc.gate", false},
		{"-olcrtc.gate-dir=/tmp/gate", "-olcrtc.gate-dir", true},
		{"the secret GATE_WBSTREAM_ROOMS", "GATE_WBSTREAM_ROOMS", true},
		{"OLCRTC_GATE_WBSTREAM_ROOMS only", "GATE_WBSTREAM_ROOMS", false},
		{"| S0 | connect |", "S0", true},
		{"S0-S7", "S0", false},
		{"https://telemost.yandex.ru/j/<id>", telemostJoinPrefix, true},
	} {
		if got := mentions(tc.text, tc.name); got != tc.want {
			t.Errorf("mentions(%q, %q) = %t, want %t", tc.text, tc.name, got, tc.want)
		}
	}
}
