// A pasted link -> a display record. The link itself is what matters: it goes to the YPtun app
// verbatim, which is the only thing that can actually speak it.
//
// Nothing here decides how to connect any more. The old version derived an HTTP-proxy record from
// the link by convention (same host, port 8443, UUID as user AND password) because Chrome could
// only ever reach a proxy ON THE SERVER — which needed a second xray inbound with a real
// certificate, and could never carry REALITY. Now the app connects and Chrome just uses the
// loopback proxy it publishes, so every transport the app supports works unchanged.

const SCHEMES = /^(vless|vmess|trojan|ss|ssh|hy2|hysteria2|tuic|wireguard|warp|yptun):\/\//i;

export function parseLink(text) {
  const raw = String(text || "").trim();
  if (!SCHEMES.test(raw)) throw new Error("bad link");

  // Reparse under `yptun:` (a NON-special scheme) so an explicit `:443` survives — the URL parser
  // swallows a default port on a special scheme, and the display chip would then lose it.
  const u = new URL(raw.replace(/^[a-z0-9]+:\/\//i, "yptun://"));
  const host = u.hostname.replace(/^\[|\]$/g, "");
  if (!host) throw new Error("bad link");

  return {
    name: decodeURIComponent(u.hash.slice(1)) || host,
    host,
    port: Number(u.port) || 0,
    link: raw,
  };
}
