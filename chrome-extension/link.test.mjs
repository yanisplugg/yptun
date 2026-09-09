// node link.test.mjs
import assert from "node:assert/strict";
import { parseLink } from "./link.js";

const v = parseLink("vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?security=reality&type=tcp#Berlin");
assert.deepEqual(v, {
  name: "Berlin",
  host: "vpn.example.com",
  port: 443,
  link: "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?security=reality&type=tcp#Berlin",
});

// The link is passed to YPtun verbatim — nothing about the transport is interpreted here.
assert.equal(parseLink("vless://uuid@h.net:8443#x").port, 8443, "explicit port survives the reparse");
assert.equal(parseLink("vless://uuid@h.net:443#x").port, 443, "…including one that is a scheme default");
assert.equal(parseLink("vless://uuid@h.net#x").port, 0, "no port in the link -> nothing to show");
assert.equal(parseLink("vless://uuid@h.net:443").name, "h.net", "name falls back to the host");
assert.equal(parseLink("vless://uuid@[2001:db8::1]:443").host, "2001:db8::1");
assert.equal(parseLink("  vless://uuid@h.net:443#x  ").link, "vless://uuid@h.net:443#x", "trimmed");

// Every protocol the app can raise is accepted; the app is what decides whether it is complete.
for (const ok of [
  "vmess://eyJhZGQiOiJoLm5ldCJ9",
  "trojan://pass@h.net:443#x",
  "ss://YWVzOnB3@h.net:8388#x",
  "hy2://pass@h.net:443#x",
  "yptun://inbound?v=1&d=abc",
]) assert.doesNotThrow(() => parseLink(ok), `should accept: ${ok}`);

for (const bad of ["", "hello", "https://proxy.example.com:8443", "vless://:443"])
  assert.throws(() => parseLink(bad), `should reject: ${bad}`);

console.log("ok");
