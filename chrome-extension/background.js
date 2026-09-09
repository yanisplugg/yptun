// Service worker: asks the YPtun app to raise the tunnel, then points Chrome at the loopback proxy
// the app publishes for us. See yptun.js for why the engine cannot live in the extension.

import { call } from "./yptun.js";

const DEFAULT_BYPASS = ["localhost", "127.0.0.1", "[::1]", "<local>"];
const PROBE_URL = "https://www.gstatic.com/generate_204";
const PROBE_MS = 10000;

// ── proxy ──────────────────────────────────────────────────────────────────────

async function applyProxy(hostPort) {
  const [host, port] = hostPort.split(":");
  const { bypass = "" } = await chrome.storage.local.get("bypass");
  const extra = bypass.split(/[\s,\n]+/).map((s) => s.trim()).filter(Boolean);
  await chrome.proxy.settings.set({
    scope: "regular",
    value: {
      mode: "fixed_servers",
      rules: {
        singleProxy: { scheme: "http", host, port: Number(port) },
        bypassList: DEFAULT_BYPASS.concat(extra),
      },
    },
  });
}

const clearProxy = () =>
  new Promise((r) => chrome.proxy.settings.clear({ scope: "regular" }, () => r(void chrome.runtime.lastError)));

/** The only honest test: one request through the proxy we just set. "" = it works. */
async function probe() {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), PROBE_MS);
  try {
    const r = await fetch(PROBE_URL, { cache: "no-store", signal: ctl.signal });
    return r.status < 400 ? "" : `http:${r.status}`;
  } catch {
    return "no-traffic";
  } finally {
    clearTimeout(timer);
  }
}

// ── state ──────────────────────────────────────────────────────────────────────

/**
 * Chrome's own proxy setting says whether the BROWSER is routed; the app says whether the TUNNEL is
 * up. Both must hold, so a tunnel dropped from inside the app shows here instead of looking connected.
 */
async function state(error = "") {
  const cfg = await chrome.proxy.settings.get({});
  const routed = cfg.levelOfControl === "controlled_by_this_extension" &&
    cfg.value?.mode === "fixed_servers";
  const app = await call("/status");
  const on = routed && app.connected === true;
  if (routed && !on) await clearProxy(); // the app went down under us
  const { activeId = null } = await chrome.storage.local.get("activeId");
  badge(on);
  return {
    connected: on,
    serverId: on ? activeId : null,
    app: app.error ? "" : `${app.app} ${app.version}`,
    location: app.location || "",
    error: error || (app.error === "not-running" ? "not-running" : ""),
  };
}

function badge(on) {
  chrome.action.setBadgeText({ text: on ? "ON" : "" });
  chrome.action.setBadgeBackgroundColor({ color: on ? "#2E7D32" : "#6E7176" });
}

// ── commands from the popup ────────────────────────────────────────────────────

async function connect(serverId) {
  const { servers = [] } = await chrome.storage.local.get("servers");
  const srv = servers.find((s) => s.id === serverId) || servers[0];
  if (!srv) return { ok: false, error: "no-location" };
  // Records saved by the server-proxy version kept only host/port/user/pass, never the link itself —
  // and the link is the only thing the app can act on now.
  if (!srv.link) return { ok: false, error: "relink", state: await state("relink") };
  await chrome.storage.local.set({ activeId: srv.id });

  const reply = await call("/connect", srv.link);
  if (reply.error) return { ok: false, error: reply.error, state: await state(reply.error) };

  await applyProxy(reply.proxy);
  const err = await probe();
  if (err) {
    await clearProxy(); // never leave the browser pointed at a proxy that carries nothing
    return { ok: false, error: err, state: await state(err) };
  }
  return { ok: true, state: await state() };
}

async function disconnect() {
  await clearProxy();
  await call("/disconnect", "");
  return { ok: true, state: await state() };
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    switch (msg.type) {
      case "connect": sendResponse(await connect(msg.serverId)); break;
      case "disconnect": sendResponse(await disconnect()); break;
      case "state": sendResponse({ ok: true, state: await state() }); break;
      default: sendResponse({ ok: false, error: "unknown" });
    }
  })();
  return true; // async sendResponse
});

chrome.runtime.onStartup.addListener(() => state());
state();
