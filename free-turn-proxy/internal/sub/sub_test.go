package sub

import (
	"strings"
	"testing"

	"github.com/samosvalishe/free-turn-proxy/internal/uri"
)

func TestParse(t *testing.T) {
	link1 := (&uri.Config{
		Version: 1, Provider: "vk", Peer: "1.1.1.1:56000",
	}).String()
	link2 := (&uri.Config{
		Version: 1, Provider: "vk", Peer: "2.2.2.2:56000",
	}).String()

	data := "#name: Test Sub\n" +
		"#refresh: 1h\n" +
		"#color: #123456\n\n" +
		link1 + "\n" +
		"##name: Server 1\n" +
		"##ip: 1.1.1.1\n\n" +
		link2 + "\n" +
		"##name: Server 2\n"

	s, err := Parse(strings.NewReader(data))
	if err != nil {
		t.Fatalf("Parse error: %v", err)
	}

	if s.Name != "Test Sub" {
		t.Errorf("Sub.Name = %v, want Test Sub", s.Name)
	}
	if s.Refresh != "1h" {
		t.Errorf("Sub.Refresh = %v, want 1h", s.Refresh)
	}
	if s.Color != "#123456" {
		t.Errorf("Sub.Color = %v, want #123456", s.Color)
	}

	if len(s.Nodes) != 2 {
		t.Fatalf("len(Nodes) = %d, want 2", len(s.Nodes))
	}

	n1 := s.Nodes[0]
	if n1.Name != "Server 1" {
		t.Errorf("Node[0].Name = %v, want Server 1", n1.Name)
	}
	if n1.IP != "1.1.1.1" {
		t.Errorf("Node[0].IP = %v, want 1.1.1.1", n1.IP)
	}
	if n1.URI.Provider != "vk" {
		t.Errorf("Node[0].URI = %+v", n1.URI)
	}

	n2 := s.Nodes[1]
	if n2.Name != "Server 2" {
		t.Errorf("Node[1].Name = %v, want Server 2", n2.Name)
	}
	if n2.URI.Peer != "2.2.2.2:56000" {
		t.Errorf("Node[1].URI.Peer = %v, want 2.2.2.2:56000", n2.URI.Peer)
	}
}

// Подписка наследует поля uri: mode должен доезжать до клиента без потерь.
func TestParseCarriesMode(t *testing.T) {
	link := (&uri.Config{Version: 1, Provider: "vk", Peer: "1.1.1.1:56000", Mode: "tcp"}).String()

	s, err := Parse(strings.NewReader(link + "\n"))
	if err != nil {
		t.Fatalf("Parse error: %v", err)
	}
	if len(s.Nodes) != 1 {
		t.Fatalf("Nodes = %d, want 1", len(s.Nodes))
	}
	if s.Nodes[0].URI.Mode != "tcp" {
		t.Errorf("Mode = %q, want tcp", s.Nodes[0].URI.Mode)
	}
}
