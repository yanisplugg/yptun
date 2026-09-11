package wdtt

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const accountCredsLifetime = 9 * time.Minute

type injectedTurnCreds struct {
	Username  string
	Password  string
	URLs      []string
	ExpiresAt time.Time
}

type turnCredsPayload struct {
	User string   `json:"u"`
	Pass string   `json:"p"`
	URLs []string `json:"urls"`
}

type vkCredsFile struct {
	Hashes map[string]turnCredsPayload `json:"hashes"`
}

var (
	vkAuthModeValue     atomic.Value
	vkAnonPathValue     atomic.Value
	injectedCredsMu     sync.RWMutex
	injectedCredsByLink map[string]injectedTurnCreds
)

func init() {
	vkAuthModeValue.Store("anonymous")
	vkAnonPathValue.Store("vkcalls")
	injectedCredsByLink = make(map[string]injectedTurnCreds)
}

// TurnCredsResultChan receives TURN credentials from Android stdin handler.
var TurnCredsResultChan = make(chan turnCredsPayload, 1)

func setVkAuthMode(mode string) string {
	mode = strings.ToLower(strings.TrimSpace(mode))
	if mode != "anonymous" {
		mode = "account"
	}
	vkAuthModeValue.Store(mode)
	return mode
}

func getVkAuthMode() string {
	mode, _ := vkAuthModeValue.Load().(string)
	if mode == "" {
		return "anonymous"
	}
	return mode
}

func setVkAnonPath(path string) string {
	path = strings.ToLower(strings.TrimSpace(path))
	if path == "legacy" {
		vkAnonPathValue.Store("legacy")
		return "legacy"
	}
	vkAnonPathValue.Store("vkcalls")
	return "vkcalls"
}

func getVkAnonPath() string {
	path, _ := vkAnonPathValue.Load().(string)
	if path == "" {
		return "vkcalls"
	}
	return path
}

func drainTurnCredsResult() {
	select {
	case <-TurnCredsResultChan:
	default:
	}
}

func injectTurnCreds(link, user, pass string, urls []string) {
	link = strings.TrimSpace(link)
	addresses := turnURLsToAddresses(urls)
	if link == "" || user == "" || pass == "" || len(addresses) == 0 {
		return
	}
	injectedCredsMu.Lock()
	defer injectedCredsMu.Unlock()
	injectedCredsByLink[link] = injectedTurnCreds{
		Username:  user,
		Password:  pass,
		URLs:      cloneStringSlice(addresses),
		ExpiresAt: time.Now().Add(accountCredsLifetime),
	}
}

func getInjectedTurnCreds(link string) (user, pass string, urls []string, ok bool) {
	link = strings.TrimSpace(link)
	injectedCredsMu.RLock()
	creds, exists := injectedCredsByLink[link]
	injectedCredsMu.RUnlock()
	if !exists {
		return "", "", nil, false
	}
	if time.Now().After(creds.ExpiresAt) {
		injectedCredsMu.Lock()
		delete(injectedCredsByLink, link)
		injectedCredsMu.Unlock()
		return "", "", nil, false
	}
	return creds.Username, creds.Password, cloneStringSlice(creds.URLs), true
}

func invalidateInjectedTurnCreds(link string) {
	link = strings.TrimSpace(link)
	injectedCredsMu.Lock()
	delete(injectedCredsByLink, link)
	injectedCredsMu.Unlock()
}

func loadVkCredsFile(path string) error {
	path = strings.TrimSpace(path)
	if path == "" {
		return nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var file vkCredsFile
	if err := json.Unmarshal(raw, &file); err != nil {
		return err
	}
	for link, payload := range file.Hashes {
		injectTurnCreds(link, payload.User, payload.Pass, payload.URLs)
	}
	log.Printf("[VK Auth] Loaded %d account TURN credential set(s) from file", len(file.Hashes))
	return nil
}

func handleTurnCredsStdinLine(line string) {
	line = strings.TrimPrefix(line, "TURN_CREDS|")
	if strings.HasPrefix(line, "error:") {
		drainTurnCredsResult()
		TurnCredsResultChan <- turnCredsPayload{}
		return
	}

	parts := strings.SplitN(line, "|", 2)
	if len(parts) != 2 {
		return
	}
	link := strings.TrimSpace(parts[0])
	payloadRaw, err := base64.StdEncoding.DecodeString(parts[1])
	if err != nil {
		log.Printf("[VK Auth] TURN_CREDS decode error: %v", err)
		return
	}
	var payload turnCredsPayload
	if err := json.Unmarshal(payloadRaw, &payload); err != nil {
		log.Printf("[VK Auth] TURN_CREDS JSON error: %v", err)
		return
	}
	if payload.User == "" || payload.Pass == "" || len(payload.URLs) == 0 {
		log.Printf("[VK Auth] TURN_CREDS rejected: empty fields for link %s", link)
		return
	}
		injectTurnCreds(link, payload.User, payload.Pass, payload.URLs)
		normalized := turnURLsToAddresses(payload.URLs)
		payload.URLs = cloneStringSlice(normalized)
		drainTurnCredsResult()
		TurnCredsResultChan <- payload
	log.Printf("[VK Auth] Account TURN creds received for link %s (urls=%d)", link, len(payload.URLs))
}

func requestAccountTurnCreds(ctx context.Context, link string, streamID int) (string, string, []string, error) {
	link = strings.TrimSpace(link)
	if u, p, urls, ok := getInjectedTurnCreds(link); ok {
		return u, p, urls, nil
	}

	drainTurnCredsResult()
	// YPtun: upstream printed "VK_AUTH_REQUIRED|link" for its Android host (WebView VK login).
	if !requestVKAuthFromHost(link) {
		return "", "", nil, fmt.Errorf("VK account login is not available on this host — use anonymous mode")
	}
	log.Printf("[STREAM %d] [VK Auth] Waiting for account TURN creds (link=%s...)", streamID, shortLink(link))

	waitCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()

	select {
	case payload := <-TurnCredsResultChan:
		if payload.User == "" {
			return "", "", nil, fmt.Errorf("VK account auth cancelled")
		}
		injectTurnCreds(link, payload.User, payload.Pass, payload.URLs)
		if u, p, urls, ok := getInjectedTurnCreds(link); ok {
			return u, p, urls, nil
		}
		return payload.User, payload.Pass, turnURLsToAddresses(payload.URLs), nil
	case <-waitCtx.Done():
		return "", "", nil, fmt.Errorf("VK account auth timeout: %w", waitCtx.Err())
	}
}

func shortLink(link string) string {
	if len(link) <= 8 {
		return link
	}
	return link[:8]
}

func fetchAccountVkCreds(ctx context.Context, link string, streamID int) (string, string, []string, error) {
	if u, p, urls, ok := getInjectedTurnCreds(link); ok {
		log.Printf("[STREAM %d] [VK Auth] Using injected account creds (urls=%d)", streamID, len(urls))
		return u, p, urls, nil
	}
	return requestAccountTurnCreds(ctx, link, streamID)
}
