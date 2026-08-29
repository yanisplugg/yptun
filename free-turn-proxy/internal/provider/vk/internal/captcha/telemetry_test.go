package captcha

import (
	"encoding/json"
	"testing"
	"time"
)

// Эталон снят с успешного check живого браузера через manual-прокси: непустой
// там ровно один ключ.
func TestAnalyticsMatchesLiveBrowser(t *testing.T) {
	want := [][2]string{
		{"accelerometer", "[]"},
		{"gyroscope", "[]"},
		{"motion", "[]"},
		{"cursor", "[]"},
		{"taps", "[]"},
		{"connectionRtt", "[]"},
		{"connectionDownlink", "[10,10,10]"},
	}
	cfg := sensorConfig{delay: 200 * time.Millisecond}
	got := buildAnalytics(cfg, 10, 600*time.Millisecond).fields()

	if len(got) != len(want) {
		t.Fatalf("%d fields, want %d", len(got), len(want))
	}
	for i, kv := range got {
		if kv != want[i] {
			t.Fatalf("field %d = %v, want %v", i, kv, want[i])
		}
	}
}

// Число сэмплов задаёт таймер виджета: сколько тиков уместилось в реальное время
// сессии, столько и точек.
func TestAnalyticsSampleCountFollowsElapsed(t *testing.T) {
	cfg := sensorConfig{delay: 100 * time.Millisecond}
	elapsed := 5 * time.Second

	if got := len(buildAnalytics(cfg, 10, elapsed).connDownlink); got != int(elapsed/cfg.delay) {
		t.Fatalf("downlink = %d, want %d", got, int(elapsed/cfg.delay))
	}
}

// Сессия короче одного тика: массивы пустые, но ключи на месте.
func TestAnalyticsWithoutTicksIsEmpty(t *testing.T) {
	cfg := sensorConfig{delay: 2 * time.Second}
	for _, kv := range buildAnalytics(cfg, 10, 500*time.Millisecond).fields() {
		if kv[1] != emptyArray {
			t.Fatalf("field %q = %s, want []", kv[0], kv[1])
		}
	}
}

func TestParseSensorConfigFallsBackToDefaults(t *testing.T) {
	cfg := parseSensorConfig(map[string]any{"error": map[string]any{"error_code": float64(5)}})
	if cfg != defaultSensorConfig() {
		t.Fatalf("sensor config = %+v, want defaults", cfg)
	}
	clamped := parseSensorConfig(map[string]any{"response": map[string]any{"sensors_delay": float64(1)}})
	if clamped.delay != sensorDelayFloor {
		t.Fatalf("delay = %s, want clamp to %s", clamped.delay, sensorDelayFloor)
	}
}

func TestParseSensorConfigReadsDelay(t *testing.T) {
	cfg := parseSensorConfig(map[string]any{"response": map[string]any{"sensors_delay": float64(250)}})
	if cfg.delay != 250*time.Millisecond {
		t.Fatalf("delay = %s, want 250ms", cfg.delay)
	}
}

// Оценка не меняется за время captcha: замеры дали [10,10,10] и [9.6,9.6,9.6].
func TestDownlinkIsConstantWithinSession(t *testing.T) {
	for _, v := range sampleDownlink(9.6, 16) {
		if v != 9.6 {
			t.Fatalf("downlink = %v, want 9.6", v)
		}
	}
}

// JSON.stringify печатает число кратчайшей записью; 9.200000000000001 в выдаче
// живого браузера невозможен и сам был бы маркером.
func TestSessionDownlinkSerializesShort(t *testing.T) {
	for range 500 {
		v := sessionDownlink()
		if v < 8 || v > 10 {
			t.Fatalf("downlink = %v, want [8, 10]", v)
		}
		raw, err := json.Marshal(v)
		if err != nil {
			t.Fatal(err)
		}
		if len(raw) > 4 {
			t.Fatalf("downlink serialized as %s, want at most 4 chars", raw)
		}
	}
}

func TestDownlinkArray(t *testing.T) {
	if got := downlinkArray(nil); got != emptyArray {
		t.Fatalf("downlinkArray(nil) = %q, want []", got)
	}
	if got := downlinkArray([]float64{10, 9.6}); got != "[10,9.6]" {
		t.Fatalf("downlinkArray = %s, want [10,9.6]", got)
	}
}
