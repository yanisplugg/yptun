package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// With CSQTT_FAKE_CLIENT set the test binary plays the Rust client: it binds the --listen UDP port,
// announces a TUNCONF, and reports (as a log line) the first IP packet the bridge sends it.
func TestMain(m *testing.M) {
	if os.Getenv("CSQTT_FAKE_CLIENT") == "1" {
		fakeClient()
		return
	}
	os.Exit(m.Run())
}

func fakeClient() {
	var listen string
	for i, a := range os.Args {
		if a == "--listen" {
			listen = os.Args[i+1]
		}
	}
	addr, _ := net.ResolveUDPAddr("udp", listen)
	c, err := net.ListenUDP("udp", addr)
	if err != nil {
		fmt.Println("fake client:", err)
		os.Exit(1)
	}
	b, _ := json.Marshal(map[string]string{"config": "TUNCONF:10.66.67.5:8.8.8.8,8.8.4.4:0:stream-v2"})
	fmt.Printf("%sCONFIG|%s\n", eventPrefix, b)
	fmt.Println("CAPTCHA_SOLVE|auto|https://x|tok")
	go func() { // answers the bridge gives on stdin
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			fmt.Println("fake got stdin:", sc.Text())
			if sc.Text() == "STOP" {
				os.Exit(0)
			}
		}
		os.Exit(0)
	}()
	buf := make([]byte, 2048)
	for {
		n, _, err := c.ReadFromUDP(buf)
		if err != nil {
			return
		}
		fmt.Printf("fake got packet: len=%d version=%d proto=%d\n", n, buf[0]>>4, buf[9])
	}
}

func TestBridgeServesSocksOverNetstack(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	bridge := filepath.Join(t.TempDir(), "csqtthost-test")
	if runtime.GOOS == "windows" {
		bridge += ".exe"
	}
	if out, err := exec.Command("go", "build", "-o", bridge, ".").CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}
	cmd := exec.Command(bridge)
	cmd.Env = append(os.Environ(), "CSQTT_FAKE_CLIENT=1")
	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill() }()
	opts, _ := json.Marshal(options{
		Client: exe, Listen: "127.0.0.1:0", Peer: "203.0.113.1:46000", VKHashes: "abc", Password: "p",
	})
	fmt.Fprintf(stdin, "OPTS %s\n", opts)

	lines := make(chan string, 64)
	go func() {
		sc := bufio.NewScanner(stdout)
		for sc.Scan() {
			lines <- sc.Text()
		}
		close(lines)
	}()
	wait := func(prefix string) string {
		t.Helper()
		deadline := time.After(20 * time.Second)
		for {
			select {
			case l, ok := <-lines:
				if !ok {
					t.Fatalf("bridge exited before %q", prefix)
				}
				t.Log(l)
				if strings.Contains(l, prefix) {
					return l
				}
			case <-deadline:
				t.Fatalf("timeout waiting for %q", prefix)
			}
		}
	}
	sl := wait("SOCKS5 on ")
	socksAddr := strings.TrimSpace(sl[strings.Index(sl, "SOCKS5 on ")+len("SOCKS5 on "):])
	ready := wait("READY ")
	if !strings.Contains(ready, "10.66.67.5|8.8.8.8,8.8.4.4|1300") {
		t.Fatalf("unexpected READY: %s", ready)
	}
	wait("fake got stdin: CAPTCHA_RESULT|error:no-webview")

	// A SOCKS CONNECT through the netstack must put a TCP SYN (IPv4, proto 6) on the client's UDP port.
	c, err := net.DialTimeout("tcp", socksAddr, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.Write([]byte{5, 1, 0})
	r := make([]byte, 2)
	c.Read(r)
	c.Write([]byte{5, 1, 0, 1, 10, 66, 67, 1, 0, 80}) // CONNECT 10.66.67.1:80
	wait("fake got packet: len=")
	fmt.Fprintln(stdin, "STOP")
	wait("STOPPED")
}
