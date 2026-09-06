// node link.test.mjs
import assert from "node:assert/strict";
import { parseLink } from "./link.js";

const v = parseLink("vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?security=tls&type=ws#Berlin");
assert.deepEqual(v, {
  name: "Berlin", scheme: "https", host: "vpn.example.com", port: 443,
  user: "11111111-2222-3333-4444-555555555555", pass: "11111111-2222-3333-4444-555555555555",
});

assert.equal(parseLink("vless://uuid@h.net:8443#x").port, 8443, "the link's own port is the proxy port");
assert.equal(parseLink("vless://uuid@h.net:443?proxyPort=9443#x").port, 9443, "proxyPort overrides it");
assert.equal(parseLink("vless://uuid@h.net#x").port, 443, "no port in the link -> 443");
assert.equal(parseLink("vless://uuid@h.net:443").name, "h.net", "name falls back to the host");

const p = parseLink("https://bob:s3cret@proxy.example.com:8443");
assert.deepEqual([p.scheme, p.host, p.port, p.user, p.pass], ["https", "proxy.example.com", 8443, "bob", "s3cret"]);
assert.equal(parseLink("http://proxy.lan").port, 80);
assert.equal(parseLink("https://proxy.lan").port, 443);
assert.equal(parseLink("vless://uuid@[2001:db8::1]:443").host, "2001:db8::1");

for (const bad of ["", "hello", "awg://x", "vless://h.net:443", "vless://u@h.net:443?proxyPort=99999"])
  assert.throws(() => parseLink(bad), `should reject: ${bad}`);

console.log("ok");
