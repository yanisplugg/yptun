package mobile

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"

	"github.com/samosvalishe/free-turn-proxy/internal/session"
)

const testConfig = `{"peer":"1.2.3.4:5000","clientId":"deadbeef","vk":{"links":["https://vk.ru/call/join/CODE"]}}`

// recorder - EventSink для тестов.
type recorder struct {
	mu      sync.Mutex
	states  []string
	logs    []string
	captcha []string
}

func (r *recorder) OnState(state string, _, _ int, _ string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.states = append(r.states, state)
}

func (r *recorder) OnLog(level, msg string, _ int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.logs = append(r.logs, level+" "+msg)
}

func (r *recorder) OnCaptcha(url string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.captcha = append(r.captcha, url)
}

func TestStateConstantsMatchSessionPhases(t *testing.T) {
	pairs := map[string]session.Phase{
		StateIdle:       session.PhaseIdle,
		StateConnecting: session.PhaseConnecting,
		StateConnected:  session.PhaseConnected,
		StateCaptcha:    session.PhaseCaptcha,
		StateError:      session.PhaseError,
	}
	if len(pairs) != 5 {
		t.Fatalf("State constants collide: %v", pairs)
	}
	for state, phase := range pairs {
		if state != string(phase) {
			t.Fatalf("state %q != phase %q", state, phase)
		}
	}
}

func TestGetStateIdleWithoutSession(t *testing.T) {
	Stop()
	if got := GetState(); got.State != StateIdle {
		t.Fatalf("GetState().State = %q, want %q", got.State, StateIdle)
	}
}

func TestDefaultConfigJSONParses(t *testing.T) {
	var dto map[string]any
	if err := json.Unmarshal([]byte(DefaultConfigJSON()), &dto); err != nil {
		t.Fatalf("DefaultConfigJSON() is not valid JSON: %v", err)
	}
	// Дефолты не содержат peer и ссылок - валидацию проходить не обязаны.
	if msg := ValidateConfig(DefaultConfigJSON()); msg == "" {
		t.Error("ValidateConfig(defaults) = \"\", want error about peer")
	}
}

func TestValidateConfig(t *testing.T) {
	if msg := ValidateConfig(testConfig); msg != "" {
		t.Errorf("ValidateConfig(valid) = %q, want empty", msg)
	}
	if msg := ValidateConfig(`{"peer":"1.2.3.4:5000","bogus":1}`); msg == "" {
		t.Error("ValidateConfig(unknown field) = \"\", want error")
	}
	if msg := ValidateConfig("{"); msg == "" {
		t.Error("ValidateConfig(malformed) = \"\", want error")
	}
}

func TestConfigToArgs(t *testing.T) {
	got, err := ConfigToArgs(testConfig)
	if err != nil {
		t.Fatalf("ConfigToArgs() error = %v", err)
	}
	for _, want := range []string{"-peer 1.2.3.4:5000", "-links CODE", "-client-id deadbeef"} {
		if !strings.Contains(got, want) {
			t.Errorf("ConfigToArgs() = %q, missing %q", got, want)
		}
	}
	if _, err := ConfigToArgs("{"); err == nil {
		t.Error("ConfigToArgs(malformed) error = nil")
	}
}

func TestStartRequiresClientID(t *testing.T) {
	t.Cleanup(Stop)
	err := Start(`{"peer":"1.2.3.4:5000","vk":{"links":["CODE"]}}`)
	if err == nil || !strings.Contains(err.Error(), "clientId") {
		t.Fatalf("Start() error = %v, want clientId requirement", err)
	}
}

func TestStartManualCaptchaRequiresSink(t *testing.T) {
	SetEventSink(nil)
	t.Cleanup(Stop)

	in := `{"peer":"1.2.3.4:5000","clientId":"deadbeef","vk":{"links":["CODE"],"manualCaptcha":true}}`
	err := Start(in)
	if err == nil || !strings.Contains(err.Error(), "event sink") {
		t.Fatalf("Start() error = %v, want event sink requirement", err)
	}
}

func TestStartRejectsBadConfig(t *testing.T) {
	t.Cleanup(Stop)
	if err := Start("{"); err == nil {
		t.Fatal("Start(malformed) error = nil")
	}
	if GetState().State != StateIdle {
		t.Errorf("state after failed Start = %q", GetState().State)
	}
}

func TestSinkReceivesStateAndLogs(t *testing.T) {
	rec := &recorder{}
	SetEventSink(rec)
	t.Cleanup(func() {
		Stop()
		SetEventSink(nil)
	})

	if err := Start(testConfig); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	Stop()

	rec.mu.Lock()
	defer rec.mu.Unlock()
	if len(rec.states) == 0 || rec.states[0] != StateConnecting {
		t.Fatalf("states = %v, want first %q", rec.states, StateConnecting)
	}
	if len(rec.logs) == 0 {
		t.Error("sink received no log lines")
	}
}

func TestDoubleStartRejected(t *testing.T) {
	t.Cleanup(Stop)
	if err := Start(testConfig); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := Start(testConfig); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("second Start() error = %v, want already running", err)
	}
}

func TestStopIsIdempotent(t *testing.T) {
	if err := Start(testConfig); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	Stop()
	Stop()
	if got := GetState().State; got != StateIdle {
		t.Fatalf("GetState().State = %q, want %q", got, StateIdle)
	}
}

func TestRestartReplacesSession(t *testing.T) {
	t.Cleanup(Stop)
	if err := Start(testConfig); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := Restart(testConfig, 0); err != nil {
		t.Fatalf("Restart() error = %v", err)
	}
	if got := GetState().State; got == StateIdle {
		t.Errorf("GetState().State = %q, want running session", got)
	}
}
