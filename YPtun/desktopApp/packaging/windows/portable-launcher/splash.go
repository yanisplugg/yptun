package main

import (
	"bytes"
	_ "embed"
	"image"
	"image/png"
	"math"
	"runtime"
	"syscall"
	"time"
	"unsafe"
)

// First-launch splash: a borderless, rounded, per-pixel-alpha window with the app icon and a
// progress pill. It replaces the stock Windows progress bar. Everything is drawn in Go into a
// 32-bit DIB and pushed with UpdateLayeredWindow, so there is no WndProc, no GDI+ and no cgo; GDI is
// used only for the one line of text (Go's stdlib has no font rasteriser).

//go:embed splash.png
var splashPNG []byte

var (
	gdi32                = syscall.NewLazyDLL("gdi32.dll")
	registerClassExW     = user32.NewProc("RegisterClassExW")
	defWindowProcW       = user32.NewProc("DefWindowProcW")
	updateLayeredWindow  = user32.NewProc("UpdateLayeredWindow")
	setProcessDPIAware   = user32.NewProc("SetProcessDPIAware")
	getDpiForSystem      = user32.NewProc("GetDpiForSystem")
	drawTextW            = user32.NewProc("DrawTextW")
	getUserDefaultUILang = kernel32.NewProc("GetUserDefaultUILanguage")
	createCompatibleDC   = gdi32.NewProc("CreateCompatibleDC")
	createDIBSection     = gdi32.NewProc("CreateDIBSection")
	selectObject         = gdi32.NewProc("SelectObject")
	deleteObject         = gdi32.NewProc("DeleteObject")
	deleteDC             = gdi32.NewProc("DeleteDC")
	setBkMode            = gdi32.NewProc("SetBkMode")
	setTextColor         = gdi32.NewProc("SetTextColor")
	createFontW          = gdi32.NewProc("CreateFontW")
)

type wndClassEx struct {
	size       uint32
	style      uint32
	wndProc    uintptr
	clsExtra   int32
	wndExtra   int32
	instance   uintptr
	icon       uintptr
	cursor     uintptr
	background uintptr
	menuName   *uint16
	className  *uint16
	iconSmall  uintptr
}

type bitmapInfoHeader struct {
	size          uint32
	width, height int32
	planes, bpp   uint16
	compression   uint32
	sizeImage     uint32
	xppm, yppm    int32
	clrUsed, clrI uint32
}

// progressBar is the handle the unpacker talks to; it only ever sends numbers over a channel.
type progressBar struct {
	updates chan int
	done    chan struct{}
}

func showProgress(total int) *progressBar {
	p := &progressBar{}
	if total <= 0 {
		return p
	}
	p.updates = make(chan int, 64)
	p.done = make(chan struct{})
	ready := make(chan struct{})
	go p.run(total, ready)
	<-ready
	return p
}

// run owns the window: all Win32 calls happen on this one locked thread (a window belongs to the
// thread that created it, and SendMessage/UpdateLayeredWindow from another one can deadlock on a
// pump that never runs), while the extraction goroutines only send numbers.
func (p *progressBar) run(total int, ready chan struct{}) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	defer close(p.done)

	s := newSplash()
	close(ready)
	if s != nil {
		defer s.destroy()
		s.draw(0)
	}

	var msg [48]byte // MSG on amd64/arm64; its fields are never read
	last := 0
	for {
		select {
		case done, ok := <-p.updates:
			if !ok {
				return
			}
			last = done
		case <-time.After(30 * time.Millisecond):
		}
	drain:
		for { // a burst of ticks must cost one redraw, not one each
			select {
			case done, ok := <-p.updates:
				if !ok {
					return
				}
				last = done
			default:
				break drain
			}
		}
		if s == nil {
			continue
		}
		s.draw(float64(last) / float64(total))
		for {
			got, _, _ := peekMessageW.Call(uintptr(unsafe.Pointer(&msg[0])), 0, 0, 0, pmRemove)
			if got == 0 {
				break
			}
			translateMessage.Call(uintptr(unsafe.Pointer(&msg[0])))
			dispatchMessageW.Call(uintptr(unsafe.Pointer(&msg[0])))
		}
	}
}

func (p *progressBar) set(done int) {
	if p.updates == nil {
		return
	}
	select {
	case p.updates <- done:
	default: // the window is behind; dropping a tick is better than slowing the unpack
	}
}

func (p *progressBar) close() {
	if p.updates == nil {
		return
	}
	close(p.updates)
	<-p.done
	p.updates = nil
}

// ---------------------------------------------------------------------------------------------

type splash struct {
	hwnd, memDC, bmp, oldBmp uintptr
	bits                     []byte // BGRA premultiplied, top-down, w*h*4
	base                     []byte // everything except the progress fill
	w, h                     int
	k                        float64 // DPI scale
	barX, barY, barW, barH   int
	px, py                   int32
}

func newSplash() *splash {
	setProcessDPIAware.Call()
	k := 1.0
	if getDpiForSystem.Find() == nil {
		if dpi, _, _ := getDpiForSystem.Call(); dpi >= 96 {
			k = float64(dpi) / 96
		}
	}
	sc := func(v float64) int { return int(math.Round(v * k)) }
	s := &splash{k: k, w: sc(360), h: sc(330)}
	s.barW, s.barH = sc(250), sc(8)
	s.barX, s.barY = (s.w-s.barW)/2, sc(268)

	screenW, _, _ := getSystemMetrics.Call(smCxScreen)
	screenH, _, _ := getSystemMetrics.Call(smCyScreen)
	s.px, s.py = int32((int(screenW)-s.w)/2), int32((int(screenH)-s.h)/2)

	name, _ := syscall.UTF16PtrFromString("YPtunSplash")
	cls := wndClassEx{wndProc: defWindowProcW.Addr(), className: name}
	cls.size = uint32(unsafe.Sizeof(cls))
	registerClassExW.Call(uintptr(unsafe.Pointer(&cls)))
	hwnd, _, _ := createWindowExW.Call(
		wsExLayered|wsExToolWin|wsExTopmost,
		uintptr(unsafe.Pointer(name)), uintptr(unsafe.Pointer(name)),
		wsPopup|wsVisible,
		uintptr(s.px), uintptr(s.py), uintptr(s.w), uintptr(s.h),
		0, 0, 0, 0,
	)
	if hwnd == 0 {
		return nil
	}
	s.hwnd = hwnd

	memDC, _, _ := createCompatibleDC.Call(0)
	hdr := bitmapInfoHeader{size: 40, width: int32(s.w), height: -int32(s.h), planes: 1, bpp: 32}
	var bitsPtr unsafe.Pointer
	bmp, _, _ := createDIBSection.Call(memDC, uintptr(unsafe.Pointer(&hdr)), 0, uintptr(unsafe.Pointer(&bitsPtr)), 0, 0)
	if memDC == 0 || bmp == 0 || bitsPtr == nil {
		destroyWindow.Call(hwnd)
		return nil
	}
	s.memDC, s.bmp = memDC, bmp
	s.oldBmp, _, _ = selectObject.Call(memDC, bmp)
	s.bits = unsafe.Slice((*byte)(bitsPtr), s.w*s.h*4)

	s.renderBase()
	return s
}

func (s *splash) destroy() {
	if s.hwnd != 0 {
		destroyWindow.Call(s.hwnd)
	}
	if s.memDC != 0 {
		selectObject.Call(s.memDC, s.oldBmp)
		deleteObject.Call(s.bmp)
		deleteDC.Call(s.memDC)
	}
}

func clamp01(v float64) float64 { return math.Max(0, math.Min(1, v)) }

func mix(a, b, t float64) float64 { return a + (b-a)*t }

// rrCoverage is the antialiased coverage of a rounded rectangle [0,w]x[0,h] with radius r at (x,y).
func rrCoverage(x, y, w, h, r float64) float64 {
	dx := math.Abs(x-w/2) - (w/2 - r)
	dy := math.Abs(y-h/2) - (h/2 - r)
	d := math.Hypot(math.Max(dx, 0), math.Max(dy, 0)) + math.Min(math.Max(dx, dy), 0) - r
	return clamp01(0.5 - d)
}

// renderBase paints the card, the glow, the icon and the caption into s.base.
func (s *splash) renderBase() {
	w, h, k := s.w, s.h, s.k
	buf := make([]byte, w*h*4)
	radius := 28 * k
	cx, cy := float64(w)/2, 128*k // glow centre = icon centre
	glowR := 150 * k
	for y := 0; y < h; y++ {
		t := float64(y) / float64(h)
		for x := 0; x < w; x++ {
			cov := rrCoverage(float64(x)+0.5, float64(y)+0.5, float64(w), float64(h), radius)
			if cov == 0 {
				continue
			}
			// vertical gradient card, a violet glow behind the icon, a 1px lighter rim
			r, g, b := mix(27, 15, t), mix(27, 15, t), mix(35, 20, t)
			gl := clamp01(1-math.Hypot(float64(x)-cx, float64(y)-cy)/glowR) * 0.22
			r, g, b = mix(r, 139, gl), mix(g, 92, gl), mix(b, 246, gl)
			inner := rrCoverage(float64(x)+0.5-k, float64(y)+0.5-k, float64(w)-2*k, float64(h)-2*k, radius-k)
			rim := 1 - inner
			r, g, b = mix(r, 64, rim), mix(g, 64, rim), mix(b, 78, rim)
			i := (y*w + x) * 4
			buf[i], buf[i+1], buf[i+2], buf[i+3] = byte(b*cov), byte(g*cov), byte(r*cov), byte(cov*255)
		}
	}
	s.drawIcon(buf)
	copy(s.bits, buf)
	s.drawCaption()
	// GDI text zeroes the alpha byte of the pixels it touches; the card interior is opaque, so
	// restore it from the pre-text copy.
	for i := 3; i < len(buf); i += 4 {
		if buf[i] == 255 {
			s.bits[i] = 255
		}
	}
	s.base = append([]byte(nil), s.bits...)
}

// drawIcon alpha-composites the embedded icon, bilinear-scaled, centred near the top of the card.
func (s *splash) drawIcon(buf []byte) {
	src, err := png.Decode(bytes.NewReader(splashPNG))
	if err != nil {
		return
	}
	nr, ok := src.(*image.NRGBA)
	if !ok {
		return
	}
	size := int(math.Round(150 * s.k))
	ox, oy := (s.w-size)/2, int(math.Round(53*s.k))
	sb := nr.Bounds()
	sw, sh := float64(sb.Dx()), float64(sb.Dy())
	// premultiplied sample, so filtering never bleeds the colour of transparent pixels
	at := func(x, y int) (r, g, b, a float64) {
		x = max(0, min(sb.Dx()-1, x))
		y = max(0, min(sb.Dy()-1, y))
		c := nr.NRGBAAt(sb.Min.X+x, sb.Min.Y+y)
		a = float64(c.A) / 255
		return float64(c.R) * a, float64(c.G) * a, float64(c.B) * a, a
	}
	for y := 0; y < size; y++ {
		fy := (float64(y)+0.5)*sh/float64(size) - 0.5
		y0 := int(math.Floor(fy))
		ty := fy - float64(y0)
		for x := 0; x < size; x++ {
			fx := (float64(x)+0.5)*sw/float64(size) - 0.5
			x0 := int(math.Floor(fx))
			tx := fx - float64(x0)
			var r, g, b, a float64
			for dy := 0; dy < 2; dy++ {
				for dx := 0; dx < 2; dx++ {
					wgt := (1 - math.Abs(float64(dx)-tx)) * (1 - math.Abs(float64(dy)-ty))
					pr, pg, pb, pa := at(x0+dx, y0+dy)
					r, g, b, a = r+pr*wgt, g+pg*wgt, b+pb*wgt, a+pa*wgt
				}
			}
			i := ((oy+y)*s.w + ox + x) * 4
			if i < 0 || i+3 >= len(buf) {
				continue
			}
			inv := 1 - a // source-over onto the premultiplied card
			buf[i] = byte(math.Min(255, b+float64(buf[i])*inv))
			buf[i+1] = byte(math.Min(255, g+float64(buf[i+1])*inv))
			buf[i+2] = byte(math.Min(255, r+float64(buf[i+2])*inv))
			buf[i+3] = byte(math.Min(255, a*255+float64(buf[i+3])*inv))
		}
	}
}

func (s *splash) drawCaption() {
	text := "First launch — unpacking…"
	lang, _, _ := getUserDefaultUILang.Call()
	if p := lang & 0x3ff; p == 0x19 || p == 0x22 || p == 0x23 { // ru / uk / be
		text = "Первый запуск — распаковка…"
	}
	face, _ := syscall.UTF16PtrFromString("Segoe UI")
	height := -int32(math.Round(16 * s.k)) // negative = character height in pixels
	font, _, _ := createFontW.Call(
		uintptr(int64(height)), 0, 0, 0, 600, 0, 0, 0, 1, 0, 0, 4, 0,
		uintptr(unsafe.Pointer(face)),
	)
	old, _, _ := selectObject.Call(s.memDC, font)
	setBkMode.Call(s.memDC, 1)           // TRANSPARENT
	setTextColor.Call(s.memDC, 0xD6CFC9) // COLORREF is 0x00BBGGRR, i.e. #C9CFD6
	rect := [4]int32{0, int32(s.barY) - int32(38*s.k), int32(s.w), int32(s.barY) - int32(10*s.k)}
	utf := syscall.StringToUTF16Ptr(text)
	// DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX
	drawTextW.Call(s.memDC, uintptr(unsafe.Pointer(utf)), ^uintptr(0), uintptr(unsafe.Pointer(&rect[0])), 0x1|0x4|0x20|0x800)
	selectObject.Call(s.memDC, old)
	deleteObject.Call(font)
}

// draw composes base + progress pill (fraction 0..1) and pushes the frame to the screen.
func (s *splash) draw(fraction float64) {
	copy(s.bits, s.base)
	fraction = clamp01(fraction)
	bw, bh := float64(s.barW), float64(s.barH)
	fillTo := bw * fraction
	for y := 0; y < s.barH; y++ {
		for x := 0; x < s.barW; x++ {
			cov := rrCoverage(float64(x)+0.5, float64(y)+0.5, bw, bh, bh/2)
			if cov == 0 {
				continue
			}
			// grey track, then a violet -> blue fill clipped to the progress with a soft end
			r, g, b := 44.0, 44.0, 54.0
			if f := clamp01(fillTo - float64(x)); f > 0 {
				t := float64(x) / bw
				r, g, b = mix(r, mix(139, 99, t), f), mix(g, mix(92, 130, t), f), mix(b, 246, f)
			}
			i := ((s.barY+y)*s.w + s.barX + x) * 4
			inv := 1 - cov
			s.bits[i] = byte(b*cov + float64(s.bits[i])*inv)
			s.bits[i+1] = byte(g*cov + float64(s.bits[i+1])*inv)
			s.bits[i+2] = byte(r*cov + float64(s.bits[i+2])*inv)
		}
	}
	pt := [2]int32{s.px, s.py}
	size := [2]int32{int32(s.w), int32(s.h)}
	src := [2]int32{0, 0}
	blend := uint32(255)<<16 | uint32(1)<<24 // AC_SRC_OVER, flags 0, SourceConstantAlpha 255, AC_SRC_ALPHA
	updateLayeredWindow.Call(s.hwnd, 0, uintptr(unsafe.Pointer(&pt[0])), uintptr(unsafe.Pointer(&size[0])),
		s.memDC, uintptr(unsafe.Pointer(&src[0])), 0, uintptr(unsafe.Pointer(&blend)), 2)
}
