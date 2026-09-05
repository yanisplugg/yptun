// A pasted link -> a proxy record Chrome can use directly.
//
// `vless://uuid@host:443?...#name` names a server, not a proxy Chrome can speak to, so we apply the
// convention the server side is set up with: the same host runs an xray `http` inbound over TLS on
// `proxyPort` (8443 by default, or `&proxyPort=` in the link) with the UUID as login AND password.
// A plain `https://user:pass@host:port` link is taken literally, for servers set up differently.

export function parseLink(text, defaultPort = 8443) {
  const raw = String(text || "").trim();
  const vless = /^vless:\/\//i.test(raw);
  if (!vless && !/^https?:\/\//i.test(raw)) throw new Error("bad link");

  const u = new URL(raw.replace(/^vless:\/\//i, "https://"));
  if (!u.hostname) throw new Error("bad link");

  const override = Number(u.searchParams.get("proxyPort"));
  const port = override || (vless ? defaultPort : Number(u.port) || (u.protocol === "http:" ? 80 : 443));
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("bad port");

  const uuid = decodeURIComponent(u.username || "");
  if (vless && !uuid) throw new Error("no uuid");

  return {
    name: decodeURIComponent(u.hash.slice(1)) || u.hostname,
    scheme: vless || u.protocol === "https:" ? "https" : "http",
    host: u.hostname.replace(/^\[|\]$/g, ""), // chrome.proxy wants a bare IPv6 literal
    port,
    user: vless ? uuid : decodeURIComponent(u.username || ""),
    pass: vless ? uuid : decodeURIComponent(u.password || ""),
  };
}
