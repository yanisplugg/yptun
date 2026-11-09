"""Stream-slot check: snolc server + client on loopback, SOCKS5 flows to a local echo sink.

usage: stream_limit_check.py <snolc binary> <streams_per_session> <n_flows> [workdir]

Burst (default): opens <n_flows> flows at once. With n_flows above the limit the session must SURVIVE:
the first (limit-1) flows stay alive, the rest are refused one by one, and a slot freed by closing a flow
is usable again. Before the fix the whole tunnel was reset ("yamux user stream limit is exhausted") and
every live connection died.

CHURN=1: opens and closes <n_flows> flows (CHURN_THREADS=N in parallel). They never exceed the limit, so
every one must succeed - this is what caught the slot leak (closed flows not released on the server).
"""
import os, shutil, socket, struct, subprocess, sys, threading, time

exe, streams, n_flows = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
work = sys.argv[4] if len(sys.argv) > 4 else os.path.join(os.environ["TEMP"], "snolc-repro")
shutil.rmtree(work, ignore_errors=True)
SRV, CLI = os.path.join(work, "srv"), os.path.join(work, "cli")
for d in (SRV, CLI):
    os.makedirs(os.path.join(d, "modules"))
    os.makedirs(os.path.join(d, "state"))

TUN_PORT, SOCKS_PORT, SINK_PORT = 18443, 18080, 19000
window = streams * 262144
managed = 1 << 30


def engine(role, adapter):
    return f'''wire_version = 1

[paths]
packages = "builtin"
state = "state"

[engine]
max_sessions = 4
max_flows = {max(256, 4 * streams)}
max_pending_sessions = 4
max_pending_opens = 64
max_managed_bytes = {managed}
max_commands = 64
max_events = 256
max_io_chunk = 16384
max_ingress_packets_per_tick = 32
connect_timeout_ms = 15000
handshake_timeout_ms = 15000
shutdown_timeout_ms = 5000

[stack]
ipv4 = true
ipv6 = true
mtu = 1280
tcp_socket_rx_bytes = 65536
tcp_socket_tx_bytes = 65536
udp_socket_rx_bytes = 131072
udp_socket_tx_bytes = 131072
udp_metadata_slots = 8
packet_queue_bytes = 262144
max_udp_payload_bytes = 65507
reassembly_slots = 4
reassembly_timeout_ms = 15000

[yamux]
max_streams_per_session = {streams}
receive_window_bytes = {window}
split_send_size = 16384
read_after_close = true

[logging]
mode = "file"
source = "toml"
levels = ["warning", "error", "debug"]
file = "state/snolc.log"
limit = "8mb"
queue_bytes = 65536
max_record_bytes = 2048
flush_interval_ms = 1000
on_io_error = "event"

[control]
mode = "off"

[[tunnels]]
name = "main"
role = "{role}"
adapters = ["modules/{adapter}.toml"]
protection = "modules/noise.toml"
carrier = "modules/tcp.toml"
policy = "modules/policy.toml"
'''


def module(instance, pkg, role, options):
    return f'wire_version = 1\ninstance = "{instance}"\npackage = "owenewans/{pkg}@0.0.4"\nrole = "{role}"\n\n[options]\n{options.strip()}\n'


def put(d, rel, text):
    with open(os.path.join(d, rel), "w", newline="\n") as f:
        f.write(text)


# keys
subprocess.run([exe, "keygen", os.path.join(SRV, "priv.hex"), os.path.join(CLI, "pub.hex")], check=True, capture_output=True)

put(SRV, "snolc.toml", engine("server", "direct"))
put(SRV, "modules/direct.toml", module("direct-main", "adapter-direct", "server",
    'dns_mode = "system"\nmax_pending_opens = 64\nmax_resolved_addresses = 16\nresolve_timeout_ms = 15000\nconnect_timeout_ms = 15000'))
put(SRV, "modules/noise.toml", module("noise-main", "protection-noise", "server", 'mode = "server"\nprivate_key_file = "../priv.hex"'))
put(SRV, "modules/tcp.toml", module("tcp-main", "carrier-tcp", "server", f'mode = "listen"\nendpoint_ip = "127.0.0.1:{TUN_PORT}"\nmax_connections = 8\nnodelay = true'))
put(SRV, "modules/policy.toml", module("policy-main", "policy-dummy", "server", "pump_buffer_bytes = 16384"))

put(CLI, "snolc.toml", engine("client", "socks5"))
put(CLI, "modules/socks5.toml", module("socks5-main", "adapter-socks5", "client",
    f'listen = "127.0.0.1:{SOCKS_PORT}"\nmax_connections = 512\nmax_udp_associations = 8\nmax_request_bytes = 1024\nreject_fragments = true'))
put(CLI, "modules/noise.toml", module("noise-main", "protection-noise", "client", 'mode = "client"\nserver_public_key_file = "../pub.hex"'))
put(CLI, "modules/tcp.toml", module("tcp-main", "carrier-tcp", "client", f'mode = "connect"\nendpoint_ip = "127.0.0.1:{TUN_PORT}"\nmax_connections = 2\nnodelay = true'))
put(CLI, "modules/policy.toml", module("policy-main", "policy-dummy", "client", "pump_buffer_bytes = 16384"))

# sink: echoes one line per connection, then keeps it open
sink = socket.socket(); sink.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sink.bind(("127.0.0.1", SINK_PORT)); sink.listen(512)


def serve():
    while True:
        try:
            c, _ = sink.accept()
        except OSError:
            return
        def echo(c=c):
            try:
                while True:
                    d = c.recv(4096)
                    if not d:
                        return
                    c.sendall(d)
            except OSError:
                pass
        threading.Thread(target=echo, daemon=True).start()


threading.Thread(target=serve, daemon=True).start()

procs = []


def start(cfg_dir):
    p = subprocess.Popen([exe, "run", os.path.join(cfg_dir, "snolc.toml")], cwd=cfg_dir,
                         stdout=open(os.path.join(cfg_dir, "out.txt"), "w"), stderr=subprocess.STDOUT)
    procs.append(p)


def socks_open():
    s = socket.create_connection(("127.0.0.1", SOCKS_PORT), timeout=15)
    s.sendall(b"\x05\x01\x00")
    if s.recv(2) != b"\x05\x00":
        raise OSError("socks greeting")
    s.sendall(b"\x05\x01\x00\x01" + socket.inet_aton("127.0.0.1") + struct.pack(">H", SINK_PORT))
    rep = s.recv(10)
    if len(rep) < 2 or rep[1] != 0:
        raise OSError("socks connect rep=%r" % rep[:2])
    return s


def alive(s):
    try:
        s.settimeout(5)
        s.sendall(b"ping")
        return s.recv(4) == b"ping"
    except OSError:
        return False


try:
    start(SRV); time.sleep(1.5); start(CLI); time.sleep(3)
    if os.environ.get("CHURN"):
        # sequential open/ping/close: with correct accounting this never runs out of slots
        bad = None
        workers = int(os.environ.get("CHURN_THREADS", "1"))
        lock = threading.Lock()

        def churn(count):
            global bad
            for i in range(count):
                try:
                    s = socks_open(); ok_ = alive(s); time.sleep(0.02 * (i % 4)); s.close()
                    if not ok_:
                        with lock: bad = bad or (i, "dead")
                        return
                except OSError as e:
                    with lock: bad = bad or (i, str(e))
                    return
                time.sleep(0.01)

        ts = [threading.Thread(target=churn, args=(n_flows // workers,)) for _ in range(workers)]
        [x.start() for x in ts]; [x.join() for x in ts]
        print(f"CHURN streams_per_session={streams}: {n_flows} sequential flows ->", "all ok" if bad is None else f"first failure at #{bad[0]}: {bad[1]}")
        raise SystemExit
    socks = []
    fails = []
    for i in range(n_flows):
        try:
            socks.append(socks_open())
        except OSError as e:
            fails.append((i, str(e)))
    time.sleep(2)
    ok = sum(1 for s in socks if alive(s))
    print(f"streams_per_session={streams}: opened={len(socks)}/{n_flows} open_failures={len(fails)} still_alive_after_2s={ok}")
    if fails:
        print("  first failures:", fails[:3])
    # a fresh flow after everything: does the tunnel still work?
    try:
        t = socks_open(); print("  fresh flow after burst:", "ok" if alive(t) else "dead")
    except OSError as e:
        print("  fresh flow after burst: FAILED", e)
    # slots freed by closing flows must be usable again
    for s in socks[:10]:
        s.close()
    t0 = time.time()
    while True:
        time.sleep(1)
        try:
            t = socks_open(); print("  flow after freeing 10 slots: %s after %.0fs" % ("ok" if alive(t) else "dead", time.time() - t0))
            break
        except OSError as e:
            if time.time() - t0 > 40:
                print("  flow after freeing 10 slots: still FAILED after 40s", e)
                break
finally:
    for p in procs:
        p.kill()
    for d in (SRV, CLI):
        for f in ("out.txt", "state/snolc.log"):
            p = os.path.join(d, f)
            if os.path.exists(p):
                txt = open(p, errors="replace").read()
                lines = [l for l in txt.splitlines() if "limit" in l or "ModuleError" in l or "error" in l.lower()]
                print(f"-- {os.path.basename(d)}/{f}: {len(txt.splitlines())} lines, {len(lines)} error-ish; sample:", lines[:3])
