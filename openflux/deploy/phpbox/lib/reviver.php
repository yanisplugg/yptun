<?php
// lib/reviver.php - node self-revival through third-party screenshot services.
//
// A screenshot service renders a URL in a REAL headless browser. That browser
// executes the free host's JS/AES anti-bot (which a plain curl or cron cannot),
// reaches the node's kick URL, and the node spawns its next generation. The
// node fires this itself near end-of-life (in a separate request, so the
// serving loop never blocks on it), so the tunnel survives the per-request cap
// with no help from the client - it works even when the only client is a phone
// on mobile data. Ported from the enterno_shot.py written 2026-10-14.

require_once __DIR__ . '/util.php';

final class PhpboxReviver
{
    /** Services to try, in order. Each loads a URL in a real browser (passes the anti-bot). */
    const SERVICES = ['enterno'];

    /** Have some browser load $target. Returns the service that did, or '' if all failed. */
    public static function kick(string $target, $log = null): string
    {
        foreach (self::SERVICES as $svc) {
            try {
                if (self::{$svc}($target, $log)) {
                    if ($log) { $log->write('info', "revive: $svc rendered the kick URL"); }
                    return $svc;
                }
            } catch (\Throwable $e) {
                if ($log) { $log->write('warn', "revive: $svc error: " . $e->getMessage()); }
            }
        }
        return '';
    }

    /**
     * enterno.io screenshot API: GET /screenshot for the CSRF token (meta tag),
     * grant AI consent, then POST the target to /api/screenshot.php. The
     * screenshot is irrelevant; the side effect - enterno's browser loading
     * $target - is the kick. Self-bootstrapping: no hard-coded token/cookies.
     */
    private static function enterno(string $target, $log): bool
    {
        $base = 'https://enterno.io';
        $jar  = tempnam(sys_get_temp_dir(), 'rvj');
        try {
            [$page, $c1] = PhpboxUtil::http("$base/screenshot", $jar, 'GET', null,
                ['Accept: text/html', 'Accept-Language: en-US,en;q=0.9']);
            if ($c1 !== 200) { throw new \RuntimeException("GET /screenshot -> $c1"); }
            $tok = PhpboxUtil::scrape('/name="csrf-token"[^>]*content="([a-f0-9]{64})"/i', (string)$page);
            if ($tok === '') { throw new \RuntimeException('csrf token not found'); }

            $hdr = [
                'X-CSRF-Token: ' . $tok,
                'Origin: ' . $base,
                'Referer: ' . $base . '/screenshot',
                'Accept: */*',
                'Content-Type: application/x-www-form-urlencoded',
            ];
            // AI consent is required before a screenshot (the API checks a cookie it sets).
            PhpboxUtil::http("$base/api/ai/consent.php", $jar, 'POST',
                'action=grant&_token=' . urlencode($tok), $hdr);

            [$body, $c3] = PhpboxUtil::http("$base/api/screenshot.php", $jar, 'POST',
                '_token=' . urlencode($tok) . '&url=' . urlencode($target), $hdr);
            $j  = json_decode((string)$body, true);
            $ok = $c3 === 200 && is_array($j) && (($j['status'] ?? '') === 'success');
            if (!$ok && $log) {
                $log->write('warn', 'revive: enterno -> ' . $c3 . ' ' . substr(strip_tags((string)$body), 0, 120));
            }
            return $ok;
        } finally {
            @unlink($jar);
        }
    }
}
