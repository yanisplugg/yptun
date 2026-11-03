// Package route decides which destinations a client dials directly instead
// of through the tunnel: a set of names and address prefixes parsed from
// text, one rule per line, in the syntax Xray's lists use so an app can hand
// the same file to either engine.
//
// ai-generated: the whole package (olcbox#28).
package route

import (
	"errors"
	"fmt"
	"net/netip"
	"slices"
	"strings"
)

// ErrRule reports a line that is not a rule. The error names the line.
var ErrRule = errors.New("route: bad rule")

const (
	prefixDomain = "domain:"
	prefixFull   = "full:"
	commentMark  = "#"
)

// Rules is what Parse builds: exact names, name suffixes and merged address
// ranges. A nil *Rules matches nothing, which is how "off" is spelled.
type Rules struct {
	full     map[string]struct{}
	suffix   map[string]struct{}
	ranges   []addrRange
	names    int
	prefixes int
}

// addrRange is a closed interval of addresses of one family.
type addrRange struct {
	lo, hi netip.Addr
}

// Parse reads one rule per line: `domain:<name>` for a name and everything
// under it, `full:<name>` for that name only, an address or a prefix in CIDR
// form for addresses. Blank lines and lines starting with # are skipped; any
// other line is an error naming its number. A text with no rules yields nil.
func Parse(text string) (*Rules, error) {
	r := &Rules{full: map[string]struct{}{}, suffix: map[string]struct{}{}}
	for n, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, commentMark) {
			continue
		}
		if err := r.add(line); err != nil {
			return nil, fmt.Errorf("%w: line %d: %q: %w", ErrRule, n+1, line, err)
		}
	}
	if r.names == 0 && r.prefixes == 0 {
		return nil, nil //nolint:nilnil // no rules is a valid answer, not a failure
	}
	r.ranges = mergeRanges(r.ranges)
	return r, nil
}

var (
	errEmptyName = errors.New("empty name")
	errNameSpace = errors.New("name contains whitespace")
	errNotARule  = errors.New("not a name rule, an address or a prefix")
)

func (r *Rules) add(line string) error {
	switch {
	case strings.HasPrefix(line, prefixDomain):
		return r.addName(r.suffix, strings.TrimPrefix(line, prefixDomain))
	case strings.HasPrefix(line, prefixFull):
		return r.addName(r.full, strings.TrimPrefix(line, prefixFull))
	}
	if prefix, err := netip.ParsePrefix(line); err == nil {
		r.addRange(prefix.Masked().Addr(), lastAddr(prefix))
		return nil
	}
	if addr, err := netip.ParseAddr(line); err == nil {
		addr = addr.WithZone("")
		r.addRange(addr, addr)
		return nil
	}
	return errNotARule
}

func (r *Rules) addName(set map[string]struct{}, raw string) error {
	name := normalizeName(raw)
	if name == "" {
		return errEmptyName
	}
	if strings.ContainsFunc(name, isSpace) {
		return errNameSpace
	}
	set[name] = struct{}{}
	r.names++
	return nil
}

func (r *Rules) addRange(lo, hi netip.Addr) {
	r.ranges = append(r.ranges, addrRange{lo: lo, hi: hi})
	r.prefixes++
}

func isSpace(c rune) bool { return c == ' ' || c == '\t' }

// normalizeName lowercases a name and drops the trailing dot; a name that is
// already clean comes back as the same string, no allocation.
func normalizeName(name string) string {
	return strings.ToLower(strings.TrimSuffix(strings.TrimSpace(name), "."))
}

// lastAddr is the highest address of a prefix.
func lastAddr(p netip.Prefix) netip.Addr {
	lo := p.Masked().Addr()
	if lo.Is4() {
		a := lo.As4()
		fillHostBits(a[:], p.Bits())
		return netip.AddrFrom4(a)
	}
	a := lo.As16()
	fillHostBits(a[:], p.Bits())
	return netip.AddrFrom16(a)
}

func fillHostBits(b []byte, bits int) {
	for i := bits; i < len(b)*8; i++ {
		b[i/8] |= 0x80 >> (i % 8)
	}
}

// mergeRanges sorts by the low end and folds overlapping and adjacent
// ranges, so a lookup is one binary search over disjoint intervals.
func mergeRanges(ranges []addrRange) []addrRange {
	if len(ranges) == 0 {
		return nil
	}
	slices.SortFunc(ranges, func(a, b addrRange) int { return a.lo.Compare(b.lo) })
	out := ranges[:1]
	for _, cur := range ranges[1:] {
		last := &out[len(out)-1]
		if cur.lo.Compare(last.hi) <= 0 || cur.lo == last.hi.Next() {
			if cur.hi.Compare(last.hi) > 0 {
				last.hi = cur.hi
			}
			continue
		}
		out = append(out, cur)
	}
	return slices.Clip(out)
}

// MatchDomain reports whether a name is one of the exact names or sits under
// one of the suffixes. Case and a trailing dot do not matter.
func (r *Rules) MatchDomain(name string) bool {
	if r == nil {
		return false
	}
	name = normalizeName(name)
	if name == "" {
		return false
	}
	if _, ok := r.full[name]; ok {
		return true
	}
	for {
		if _, ok := r.suffix[name]; ok {
			return true
		}
		dot := strings.IndexByte(name, '.')
		if dot < 0 {
			return false
		}
		name = name[dot+1:]
	}
}

// MatchIP reports whether an address lies in one of the prefixes. An
// IPv4-mapped IPv6 address is matched as the IPv4 address it carries.
func (r *Rules) MatchIP(ip netip.Addr) bool {
	if r == nil || !ip.IsValid() {
		return false
	}
	ip = ip.Unmap().WithZone("")
	// The last range starting at or before ip is the only one that can hold it.
	idx, _ := slices.BinarySearchFunc(r.ranges, ip, func(rg addrRange, target netip.Addr) int {
		return rg.lo.Compare(target)
	})
	if idx < len(r.ranges) && r.ranges[idx].lo == ip {
		return true
	}
	if idx == 0 {
		return false
	}
	return r.ranges[idx-1].hi.Compare(ip) >= 0
}

// MatchHost matches an address literal by prefix and anything else by name.
// Brackets around an IPv6 literal are allowed.
func (r *Rules) MatchHost(host string) bool {
	if r == nil {
		return false
	}
	bare := strings.TrimSuffix(strings.TrimPrefix(host, "["), "]")
	if ip, err := netip.ParseAddr(bare); err == nil {
		return r.MatchIP(ip)
	}
	return r.MatchDomain(host)
}

// Summary is the one line a log prints about the rules.
func (r *Rules) Summary() string {
	if r == nil {
		return "off"
	}
	return fmt.Sprintf("%d names, %d prefixes", r.names, r.prefixes)
}
