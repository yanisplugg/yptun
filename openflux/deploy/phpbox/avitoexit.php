<?php
if (is_file(__DIR__ . '/config.php')) { require_once __DIR__ . '/config.php'; }
/**
 * avitoexit.php - a phpbox exit over code.avito.ru ShareDB rooms.
 *
 *   client (--mode=stream, --transport=avito, --url <packed room list>)
 *         ->  code.avito.ru ShareDB  ->  avitoexit.php  ->  dst
 *
 * Mirrors transport/avito: tunnel bytes ride as base64 inside ShareDB textv1
 * ops; the link is duplex over a SPLIT set of rooms - the exit WRITES the first
 * half (the client reads them) and READS the second half (the client writes
 * them) - because two writers on one ShareDB doc collide. One op = one mux
 * frame. Shared mux/ws/util live in lib/.
 *
 * The rooms are made ahead of time (both ends need the same list) and the
 * packed list (base64url(json(ids)), as transport/avito prints) is passed as
 * ?url=. Anonymous: no login/cookies needed.
 *
 * Run: https://<host>/avitoexit.php?k=PHPBOX_TOKEN&url=<packed list>
 * Offline framing self-test: php avitoexit.php selftest
 *
 * NOTE (chain mode): every generation joins the SAME rooms, and two generations
 * writing one ShareDB doc race on the version. A first proof runs one generation
 * (no &chain, or short overlap); robust avito handover is a later problem
 * (per-generation sub-rooms, or a version lease).
 */
error_reporting(E_ALL & ~E_DEPRECATED);
require_once __DIR__ . '/lib/util.php';
require_once __DIR__ . '/lib/ws.php';
require_once __DIR__ . '/lib/mux.php';
require_once __DIR__ . '/lib/node.php';

const RUN_CAP      = 140;
const AVITO_BASE   = 'https://code.avito.ru';
const AVITO_WS     = 'wss://code.avito.ru/sharedb';
const SHAREDB_TYPE = 'http://sharejs.org/types/textv1';

final class AvitoRoom
{
    public WsClient $ws;
    public string $connID = '';
    public int $ver = 0;
    public int $docLen = 0;
    public int $seq = 1;
    public bool $ready = false;
    public bool $pending = false;          // tx only: an op is awaiting its ack
    public function __construct(public string $id, public bool $isTx) { $this->ws = new WsClient(); }
}

final class AvitoCarrier implements Carrier
{
    private Mux $mux;
    /** @var AvitoRoom[] */ private array $rooms = [];
    /** @var AvitoRoom[] */ private array $tx = [];
    private array $outQ = [];              // frames waiting for a free tx room
    private int $rr = 0;

    public function __construct(private string $spec) {}

    public function setMux(Mux $m): void { $this->mux = $m; }

    public function sockets(): array
    {
        $s = [];
        foreach ($this->rooms as $r) { if ($r->ws->sock) { $s[] = $r->ws->sock; } }
        return $s;
    }

    public function connect(): bool
    {
        $ids = self::unpackRooms($this->spec);
        if (count($ids) < 2) { echo "avito: ?url= needs a packed list of >=2 rooms\n"; return false; }
        $half = intdiv(count($ids), 2);
        foreach ($ids as $i => $id) {
            $r = new AvitoRoom($id, $i < $half);        // exit writes the first half, reads the second
            if ($this->join($r)) { $this->rooms[] = $r; if ($r->isTx) { $this->tx[] = $r; } }
            else { echo "avito: room " . substr($id, 0, 8) . " join failed\n"; }
        }
        $rx = count($this->rooms) - count($this->tx);
        echo "avito: " . count($this->tx) . " tx + $rx rx rooms ready\n";
        return count($this->tx) > 0 && $rx > 0;
    }

    private function join(AvitoRoom $r): bool
    {
        if (!$r->ws->connect(AVITO_WS, AVITO_BASE, '')) { echo "avito: ws connect failed\n"; return false; }
        $init = $this->read($r, 12);                    // server greets with {"id": connID}
        $r->connID = (string)($init['id'] ?? '');
        if ($r->connID === '') { return false; }
        $r->ws->writeText(json_encode(['a' => 's', 'c' => 'docs', 'd' => $r->id, 'v' => null]));
        $sub = $this->read($r, 12);
        if (isset($sub['error'])) { echo "avito: subscribe error\n"; return false; }
        $doc = is_array($sub['data'] ?? null) ? $sub['data'] : $sub;
        $r->ver = (int)($doc['v'] ?? 0);
        $r->docLen = strlen((string)($doc['data'] ?? ''));
        $r->seq = 1;
        if (($doc['type'] ?? null) === null) {          // make the doc if it is new
            $r->ws->writeText(json_encode(['a' => 'op', 'c' => 'docs', 'd' => $r->id, 'v' => 0,
                'src' => $r->connID, 'seq' => $r->seq, 'create' => ['type' => SHAREDB_TYPE, 'data' => '']]));
            $reply = $this->read($r, 12);
            if (!isset($reply['error'])) { $r->ver = 1; $r->docLen = 0; $r->seq = 2; }
            else {                                       // another endpoint created it first: re-read the version
                $r->ws->writeText(json_encode(['a' => 's', 'c' => 'docs', 'd' => $r->id, 'v' => null]));
                $re = $this->read($r, 12); $d2 = is_array($re['data'] ?? null) ? $re['data'] : $re;
                $r->ver = (int)($d2['v'] ?? 1); $r->docLen = strlen((string)($d2['data'] ?? '')); $r->seq = 2;
            }
        }
        $r->ready = true;
        return true;
    }

    /** Blocking read of one JSON message (setup only). */
    private function read(AvitoRoom $r, float $timeout): array
    {
        $deadline = microtime(true) + $timeout;
        while (microtime(true) < $deadline) {
            $m = $r->ws->readFrame(2.0);
            if ($m === null) { if ($r->ws->closed) { return []; } continue; }
            if ($m === '') { continue; }
            $o = json_decode($m, true);
            if (is_array($o)) { return $o; }
        }
        return [];
    }

    public function onReadable($sock): void
    {
        foreach ($this->rooms as $r) {
            if ($r->ws->sock !== $sock) { continue; }
            do {
                $m = $r->ws->readFrame(0.2);
                if ($m !== null && $m !== '') { $this->onMessage($r, $m); }
            } while ($r->ws->pending());
            return;
        }
    }

    private function onMessage(AvitoRoom $r, string $msg): void
    {
        $o = json_decode($msg, true);
        if (!is_array($o)) { return; }
        $src = (string)($o['src'] ?? '');
        if (($o['a'] ?? '') === 'op' && $src !== '' && $src !== $r->connID) {
            foreach (($o['op'] ?? []) as $piece) {       // a peer op (the client's data): one string = one packet
                if (is_string($piece) && $piece !== '') {
                    $pkt = base64_decode($piece, true);
                    if ($pkt !== false && $pkt !== '') { $this->mux->onPacket($pkt); }
                }
            }
            if (isset($o['v'])) { $nv = (int)$o['v'] + 1; if ($nv > $r->ver) { $r->ver = $nv; } }
            return;
        }
        if ($r->isTx && $r->pending) {                   // the ack/echo for our own op
            $r->ver = isset($o['v']) ? (int)$o['v'] + 1 : $r->ver + 1;
            $r->seq++;
            $r->pending = false;
            $this->flush();
        }
    }

    public function sendPacket(string $frame): void
    {
        $r = $this->freeTx();
        if ($r === null) { $this->outQ[] = $frame; return; }
        $this->sendOp($r, $frame);
    }

    private function flush(): void
    {
        while ($this->outQ) {
            $r = $this->freeTx();
            if ($r === null) { return; }
            $this->sendOp($r, array_shift($this->outQ));
        }
    }

    private function freeTx(): ?AvitoRoom
    {
        $n = count($this->tx);
        for ($i = 0; $i < $n; $i++) {
            $r = $this->tx[($this->rr + $i) % $n];
            if ($r->ready && !$r->pending && $r->ws->sock) { $this->rr = ($this->rr + $i + 1) % $n; return $r; }
        }
        return null;
    }

    private function sendOp(AvitoRoom $r, string $frame): void
    {
        $b64 = base64_encode($frame);
        $op  = $r->docLen > 0 ? [['d' => $r->docLen], $b64] : [$b64];
        $r->ws->writeText(json_encode(['a' => 'op', 'c' => 'docs', 'd' => $r->id,
            'v' => $r->ver, 'src' => $r->connID, 'seq' => $r->seq, 'op' => $op]));
        $r->docLen  = strlen($b64);
        $r->pending = true;
    }

    public function close(): void { foreach ($this->rooms as $r) { $r->ws->close(); } }

    // ---- room-list codec (matches transport/avito packRooms) ---------------
    public static function unpackRooms(string $s): array
    {
        $s = trim($s);
        if ($s === '') { return []; }
        if (($u = @parse_url($s)) && !empty($u['query'])) {
            parse_str($u['query'], $q);
            if (!empty($q['rooms'])) { $s = (string)$q['rooms']; }
        }
        $raw = base64_decode(strtr($s, '-_', '+/'), true);
        if ($raw === false) { return []; }
        $ids = json_decode($raw, true);
        return is_array($ids) ? array_values(array_filter($ids, 'is_string')) : [];
    }

    public static function packRooms(array $ids): string
    {
        return rtrim(strtr(base64_encode(json_encode(array_values($ids))), '+/', '-_'), '=');
    }

    public static function selfTest(): void
    {
        $ok = true;
        foreach (['', 'A', 'hello', str_repeat('Z', 300), random_bytes(4000)] as $p) {
            $frame = pack('CNN', Mux::DATA, 7, strlen($p)) . $p;   // one mux frame
            $op    = [['d' => 123], base64_encode($frame)];         // delete+insert op shape
            $dec   = base64_decode($op[count($op) - 1], true);
            $pass  = $dec === $frame;
            $ok    = $ok && $pass;
            printf("selftest frame len=%-5d %s\n", strlen($p), $pass ? 'OK' : 'FAIL');
        }
        $ids = ['11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222',
                '33333333-3333-3333-3333-333333333333', '44444444-4444-4444-4444-444444444444'];
        $rt = self::unpackRooms(self::packRooms($ids));
        $pass = $rt === $ids; $ok = $ok && $pass;
        printf("selftest roomlist(%d) %s\n", count($ids), $pass ? 'OK' : 'FAIL');
        // unpack from a ?rooms= URL too
        $pass2 = self::unpackRooms('https://x/y?rooms=' . self::packRooms($ids)) === $ids; $ok = $ok && $pass2;
        printf("selftest roomlist-from-url %s\n", $pass2 ? 'OK' : 'FAIL');
        echo $ok ? "SELFTEST PASS\n" : "SELFTEST FAIL\n";
        exit($ok ? 0 : 1);
    }
}

if (PHP_SAPI === 'cli' && ($argv[1] ?? '') === 'selftest') { AvitoCarrier::selfTest(); exit; }

(new PhpboxNode('avito', 'code.avito.ru', function (array $g): string {
    return trim((string)($g['url'] ?? ''));              // the packed room list
}, fn(string $spec) => new AvitoCarrier($spec), RUN_CAP))->handle();
