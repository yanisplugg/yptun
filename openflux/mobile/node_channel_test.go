package mobile

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"

	"github.com/p1neappleXpress/OpenFlux/share"
)

// Without an SSH connection the channel calls answer with an error, not a panic.
func TestNodeChannelCallsNeedConnection(t *testing.T) {
	for name, out := range map[string]string{
		"show": NodeShowChannel("of-abc123", ""),
		"set":  NodeSetTransports("of-abc123", `[{"type":"mailru","url":"https://cloud.mail.ru/public/DEmN/ETbZW2MPY"}]`, "", "", ""),
	} {
		var r struct {
			OK    bool   `json:"ok"`
			Error string `json:"error"`
		}
		if err := json.Unmarshal([]byte(out), &r); err != nil || r.OK || r.Error == "" {
			t.Fatalf("%s: %s", name, out)
		}
	}
}

// A link for an installed channel keeps the channel's context.
func TestNodeShareLinkContextKeepsContext(t *testing.T) {
	key := strings.Repeat("ab", 32)
	doc := "https://docs.yandex.ru/edit/d/S3sjFjA-ZmVWgXQPTmVwdSPegnqahzm72s0qoIz-cKg6OTJnZ2wza1d2UQ"
	link, err := NodeShareLinkContext("Нода", `[{"type":"mailru","url":"https://cloud.mail.ru/public/DEmN/ETbZW2MPY"}]`, key, "203.0.113.5", 8445, doc)
	if err != nil {
		t.Fatal(err)
	}
	c, err := share.Decode(link)
	if err != nil {
		t.Fatal(err)
	}
	if c.Context != doc || c.Transports[0].Type != "mailru" {
		t.Fatalf("link: %+v", c)
	}
}

// A kit needs no connection and answers with the script and what the client's profile needs of the channel.
func TestNodeKitWorksOffline(t *testing.T) {
	key := strings.Repeat("ab", 32)
	pkg := base64.StdEncoding.EncodeToString([]byte("flux"))
	ts := `[{"type":"mailru","url":"https://cloud.mail.ru/public/DEmN/ETbZW2MPY"},{"type":"script","script":"mx","pubkey":"` + strings.Repeat("cd", 32) + `","package":"` + pkg + `","params":"eyJ0Ijoie30ifQ"}]`
	out := NodeKit("of-kit", ts, key, true, "l4", "")
	var r struct {
		OK    bool   `json:"ok"`
		Error string `json:"error"`
		Kit   struct {
			Script     string `json:"script"`
			Paste      string `json:"paste"`
			Context    string `json:"context"`
			Transports []struct {
				Type     string `json:"type"`
				Priority int    `json:"priority"`
				Script   string `json:"script"`
			} `json:"transports"`
		} `json:"kit"`
	}
	if err := json.Unmarshal([]byte(out), &r); err != nil || !r.OK {
		t.Fatalf("NodeKit: %s", out)
	}
	if !strings.Contains(r.Kit.Script, "channel=of-kit") || !strings.Contains(r.Kit.Script, key) || !strings.HasPrefix(r.Kit.Paste, "sudo sh <<") {
		t.Errorf("kit = %.300s", r.Kit.Script)
	}
	if len(r.Kit.Transports) != 2 || r.Kit.Transports[0].Type != "mailru" || r.Kit.Transports[0].Priority != 90 || r.Kit.Transports[1].Script != "mx" {
		t.Errorf("transports = %+v", r.Kit.Transports)
	}
	if r.Kit.Context != "https://cloud.mail.ru/public/DEmN/ETbZW2MPY" {
		t.Errorf("context = %q", r.Kit.Context)
	}
	for name, bad := range map[string]string{
		"short key":      NodeKit("of-kit", ts, "abc", true, "l4", ""),
		"unknown source": NodeKit("of-kit", ts, key, true, "l4", "nonsense"),
		"bad json":       NodeKit("of-kit", "not json", key, true, "l4", ""),
	} {
		var f struct {
			OK    bool   `json:"ok"`
			Error string `json:"error"`
		}
		if err := json.Unmarshal([]byte(bad), &f); err != nil || f.OK || f.Error == "" {
			t.Errorf("%s: %s", name, bad)
		}
	}
}
