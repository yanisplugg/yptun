// Package bitrix24 implements a transport that tunnels data through Bitrix24
// chat messages. Each side writes base64-encoded frames as chat messages and
// polls the other side's messages to read them back. The domain *.bitrix24.ru
// sits in every Russian ISP's whitelist, so it is practically unblockable.
//
// URL format: https://<portal>.bitrix24.ru — the transport authenticates with
// the portal's session cookies (PHPSESSID + kernel) and sessid (CSRF token),
// passed via the "cookies" and "sessid" transport params.
package bitrix24

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/p1neappleXpress/OpenFlux/netbind"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/utils"
)

const (
	msgPrefix    = "OFX1:"
	userAgent    = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"
	recvQueue    = 256
	pollMin      = 150 * time.Millisecond
	pollMax      = 2 * time.Second
	pollGrowth   = 1.5
	batchSize    = 5
	numWriters   = 3
)

type Bitrix24Transport struct {
	*transport.BaseTransport

	portalURL string
	cookies   string
	sessid    string
	chatID    string

	writeQueue chan []byte
	recvCh     chan []byte

	lastMsgID atomic.Int64
	client    *http.Client

	bytesSent atomic.Uint64
	bytesRecv atomic.Uint64
	pktsSent  atomic.Uint64
	pktsRecv  atomic.Uint64
	reconnects atomic.Uint64
}

func NewBitrix24Transport(portalURL string, cfg transport.TransportConfig, cookies, sessid, chatID string) *Bitrix24Transport {
	portalURL = strings.TrimRight(portalURL, "/")
	if chatID != "" && !strings.HasPrefix(chatID, "chat") {
		chatID = "chat" + chatID
	}
	t := &Bitrix24Transport{
		BaseTransport: transport.NewBaseTransport(cfg),
		portalURL:     portalURL,
		cookies:       cookies,
		sessid:        sessid,
		chatID:        chatID,
		writeQueue:    make(chan []byte, 512),
	}
	return t
}

func (t *Bitrix24Transport) Start() error {
	if err := t.BaseTransport.Start(); err != nil {
		return err
	}

	t.client = &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			DialContext:         netbind.DialerFor(t.BaseTransport.GetConfig().Network, 30*time.Second).DialContext,
			MaxIdleConnsPerHost: numWriters + 2,
			IdleConnTimeout:    90 * time.Second,
		},
	}

	t.recvCh = make(chan []byte, recvQueue)
	utils.SafeGo("bitrix24.recv", func() {
		for {
			select {
			case pkt := <-t.recvCh:
				t.CallReceive(pkt)
			case <-t.Done():
				return
			}
		}
	})

	if t.chatID == "" {
		cid, err := t.createChat()
		if err != nil {
			return fmt.Errorf("bitrix24: create chat: %w", err)
		}
		t.chatID = "chat" + strconv.Itoa(cid)
		utils.Debugf("[B24] created chat %s", t.chatID)
	}

	for i := 0; i < numWriters; i++ {
		utils.SafeGo(fmt.Sprintf("bitrix24.writer.%d", i), t.writerLoop)
	}
	utils.SafeGo("bitrix24.poller", t.pollLoop)

	t.SetConnected(true)
	utils.Debugf("[B24] started: portal=%s chat=%s", t.portalURL, t.chatID)
	return nil
}

func (t *Bitrix24Transport) Stop() error {
	t.SetConnected(false)
	return t.BaseTransport.Stop()
}

func (t *Bitrix24Transport) Send(data []byte) error {
	if !t.IsConnected() {
		return fmt.Errorf("not connected")
	}
	select {
	case t.writeQueue <- data:
		return nil
	default:
		return fmt.Errorf("write queue full")
	}
}

func (t *Bitrix24Transport) Stats() transport.TransportStats {
	return transport.TransportStats{
		BytesSent:     t.bytesSent.Load(),
		BytesReceived: t.bytesRecv.Load(),
		PacketsSent:   t.pktsSent.Load(),
		PacketsRecv:   t.pktsRecv.Load(),
		Reconnects:    t.reconnects.Load(),
		Connected:     t.IsConnected(),
	}
}

func (t *Bitrix24Transport) PreferredBatchBytes() int {
	if v := os.Getenv("B24_BATCH_BYTES"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return 100_000
}

func (t *Bitrix24Transport) ChatID() string { return t.chatID }

// --- REST API ---

func (t *Bitrix24Transport) restCall(method string, params url.Values) (json.RawMessage, error) {
	if params == nil {
		params = url.Values{}
	}
	params.Set("sessid", t.sessid)

	req, err := http.NewRequest("POST", t.portalURL+"/rest/"+method+".json", strings.NewReader(params.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Cookie", t.cookies)
	req.Header.Set("User-Agent", userAgent)

	resp, err := t.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}

	var result struct {
		Result json.RawMessage `json:"result"`
		Error  string          `json:"error"`
		Desc   string          `json:"error_description"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("json: %w (body: %.200s)", err, body)
	}
	if result.Error != "" {
		return nil, fmt.Errorf("%s: %s", result.Error, result.Desc)
	}
	return result.Result, nil
}

func (t *Bitrix24Transport) createChat() (int, error) {
	raw, err := t.restCall("im.chat.add", url.Values{
		"TYPE":        {"CHAT"},
		"TITLE":       {"ofx-" + strconv.FormatInt(time.Now().UnixMilli(), 36)},
		"DESCRIPTION": {"tunnel"},
	})
	if err != nil {
		return 0, err
	}
	var id int
	if err := json.Unmarshal(raw, &id); err != nil {
		return 0, err
	}
	return id, nil
}

func (t *Bitrix24Transport) sendMessage(text string) (int, error) {
	raw, err := t.restCall("im.message.add", url.Values{
		"DIALOG_ID": {t.chatID},
		"MESSAGE":   {text},
	})
	if err != nil {
		return 0, err
	}
	var id int
	if err := json.Unmarshal(raw, &id); err != nil {
		return 0, err
	}
	return id, nil
}

type b24Message struct {
	ID       int    `json:"id"`
	AuthorID int    `json:"author_id"`
	Text     string `json:"text"`
}

type b24MessagesResult struct {
	Messages []b24Message `json:"messages"`
}

func (t *Bitrix24Transport) readMessages(lastID int64) ([]b24Message, error) {
	params := url.Values{
		"DIALOG_ID": {t.chatID},
		"LIMIT":     {"50"},
	}
	if lastID > 0 {
		params.Set("LAST_ID", strconv.FormatInt(lastID, 10))
	}
	raw, err := t.restCall("im.dialog.messages.get", params)
	if err != nil {
		return nil, err
	}
	var res b24MessagesResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Messages, nil
}

// --- Writer ---

func (t *Bitrix24Transport) writerLoop() {
	for {
		var batch [][]byte
		select {
		case <-t.Done():
			return
		case data := <-t.writeQueue:
			batch = append(batch, data)
		}
		// Drain non-blocking up to batchSize.
		for len(batch) < batchSize {
			select {
			case data := <-t.writeQueue:
				batch = append(batch, data)
			default:
				goto send
			}
		}
	send:
		if len(batch) == 1 {
			t.sendFrame(batch[0])
		} else {
			t.sendBatch(batch)
		}
	}
}

func (t *Bitrix24Transport) sendFrame(data []byte) {
	encoded := msgPrefix + base64.StdEncoding.EncodeToString(data)
	if err := t.sendWithRetry(func() error {
		_, err := t.sendMessage(encoded)
		return err
	}); err != nil {
		utils.Debugf("[B24] send error: %v", err)
		return
	}
	t.bytesSent.Add(uint64(len(data)))
	t.pktsSent.Add(1)
}

func (t *Bitrix24Transport) sendBatch(frames [][]byte) {
	params := url.Values{}
	for i, data := range frames {
		encoded := msgPrefix + base64.StdEncoding.EncodeToString(data)
		cmd := fmt.Sprintf("im.message.add?DIALOG_ID=%s&MESSAGE=%s",
			url.QueryEscape(t.chatID), url.QueryEscape(encoded))
		params.Set(fmt.Sprintf("cmd[msg_%d]", i), cmd)
	}
	var raw json.RawMessage
	err := t.sendWithRetry(func() error {
		var e error
		raw, e = t.restCall("batch", params)
		return e
	})
	if err != nil {
		utils.Debugf("[B24] batch send error: %v", err)
		return
	}
	var batchRes struct {
		Result      map[string]json.RawMessage `json:"result"`
		ResultError map[string]json.RawMessage `json:"result_error"`
	}
	if err := json.Unmarshal(raw, &batchRes); err != nil {
		utils.Debugf("[B24] batch parse error: %v", err)
		return
	}
	ok := 0
	for i, data := range frames {
		key := fmt.Sprintf("msg_%d", i)
		if _, has := batchRes.ResultError[key]; has {
			continue
		}
		t.bytesSent.Add(uint64(len(data)))
		t.pktsSent.Add(1)
		ok++
	}
	utils.Debugf("[B24] batch sent %d/%d", ok, len(frames))
}

func (t *Bitrix24Transport) sendWithRetry(fn func() error) error {
	for attempt := 0; attempt < 5; attempt++ {
		err := fn()
		if err == nil {
			return nil
		}
		errStr := err.Error()
		if strings.Contains(errStr, "QUERY_LIMIT_EXCEEDED") ||
			strings.Contains(errStr, "OPERATION_TIME_LIMIT") {
			utils.Debugf("[B24] rate limited, waiting 2s (attempt %d)", attempt)
			select {
			case <-time.After(2 * time.Second):
			case <-t.Done():
				return err
			}
			continue
		}
		return err
	}
	return fmt.Errorf("failed after 5 retries")
}

// --- Poller ---

func (t *Bitrix24Transport) pollLoop() {
	interval := pollMin

	for {
		select {
		case <-t.Done():
			return
		case <-time.After(interval):
		}

		msgs, err := t.readMessages(t.lastMsgID.Load())
		if err != nil {
			utils.Debugf("[B24] poll error: %v", err)
			interval = pollMax
			continue
		}

		delivered := 0
		for _, msg := range msgs {
			if int64(msg.ID) <= t.lastMsgID.Load() {
				continue
			}
			t.lastMsgID.Store(int64(msg.ID))

			if !strings.HasPrefix(msg.Text, msgPrefix) {
				continue
			}
			data, err := base64.StdEncoding.DecodeString(msg.Text[len(msgPrefix):])
			if err != nil {
				utils.Debugf("[B24] decode error on msg %d: %v", msg.ID, err)
				continue
			}

			t.bytesRecv.Add(uint64(len(data)))
			t.pktsRecv.Add(1)
			delivered++

			select {
			case t.recvCh <- data:
			default:
				utils.Debugf("[B24] recv queue full, dropping msg %d", msg.ID)
			}
		}

		if delivered > 0 {
			interval = pollMin
		} else {
			interval = time.Duration(float64(interval) * pollGrowth)
			if interval > pollMax {
				interval = pollMax
			}
		}
	}
}

// FetchCookies returns the current auth state for cookie persistence.
func (t *Bitrix24Transport) FetchCookies() (map[string]string, error) {
	return map[string]string{
		"cookies": t.cookies,
		"sessid":  t.sessid,
		"chat_id": t.chatID,
	}, nil
}

// ApplyCookies updates the auth state (e.g. after a cookie refresh).
func (t *Bitrix24Transport) ApplyCookies(values map[string]string) error {
	if v, ok := values["cookies"]; ok && v != "" {
		t.cookies = v
	}
	if v, ok := values["sessid"]; ok && v != "" {
		t.sessid = v
	}
	return nil
}
