package captcha

// Телеметрия captcha.
//
// Виджет заводит setInterval(sensors_delay) и на каждом тике кладёт в аккумулятор
// navigator.connection.rtt/downlink. Акселерометр пишется отдельным потоком
// событий Generic Sensor API с частотой 1000/sensors_delay.
//
// Указатель (cursor - мышь, taps - палец) пишется только через externalCoords, а
// их заполняет либо нативный бридж мобильного SDK, либо хост-страница, если
// виджет открыт в iframe. Мы ходим на captcha прямой навигацией, без хоста, -
// в этом режиме виджет живого браузера оставляет оба массива пустыми.
//
// В captchaNotRobot.check уходят все семь массивов, даже пустые: значения,
// которых браузер не дал, виджет просто не кладёт в массив, но сам ключ
// отправляет всегда. Отсутствие ключа - то, чего живой браузер сделать не может.

import (
	"encoding/json"
	"math"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
	"github.com/samosvalishe/free-turn-proxy/internal/randx"
)

const (
	sensorDelayDefault = 100 * time.Millisecond
	sensorDelayFloor   = 20 * time.Millisecond
	sensorDelayCeil    = 2 * time.Second
	// sensorTicksMax ограничивает размер выборки: виджет режет аккумулятор по
	// 900 КБ, до такого объёма наши сессии не доживают.
	sensorTicksMax = 600
)

const (
	sensorAccelerometer = "accelerometer"
	sensorCursor        = "cursor"
	sensorTaps          = "taps"
)

// sensorConfig - что и как часто слушать; приходит из captchaNotRobot.settings.
type sensorConfig struct {
	delay         time.Duration
	cursor        bool
	taps          bool
	accelerometer bool
}

func defaultSensorConfig() sensorConfig {
	return sensorConfig{delay: sensorDelayDefault, cursor: true, taps: true, accelerometer: true}
}

func parseSensorConfig(raw map[string]any) sensorConfig {
	cfg := defaultSensorConfig()
	resp, ok := raw["response"].(map[string]any)
	if !ok {
		return cfg
	}
	if ms, hasDelay := resp["sensors_delay"].(float64); hasDelay && ms > 0 {
		cfg.delay = min(max(time.Duration(ms)*time.Millisecond, sensorDelayFloor), sensorDelayCeil)
	}
	list, ok := resp["bridge_sensors_list"].([]any)
	if !ok {
		return cfg
	}
	cfg.cursor, cfg.taps, cfg.accelerometer = false, false, false
	for _, item := range list {
		switch name, _ := item.(string); name {
		case sensorCursor:
			cfg.cursor = true
		case sensorTaps:
			cfg.taps = true
		case sensorAccelerometer:
			cfg.accelerometer = true
		}
	}
	return cfg
}

type point struct {
	X int `json:"x"`
	Y int `json:"y"`
}

type vec3 struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
	Z float64 `json:"z"`
}

// analytics - аккумулятор виджета целиком.
type analytics struct {
	accelerometer []vec3
	gyroscope     []vec3
	motion        []vec3
	cursor        []point
	taps          []point
	connRtt       []int
	connDownlink  []float64
}

// fields отдаёт массивы в порядке ключей аккумулятора.
func (a analytics) fields() [][2]string {
	return [][2]string{
		{sensorAccelerometer, jsonArray(a.accelerometer)},
		{"gyroscope", jsonArray(a.gyroscope)},
		{"motion", jsonArray(a.motion)},
		{sensorCursor, jsonArray(a.cursor)},
		{sensorTaps, jsonArray(a.taps)},
		{"connectionRtt", jsonArray(a.connRtt)},
		{"connectionDownlink", jsonArray(a.connDownlink)},
	}
}

func jsonArray[T any](values []T) string {
	if len(values) == 0 {
		return "[]"
	}
	data, err := json.Marshal(values)
	if err != nil {
		return "[]"
	}
	return string(data)
}

// gesture - движение указателя, показанное виджету перед check.
type gesture struct {
	from   point
	to     point
	move   time.Duration // само перетаскивание/подведение
	settle time.Duration // пауза в конечной точке до отправки check
}

// buildAnalytics собирает аккумулятор так, как его собрал бы живой браузер за
// elapsed с момента ответа settings: тиков ровно столько, сколько успел сделать
// таймер, а непокрытые персоной сенсоры остаются пустыми.
func buildAnalytics(p browserprofile.Profile, cfg sensorConfig, elapsed time.Duration) analytics {
	ticks := ticksIn(elapsed, cfg.delay)
	if ticks == 0 {
		// Таймер не успел тикнуть ни разу - живой виджет отправил бы пустые массивы.
		return analytics{}
	}
	out := analytics{}
	out.connRtt, out.connDownlink = sampleConnection(p.IsMobile(), ticks)
	if p.Accelerometer() && cfg.accelerometer {
		out.accelerometer = sampleAccelerometer(ticks)
	}
	return out
}

func ticksIn(d time.Duration, delay time.Duration) int {
	if d <= 0 || delay <= 0 {
		return 0
	}
	return min(int(d/delay), sensorTicksMax)
}

// sampleConnection повторяет NetworkInformation: Chrome огрубляет rtt до кратных
// 25 мс и downlink до шага 0.05 с потолком 10 Мбит/с, а между тиками оценка
// меняется редко.
func sampleConnection(mobile bool, ticks int) ([]int, []float64) {
	if ticks <= 0 {
		return nil, nil
	}
	rtt := 25 * (2 + randx.Intn(5)) // 50..150
	downlink := 5.0 + randx.Float64()*5.0
	if mobile {
		rtt = 25 * (2 + randx.Intn(3)) // 50..100
		downlink = 1.4 + randx.Float64()*0.7
	}
	rttOut := make([]int, 0, ticks)
	downlinkOut := make([]float64, 0, ticks)
	for range ticks {
		if randx.Intn(12) == 0 {
			rtt = max(25, rtt+25*(randx.Intn(3)-1))
			downlink = math.Max(0.05, downlink+(randx.Float64()-0.5))
		}
		rttOut = append(rttOut, rtt)
		downlinkOut = append(downlinkOut, quantizeDownlink(downlink))
	}
	return rttOut, downlinkOut
}

func quantizeDownlink(v float64) float64 {
	return math.Min(10, math.Round(v/0.05)*0.05)
}

// sampleAccelerometer генерирует показания покоящегося телефона: вектор длиной g
// в наклонённой системе координат плюс шум сенсора.
func sampleAccelerometer(count int) []vec3 {
	if count <= 0 {
		return nil
	}
	const g = 9.81
	bx := -2.5 + randx.Float64()*5.0
	by := 4.0 + randx.Float64()*4.0
	bz := g
	if rem := g*g - bx*bx - by*by; rem > 0 {
		bz = math.Sqrt(rem)
	}
	jitter := func() float64 { return (randx.Float64() - 0.5) * 0.2 }
	out := make([]vec3, 0, count)
	for range count {
		out = append(out, vec3{
			X: round1(bx + jitter()),
			Y: round1(by + jitter()),
			Z: round1(bz + jitter()),
		})
	}
	return out
}

func round1(v float64) float64 { return math.Round(v*10) / 10 }

// widgetLayout - геометрия виджета в координатах страницы. Виджет центрируется в
// viewport персоны, поэтому координаты указателя обязаны лежать внутри него.
type widgetLayout struct {
	center      point
	sliderY     int
	sliderLeft  int
	sliderRight int
	checkbox    point
}

func layoutFor(p browserprofile.Profile) widgetLayout {
	w, h := p.Viewport.W, p.Viewport.H
	center := point{X: w / 2, Y: h / 2}
	track := min(280, w-80)
	return widgetLayout{
		center:      center,
		sliderY:     center.Y + min(150, h/4),
		sliderLeft:  center.X - track/2,
		sliderRight: center.X + track/2,
		checkbox:    point{X: center.X - min(110, w/4), Y: center.Y},
	}
}

// sliderTarget - позиция ручки для кандидата index из count.
func (l widgetLayout) sliderTarget(index, count int) point {
	if count < 2 {
		count = 2
	}
	index = min(max(index, 1), count)
	span := l.sliderRight - l.sliderLeft
	x := l.sliderLeft + span*(index-1)/(count-1)
	return point{X: x + randx.Intn(7) - 3, Y: l.sliderY + randx.Intn(7) - 3}
}

// approachFrom - откуда указатель приходит к цели: мышь появляется в стороне,
// палец касается почти вплотную.
func approachFrom(p browserprofile.Profile, l widgetLayout, target point) point {
	if p.Touch() {
		return point{X: l.sliderLeft + randx.Intn(24) - 12, Y: target.Y + randx.Intn(16) - 8}
	}
	return point{
		X: min(max(l.center.X+randx.Intn(320)-160, 8), p.Viewport.W-8),
		Y: min(max(l.center.Y+randx.Intn(240)-120, 8), p.Viewport.H-8),
	}
}
