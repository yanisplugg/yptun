package provision

import (
	"encoding/base64"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
)

// validChannelID is what node-install.sh accepts as a channel name.
var validChannelID = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,30}$`)

// A node's kit: the installation of one channel as a shell script a person runs
// on the machine that will host the node, for when the app has no SSH to it (a
// machine behind a firewall, one the person does not want to hand a password
// to, a server of a friend). It takes the same steps the wizard takes over SSH,
// and nothing else: download the pinned installer, check its SHA-256, ask it
// for a plan (the installer picks the port) and apply it - or, when the channel
// is on the machine already, set-transports, which keeps its key, port and
// context. The app made the key itself (NewKey), so it knows the client's side
// of the channel without ever seeing the machine.
//
// The script holds the channel's key and the accounts' tokens the node's
// transports need: it is a secret, to be deleted once it ran.

// KitCarrier is one carrier of the channel as the client's profile lists it:
// priority is the core's own for the type, so the app decides nothing here.
type KitCarrier struct {
	Type     string `json:"type"`
	URL      string `json:"url"`
	Priority int    `json:"priority"`
	// Script is the package id of a script carrier.
	Script string `json:"script,omitempty"`
}

// Kit is a channel's installation and what the client needs of it.
type Kit struct {
	// Script is the file to run as root: sudo sh openflux-node.sh.
	Script string `json:"script"`
	// Paste is the same as one block for a terminal: sudo sh <<'...' with the script inside.
	Paste string `json:"paste"`
	// Context is the encryption context both ends derive for these carriers (see SessionContext).
	Context string `json:"context"`
	// Transports are the carriers besides direct, best first.
	Transports []KitCarrier `json:"transports"`
	// Installer is the pinned script the kit downloads, for showing the person where the code comes from.
	Installer Script `json:"installer"`
}

// kitOptions is for tests only: the production path never skips the root check.
type kitOptions struct{ skipRootCheck bool }

// RenderKit renders ch as a Kit for the node-install.sh of the given source ("" the fork's, "official").
func RenderKit(ch Channel, source string) (*Kit, error) {
	installer, err := PinnedFor(source)
	if err != nil {
		return nil, err
	}
	return renderKit(ch, installer, kitOptions{})
}

// RenderKitFor is RenderKit for an installer the caller already chose (the wizard's own pin, or a test's).
func RenderKitFor(ch Channel, installer Script) (*Kit, error) {
	return renderKit(ch, installer, kitOptions{})
}

func renderKit(ch Channel, installer Script, o kitOptions) (*Kit, error) {
	if !validChannelID.MatchString(ch.ID) {
		return nil, errors.New("неверное имя канала")
	}
	if !hexKey.MatchString(ch.Key) {
		return nil, errors.New("ключ канала должен быть 64 hex-символа")
	}
	secure := strings.HasPrefix(installer.URL, "https://") || (allowPlainScriptURL && strings.HasPrefix(installer.URL, "http://127.0.0.1:"))
	if !secure || strings.ContainsAny(installer.URL, "'\"\\ \n") || !hexKey.MatchString(strings.ToLower(installer.SHA256)) {
		return nil, errors.New("неверный адрес скрипта")
	}
	ts, err := CheckTransports(ch.Transports)
	if err != nil {
		return nil, err
	}
	mode, err := CheckMode(ch.Mode)
	if err != nil {
		return nil, err
	}

	// The config the installer takes, without the secrets and the port (plan wants neither): the script adds
	// them when it applies.
	var base strings.Builder
	base.WriteString("channel=" + ch.ID + "\n")
	type pkg struct{ head, body string }
	var pkgs []pkg
	carriers := make([]KitCarrier, 0, len(ts))
	for _, t := range ts {
		carriers = append(carriers, KitCarrier{Type: t.Type, URL: t.URL, Priority: transportPriority[t.Type], Script: t.Script})
		if t.Type != "script" {
			base.WriteString(t.Type + "=" + t.URL + "\n")
			continue
		}
		if t.Package == "" {
			return nil, fmt.Errorf("JS-транспорту %s нужен пакет: на новой машине его неоткуда взять", t.Script)
		}
		pkgs = append(pkgs, pkg{
			head: t.Script + "," + t.Pubkey + "," + base64.RawURLEncoding.EncodeToString([]byte(t.URL)) + "," + t.Params,
			body: t.Package,
		})
	}
	if mode == ModeL3 {
		base.WriteString("mode=l3\n")
	}
	if ch.AutoUpdate {
		base.WriteString("autoupdate=yes\n")
	} else {
		base.WriteString("autoupdate=no\n")
	}

	var b strings.Builder
	w := func(s string) { b.WriteString(s) }
	w("#!/bin/sh\n")
	w("# OpenFlux node, channel " + ch.ID + ". Made by the OpenFlux app for the machine that will run the node.\n")
	w("# It holds the channel's key and the tokens of the node's accounts: keep it to yourself, delete it after it ran.\n")
	w("# Run it as root: sudo sh openflux-node.sh\n")
	w("set -eu\numask 077\n")
	if !o.skipRootCheck {
		w("[ \"$(id -u)\" = 0 ] || { echo 'Запустите от root: sudo sh openflux-node.sh' >&2; exit 1; }\n")
	}
	w("work=$(mktemp -d /tmp/openflux-kit.XXXXXX)\n")
	w("trap 'rm -rf \"$work\"' EXIT\n")
	w("inst=\"$work/node-install.sh\"\n")
	w("url=" + shellQuote(installer.URL) + "\n")
	w("want=" + shellQuote(strings.ToLower(installer.SHA256)) + "\n")
	w("echo 'Скачиваю установщик ноды (закреплённая версия)…'\n")
	w("if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 --connect-timeout 20 -o \"$inst\" \"$url\" </dev/null\n")
	w("elif command -v wget >/dev/null 2>&1; then wget -q -T 20 -t 3 -O \"$inst\" \"$url\" </dev/null\n")
	w("else echo 'Нужен curl или wget' >&2; exit 3; fi\n")
	w("if command -v sha256sum >/dev/null 2>&1; then got=$(sha256sum \"$inst\" | cut -d' ' -f1); else got=$(openssl dgst -sha256 -r \"$inst\" | cut -d' ' -f1); fi\n")
	w("[ \"$got\" = \"$want\" ] || { echo 'Установщик отличается от проверенного, установка остановлена' >&2; exit 1; }\n")

	w("cat > \"$work/node.conf\" <<'OPENFLUX_NODE_CONF'\n")
	w(base.String())
	w("OPENFLUX_NODE_CONF\n")
	// A package is one long base64 line in the installer's config: sent as short lines so it survives a
	// terminal (a tty line holds 4095 bytes), and put together here.
	for i, p := range pkgs {
		delim := fmt.Sprintf("OPENFLUX_NODE_PKG_%d", i+1)
		w("tr -d '\\n' > \"$work/pkg" + fmt.Sprint(i+1) + "\" <<'" + delim + "'\n")
		for len(p.body) > 0 {
			n := 76
			if n > len(p.body) {
				n = len(p.body)
			}
			w(p.body[:n] + "\n")
			p.body = p.body[n:]
		}
		w(delim + "\n")
		w("printf 'script=%s,%s\\n' " + shellQuote(p.head) + " \"$(cat \"$work/pkg" + fmt.Sprint(i+1) + "\")\" >> \"$work/node.conf\"\n")
	}

	// Already here: only the transports change (key, port and context stay). New: the installer's plan picks the
	// port, then the key and the port go in and it is applied.
	w("fail() { printf '%s\\n' \"$1\" >&2; exit 1; }\n")
	w("ok() { case \"$1\" in *'\"ok\":true'*) ;; *) fail \"$1\" ;; esac; }\n")
	w("have=$(sh \"$inst\" list </dev/null 2>/dev/null || true)\n")
	w("case \"$have\" in *'\"channel\":\"" + ch.ID + "\"'*)\n")
	w("  echo 'Канал " + ch.ID + " уже есть на этой машине: меняю его транспорты…'\n")
	w("  out=$(sh \"$inst\" set-transports \"$work/node.conf\" </dev/null); ok \"$out\"\n")
	w("  echo 'Готово.'; exit 0 ;;\nesac\n")
	w("plan=$(sh \"$inst\" plan < \"$work/node.conf\"); ok \"$plan\"\n")
	w("port=$(printf '%s' \"$plan\" | sed -n 's/.*\"port\":\\([0-9][0-9]*\\).*/\\1/p')\n")
	w("[ -n \"$port\" ] || fail 'установщик не назвал порт'\n")
	w("printf 'port=%s\\nkey=%s\\n' \"$port\" " + shellQuote(ch.Key) + " >> \"$work/node.conf\"\n")
	w("echo 'Устанавливаю ноду…'\n")
	w("out=$(sh \"$inst\" apply \"$work/node.conf\" </dev/null); ok \"$out\"\n")
	w("echo \"Готово: нода " + ch.ID + " работает, прямой порт $port. Удалите этот файл: в нём секреты.\"\n")
	script := b.String()

	delimiter := "OPENFLUX_NODE_KIT"
	paste := "sudo sh <<'" + delimiter + "'\n" + script + delimiter + "\n"
	sort.SliceStable(carriers, func(i, j int) bool { return carriers[i].Priority > carriers[j].Priority })
	return &Kit{Script: script, Paste: paste, Context: SessionContext(ts), Transports: carriers, Installer: installer}, nil
}
