// The YPtun desktop app's loopback control API (DesktopExtensionBridge.kt).
//
// MV3 gives an extension no sockets and no way to listen on a port, so it can never speak VLESS
// itself — and REALITY is impossible in a browser at all, since it needs a custom TLS ClientHello
// and Chrome's TLS stack is not scriptable. The app IS this extension's engine: it gets the link,
// raises the tunnel with whatever core the link needs (tcp, xhttp, tls, REALITY, AmneziaWG…) and
// hands back one loopback HTTP proxy that `chrome.proxy` understands.

const BASE = "http://127.0.0.1:47639";
const GUARD = { "X-YPtun-Extension": "1" }; // forces a CORS preflight; web pages can't get past it
const TIMEOUT_MS = 90000; // a cold connect (import + core start + handshake) is slow

/** "not-running" when the app isn't there; otherwise the parsed JSON, which may carry `.error`. */
export async function call(path, body) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    const r = await fetch(BASE + path, {
      method: body === undefined ? "GET" : "POST",
      headers: GUARD,
      body,
      cache: "no-store",
      signal: ctl.signal,
    });
    return await r.json();
  } catch {
    return { error: "not-running" };
  } finally {
    clearTimeout(timer);
  }
}
