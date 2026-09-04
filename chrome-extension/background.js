// Service worker: owns the native-host port, the chrome.proxy setting and the badge.
// The popup never talks to the host directly — it asks here and re-renders from the state it gets back.

const HOST = "org.yptun.host";
const KEEPALIVE_MS = 20000; // < the 30s MV3 idle timeout; every port message also resets it

let port = null;          // chrome.runtime.Port to the native host
let pending = [];         // resolvers waiting for the host's next reply (host answers in order)
let keepalive = null;

/** state: {connected, port, serverId, error} — mirrored into session storage so a SW restart recovers. */
let state = { connected: false, port: 0, serverId: null, error: "" };

// ── native host ────────────────────────────────────────────────────────────────

function connectHost() {
  if (port) return port;
  port = chrome.runtime.connectNative(HOST);
  port.onMessage.addListener((msg) => {
    const resolve = pending.shift();
    if (resolve) resolve(msg);
  });
  port.onDisconnect.addListener(() => {
    const err = chrome.runtime.lastError?.message || "native host disconnected";
    port = null;
    pending.splice(0).forEach((r) => r({ ok: false, error: err }));
    stopKeepalive();
    // The host dies with the port, so the tunnel is gone: never leave Chrome proxying into nothing.
    if (state.connected) setState({ connected: false, port: 0, error: err });
    clearProxy();
  });
  startKeepalive();
  return port;
}

function send(msg) {
  return new Promise((resolve) => {
    try {
      connectHost().postMessage(msg);
      pending.push(resolve);
    } catch (e) {
      resolve({ ok: false, error: String(e.message || e) });
    }
  });
}

function startKeepalive() {
  stopKeepalive();
  keepalive = setInterval(() => { if (port) send({ cmd: "ping" }); }, KEEPALIVE_MS);
  chrome.alarms.create("keepalive", { periodInMinutes: 0.5 });
}

function stopKeepalive() {
  if (keepalive) clearInterval(keepalive);
  keepalive = null;
  chrome.alarms.clear("keepalive");
}

// ── proxy ──────────────────────────────────────────────────────────────────────

const DEFAULT_BYPASS = ["localhost", "127.0.0.1", "[::1]", "<local>"];

async function applyProxy(socksPort) {
  const { bypass = "" } = await chrome.storage.local.get("bypass");
  const extra = bypass.split(/[\s,\n]+/).map((s) => s.trim()).filter(Boolean);
  await chrome.proxy.settings.set({
    scope: "regular",
    value: {
      mode: "fixed_servers",
      rules: {
        singleProxy: { scheme: "socks5", host: "127.0.0.1", port: socksPort },
        bypassList: DEFAULT_BYPASS.concat(extra),
      },
    },
  });
}

function clearProxy() {
  chrome.proxy.settings.clear({ scope: "regular" }, () => void chrome.runtime.lastError);
}

// ── state ──────────────────────────────────────────────────────────────────────

function setState(patch) {
  state = { ...state, ...patch };
  chrome.storage.session.set({ state });
  chrome.action.setBadgeText({ text: state.connected ? "ON" : "" });
  chrome.action.setBadgeBackgroundColor({ color: state.connected ? "#2E7D32" : "#6E7176" });
  chrome.runtime.sendMessage({ type: "state", state }).catch(() => {});
}

async function restoreState() {
  const stored = await chrome.storage.session.get("state");
  if (stored.state) state = stored.state;
}

// ── commands from the popup ────────────────────────────────────────────────────

async function connect(serverId) {
  const { servers = [] } = await chrome.storage.local.get("servers");
  const server = servers.find((s) => s.id === serverId) || servers[0];
  if (!server) return { ok: false, error: "no server" };

  const reply = await send({
    cmd: "start",
    kind: server.kind,
    uri: server.kind === "vless" ? server.config : "",
    config: server.kind === "awg" ? server.config : "",
  });
  if (!reply.ok) {
    setState({ connected: false, port: 0, error: reply.error || "start failed" });
    return reply;
  }
  await applyProxy(reply.port);
  await chrome.storage.local.set({ lastServerId: server.id });
  setState({ connected: true, port: reply.port, serverId: server.id, error: "" });
  return reply;
}

async function disconnect() {
  clearProxy();
  const reply = await send({ cmd: "stop" });
  setState({ connected: false, port: 0, error: "" });
  if (port) port.disconnect();
  port = null;
  stopKeepalive();
  return reply;
}

/** Re-checks the host: the tunnel is only really up if the host says so AND we set the proxy. */
async function refresh() {
  if (!state.connected) return state;
  const reply = await send({ cmd: "status" });
  if (!reply.ok || !reply.running) {
    clearProxy();
    setState({ connected: false, port: 0, error: reply.error || "" });
  }
  return state;
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    await restoreState();
    switch (msg.type) {
      case "connect": sendResponse(await connect(msg.serverId)); break;
      case "disconnect": sendResponse(await disconnect()); break;
      case "state": sendResponse(await refresh()); break;
      case "probe": sendResponse(await send({ cmd: "status" })); break;
      default: sendResponse({ ok: false, error: "unknown" });
    }
  })();
  return true; // async sendResponse
});

chrome.alarms.onAlarm.addListener((a) => { if (a.name === "keepalive" && port) send({ cmd: "ping" }); });

// A browser restart kills the host but Chrome restores its proxy setting from the profile —
// drop it so the user is never left with a dead proxy configured.
chrome.runtime.onStartup.addListener(async () => {
  await restoreState();
  clearProxy();
  setState({ connected: false, port: 0, error: "" });
});

restoreState();
