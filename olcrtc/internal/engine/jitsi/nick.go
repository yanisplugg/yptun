package jitsi

import "strings"

// cyrillicToLatin maps Cyrillic runes to their Latin transliteration strings.
var cyrillicToLatin = map[rune]string{ //nolint:gochecknoglobals // package-level lookup table
	'А': "A", 'а': "a", 'Б': "B", 'б': "b", 'В': "V", 'в': "v",
	'Г': "G", 'г': "g", 'Д': "D", 'д': "d", 'Е': "E", 'е': "e",
	'Ё': "Yo", 'ё': "yo", 'Ж': "Zh", 'ж': "zh", 'З': "Z", 'з': "z",
	'И': "I", 'и': "i", 'Й': "Y", 'й': "y", 'К': "K", 'к': "k",
	'Л': "L", 'л': "l", 'М': "M", 'м': "m", 'Н': "N", 'н': "n",
	'О': "O", 'о': "o", 'П': "P", 'п': "p", 'Р': "R", 'р': "r",
	'С': "S", 'с': "s", 'Т': "T", 'т': "t", 'У': "U", 'у': "u",
	'Ф': "F", 'ф': "f", 'Х': "Kh", 'х': "kh", 'Ц': "Ts", 'ц': "ts",
	'Ч': "Ch", 'ч': "ch", 'Ш': "Sh", 'ш': "sh", 'Щ': "Shch", 'щ': "shch",
	'Ъ': "", 'ъ': "", 'Ы': "Y", 'ы': "y", 'Ь': "", 'ь': "",
	'Э': "E", 'э': "e", 'Ю': "Yu", 'ю': "yu", 'Я': "Ya", 'я': "ya",
}

// sanitiseNick reduces a display name to a 7-bit ASCII slug acceptable to
// the j library's MUC presence helper. Cyrillic characters are transliterated,
// other non-ASCII characters are dropped, and punctuation becomes '-'.
func sanitiseNick(raw string) string {
	const maxNickLen = 16
	var b strings.Builder
	b.Grow(len(raw))
	prevDash := false
	for _, r := range raw {
		if b.Len() >= maxNickLen {
			break
		}
		if isNickRune(r) {
			_, _ = b.WriteRune(r)
			prevDash = false
			continue
		}
		if lat, ok := cyrillicToLatin[r]; ok {
			for _, lr := range lat {
				if b.Len() >= maxNickLen {
					break
				}
				_, _ = b.WriteRune(lr)
			}
			prevDash = false
			continue
		}
		if !prevDash && b.Len() > 0 {
			_, _ = b.WriteRune('-')
			prevDash = true
		}
	}
	return strings.Trim(b.String(), "-")
}

func isNickRune(r rune) bool {
	switch {
	case r >= 'a' && r <= 'z':
		return true
	case r >= 'A' && r <= 'Z':
		return true
	case r >= '0' && r <= '9':
		return true
	case r == '-' || r == '_':
		return true
	}
	return false
}

// normaliseHost strips an optional scheme and path from a Jitsi host.
func normaliseHost(raw string) string {
	raw = strings.TrimSpace(raw)
	if idx := strings.Index(raw, "://"); idx >= 0 {
		raw = raw[idx+3:]
	}
	raw = strings.TrimPrefix(raw, "//")
	raw = strings.TrimSuffix(raw, "/")
	if i := strings.Index(raw, "/"); i >= 0 {
		raw = raw[:i]
	}
	return raw
}
