#!/bin/bash
# PID 1 контейнера AWG: демон в форграунде, чтобы его смерть роняла контейнер.
# `ft-awg-start sync` - применить правки conf к живому интерфейсу (docker exec с хоста).
set -euo pipefail

IFACE="${AWG_IFACE:-ftawg0}"
CONF="${AWG_CONF:-/etc/awg/${IFACE}.conf}"
if [ ! -f "$CONF" ] && [ -f "/etc/awg/awg0.conf" ]; then
    CONF="/etc/awg/awg0.conf"
fi

# Значения ключа из [Interface] (до первого [Peer]), по одному на строку.
_iface_vals() {
    sed -n "/^[[:space:]]*\[[pP][eE][eE][rR]\]/q; s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//Ip" "$CONF" \
        | sed 's/[#;].*//' | tr -d ' \r' | tr ',' '\n' | grep -v '^$' || true
}

_iface_val() {
    _iface_vals "$1" | head -n1
}

_sync() {
    awg-quick strip "$CONF" | awg syncconf "$IFACE" /dev/stdin
}

if [ "${1:-}" = "sync" ]; then
    _sync
    exit 0
fi

[ -f "$CONF" ] || { echo "ft-awg: $CONF not found" >&2; exit 1; }

ADDRS=$(_iface_vals Address)
MTU=$(_iface_val MTU)
[ -n "$ADDRS" ] || { echo "ft-awg: no Address in $CONF" >&2; exit 1; }
: "${MTU:=1280}"

# В namespace контейнера форвардинг задаётся флагом docker --sysctl; здесь - страховка.
sysctl -qw net.ipv4.ip_forward=1 >/dev/null 2>&1 || true

export WG_PROCESS_FOREGROUND=1
# amneziawg-go знает только verbose/debug/error/silent, прочее для него = error.
export LOG_LEVEL="${AWG_LOG_LEVEL:-error}"
amneziawg-go -f "$IFACE" &
GO_PID=$!

_shutdown() {
    kill "$GO_PID" 2>/dev/null || true
    wait "$GO_PID" 2>/dev/null || true
    exit 0
}
trap _shutdown TERM INT

# UAPI-сокет появляется позже процесса - до него setconf не с кем говорить.
for _ in $(seq 1 100); do
    [ -S "/var/run/amneziawg/$IFACE.sock" ] && break
    kill -0 "$GO_PID" 2>/dev/null || { echo "ft-awg: daemon died on startup" >&2; wait "$GO_PID"; exit 1; }
    sleep 0.1
done

_sync
# Без -4: конфиг может нести и v6.
for addr in $ADDRS; do
    ip address add "$addr" dev "$IFACE"
done
ip link set mtu "$MTU" up dev "$IFACE"

# Подсеть берём из connected-роута, а не из парсинга Address - маска любая.
# NAT только v4: masquerade для v6 требует ip6tables, его в образе нет.
NET=$(ip -4 route show dev "$IFACE" proto kernel scope link | awk '{print $1; exit}')
WAN=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="dev"){print $(i+1); exit}}')

_ipt() {
    local tbl=()
    if [ "$1" = "-t" ]; then
        tbl=(-t "$2")
        shift 2
    fi
    if ! iptables "${tbl[@]}" -C "$@" 2>/dev/null; then
        iptables "${tbl[@]}" -A "$@"
    fi
}

if [ -n "$WAN" ] && [ -n "$NET" ]; then
    _ipt FORWARD -i "$IFACE" -j ACCEPT
    _ipt FORWARD -o "$IFACE" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
    _ipt -t nat POSTROUTING -s "$NET" -o "$WAN" -j MASQUERADE
    # -o WAN: ответный SYN-ACK из интернета несёт MSS 1460, режем по PMTU пути.
    # -o IFACE: страховка от клиента, забывшего MTU.
    _ipt -t mangle FORWARD -o "$WAN" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
    _ipt -t mangle FORWARD -o "$IFACE" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss "$((MTU - 40))"
else
    echo "ft-awg: WAN or subnet not detected (wan='$WAN' net='$NET'); NAT skipped" >&2
fi

echo "ft-awg: $IFACE up, addr $(echo "$ADDRS" | tr '\n' ' '), mtu $MTU, nat via ${WAN:-none}"
wait "$GO_PID"
