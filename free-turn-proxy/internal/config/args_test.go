package config

import (
	"io"
	"reflect"
	"strings"
	"testing"
)

// Главное свойство ClientArgs: строка, показанная пользователю, разбирается
// обратно в тот же конфиг. Иначе экран "какая команда работает" начинает врать.
func TestClientArgsRoundTrip(t *testing.T) {
	cases := []struct {
		name string
		args []string
	}{
		{"minimal", validClientArgs()},
		{"tcp bond", append(validClientArgs(), "-mode", "tcp", "-bond")},
		{"udp transport", append(validClientArgs(), "-transport", "udp")},
		{"obf", append(validClientArgs(), "-obf-profile", "rtpopus3", "-obf-key", testObfKey)},
		{"obf timing", append(validClientArgs(), "-obf-profile", "rtpopus", "-obf-key", testObfKey, "-obf-timing", "20ms")},
		{"dns", append(validClientArgs(), "-dns-mode", "doh", "-dns-servers", "1.1.1.1,8.8.8.8:53")},
		{"turn override", append(validClientArgs(), "-turn", "5.5.5.5", "-port", "3478")},
		{"tuning", append(validClientArgs(), "-n", "3", "-streams-per-cred", "4")},
		{"vk extras", append(validClientArgs(), "-manual-captcha", "-platform", "mobile")},
		{"multi links", []string{"-peer", "1.2.3.4:5000", "-links", "https://vk.ru/call/join/A,https://vk.ru/call/join/B"}},
		{"misc", append(validClientArgs(), "-client-id", "deadbeef", "-listen", "127.0.0.1:1080", "-debug")},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			want, err := ParseClient(tc.args, io.Discard)
			if err != nil {
				t.Fatalf("ParseClient(%v) error = %v", tc.args, err)
			}

			rebuilt := ClientArgs(want)
			got, err := ParseClient(rebuilt, io.Discard)
			if err != nil {
				t.Fatalf("ParseClient(%v) error = %v", rebuilt, err)
			}
			if !reflect.DeepEqual(*got, *want) {
				t.Errorf("round-trip changed config\nargs %v\n got %+v\nwant %+v", rebuilt, *got, *want)
			}
		})
	}
}

// Дефолтные значения в строку не попадают: она должна читаться человеком.
func TestClientArgsOmitsDefaults(t *testing.T) {
	c, err := ParseClient(validClientArgs(), io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	got := strings.Join(ClientArgs(c), " ")
	want := "-peer 1.2.3.4:5000 -links abcdef"
	if got != want {
		t.Errorf("ClientArgs() = %q, want %q", got, want)
	}
}

func TestClientArgsNil(t *testing.T) {
	if got := ClientArgs(nil); got != nil {
		t.Errorf("ClientArgs(nil) = %v", got)
	}
}
