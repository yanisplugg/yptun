// A pasted link -> the proxy record Chrome can use directly.
//
// `vless://uuid@host:443?...#name` names a server, not a proxy Chrome can speak to, so we apply the
// convention the server side is set up with: the SAME host:port also answers as an xray `http`
// inbound with the UUID as login AND password. Nothing for the user to pick — the port comes from
// the link itself; `&proxyPort=` overrides it for a server that puts the http inbound elsewhere.
// A plain `http(s)://user:pass@host:port` link is taken literally, for servers set up differently.

export function parseLink(text) {
  const raw = String(text || "").trim();
  const vless = /^vless:\/\//i.test(raw);
  if (!vless && !/^https?:\/\//i.test(raw)) throw new Error("bad link");

  // Reparse under `yptun:` (a NON-special scheme) on purpose: the URL parser swallows an explicit
  // `:443` on an `https:` URL as the scheme default, and we need the number the user actually wrote.
  const u = new URL(raw.replace(/^[a-z]+:\/\//i, "yptun://"));
  if (!u.hostname) throw new Error("bad link");

  const secure = vless || /^https:/i.test(raw);
  const override = Number(u.searchParams.get("proxyPort"));
  const port = override || Number(u.port) || (secure ? 443 : 80);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("bad port");

  const uuid = decodeURIComponent(u.username || "");
  if (vless && !uuid) throw new Error("no uuid");

  return {
    name: decodeURIComponent(u.hash.slice(1)) || u.hostname,
    // Only a starting guess: the same host:port may be a plain HTTP proxy, and connect() falls back
    // to the other scheme on its own rather than asking.
    scheme: secure ? "https" : "http",
    host: u.hostname.replace(/^\[|\]$/g, ""), // chrome.proxy wants a bare IPv6 literal
    port,
    user: uuid,
    pass: vless ? uuid : decodeURIComponent(u.password || ""),
  };
}
