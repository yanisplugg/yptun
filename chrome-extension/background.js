// Service worker: owns the chrome.proxy setting, the proxy password and the badge.
//
// Nothing is installed on this machine: the tunnel runs on the server (xray `http` inbound over TLS)
// and Chrome speaks to it natively, so there is no native host, no loopback port and nothing to keep
// alive — the proxy setting survives a suspended service worker and a browser restart on its own.

const DEFAULT_BYPASS = ["localhost", "127.0.0.1", "[::1]", "<local>"];
const PROBE_URL = "https://www.gstatic.com/generate_204";
const PROBE_MS = 8000;

const activeServer = async () => {
  const { servers = [], activeId } = await chrome.storage.local.get(["servers", "activeId"]);
  return servers.find((s) => s.id === activeId) || null;
};

// Chrome asks us for the proxy login instead of showing the browser's password box.
chrome.webRequest.onAuthRequired.addListener(
  (details, cb) => {
    if (!details.isProxy) return cb({});
    activeServer().then((s) =>
      cb(s?.user ? { authCredentials: { username: s.user, password: s.pass } } : {}));
  },
  { urls: ["<all_urls>"] },
  ["asyncBlocking"],
);

// ── proxy ──────────────────────────────────────────────────────────────────────

async function applyProxy(srv) {
  const { bypass = "" } = await chrome.storage.local.get("bypass");
  const extra = bypass.split(/[\s,\n]+/).map((s) => s.trim()).filter(Boolean);
  await chrome.proxy.settings.set({
    scope: "regular",
    value: {
      mode: "fixed_servers",
      rules: {
        singleProxy: { scheme: srv.scheme, host: srv.host, port: srv.port },
        bypassList: DEFAULT_BYPASS.concat(extra),
      },
    },
  });
}

const clearProxy = () =>
  new Promise((r) => chrome.proxy.settings.clear({ scope: "regular" }, () => r(void chrome.runtime.lastError)));

/**
 * The only honest test that the server side is really there: fetch through the proxy we just set.
 * Returns "" on success, else a CODE the popup turns into a sentence — the raw exception is always
 * the useless "Failed to fetch", which is what made every failure here undiagnosable.
 */
async function probe() {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), PROBE_MS);
  try {
    const r = await fetch(PROBE_URL, { cache: "no-store", signal: ctl.signal });
    if (r.status < 400) return "";
    return r.status === 407 ? "auth" : `http:${r.status}`;
  } catch {
    return "unreachable";
  } finally {
    clearTimeout(timer);
  }
}

// ── state ──────────────────────────────────────────────────────────────────────

/** Chrome's own proxy setting is the source of truth — no mirrored state to go stale. */
async function state(error = "") {
  const cfg = await chrome.proxy.settings.get({});
  const proxy = cfg.value?.rules?.singleProxy;
  const on = cfg.levelOfControl === "controlled_by_this_extension" && cfg.value?.mode === "fixed_servers";
  const { activeId = null } = await chrome.storage.local.get("activeId");
  badge(on);
  return { connected: on, serverId: on ? activeId : null, via: on && proxy ? `${proxy.host}:${proxy.port}` : "", error };
}

function badge(on) {
  chrome.action.setBadgeText({ text: on ? "ON" : "" });
  chrome.action.setBadgeBackgroundColor({ color: on ? "#2E7D32" : "#6E7176" });
}

// ── commands from the popup ────────────────────────────────────────────────────

async function connect(serverId) {
  const { servers = [] } = await chrome.storage.local.get("servers");
  const srv = servers.find((s) => s.id === serverId) || servers[0];
  if (!srv) return { ok: false, error: "no server" };

  await chrome.storage.local.set({ activeId: srv.id });

  // The same host:port may serve the http inbound behind TLS or in the clear, and the vless link
  // says nothing about it. Try the guess from the link, then the other one — cheaper than another
  // field in the popup, and it's the whole reason "I set the right port and it still didn't
  // connect" used to be a dead end.
  const schemes = srv.scheme === "http" ? ["http", "https"] : ["https", "http"];
  let err = "";
  for (const scheme of schemes) {
    await applyProxy({ ...srv, scheme });
    err = await probe();
    if (!err) {
      if (scheme !== srv.scheme) {
        // Remember what actually answered so the next connect gets it right on the first try.
        await chrome.storage.local.set({
          servers: servers.map((s) => (s.id === srv.id ? { ...s, scheme } : s)),
        });
      }
      return { ok: true, state: await state() };
    }
    if (err === "auth") break; // we reached the proxy; the credentials are what's wrong
  }
  await clearProxy(); // never leave the browser pointed at a proxy that doesn't answer
  return { ok: false, error: err, state: await state(err) };
}

async function disconnect() {
  await clearProxy();
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
