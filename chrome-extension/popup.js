import { strings } from "./i18n.js";

const $ = (id) => document.getElementById(id);
let S = strings("auto");
let servers = [];
let selected = null;
let state = { connected: false, port: 0, serverId: null, error: "" };

const ask = (msg) => chrome.runtime.sendMessage(msg).catch((e) => ({ ok: false, error: String(e) }));

// ── rendering ──────────────────────────────────────────────────────────────────

function paintLabels() {
  $("lbl-servers").textContent = S.servers;
  $("lbl-settings").textContent = S.settings;
  $("lbl-language").textContent = S.language;
  $("lbl-bypass").textContent = S.bypass;
  $("empty").textContent = S.noServers;
  $("add").title = S.add;
  $("save").textContent = S.save;
  $("cancel").textContent = S.cancel;
  $("copy-cmd").textContent = S.copy;
  $("warn-title").textContent = S.hostMissing;
  $("warn-hint").textContent = S.hostHint;
  $("f-name").placeholder = S.name;
  $("lang").options[0].textContent = S.auto;
  paintState();
}

function paintState() {
  const power = $("power");
  power.classList.toggle("on", state.connected);
  const active = servers.find((s) => s.id === (state.serverId || selected));
  $("active-name").textContent = active?.name || S.title;
  const sub = $("state");
  sub.classList.toggle("on", state.connected);
  sub.textContent = state.error
    ? state.error
    : state.connected
      ? `${S.connected} · ${S.socksPort}${state.port}`
      : S.disconnected;
  power.title = state.connected ? S.disconnect : S.connect;
}

function paintServers() {
  const list = $("servers");
  list.replaceChildren();
  $("empty").hidden = servers.length > 0;
  for (const srv of servers) {
    const li = document.createElement("li");
    li.className = srv.id === selected ? "active" : "";
    li.onclick = () => { selected = srv.id; chrome.storage.local.set({ lastServerId: srv.id }); paintServers(); paintState(); };

    const name = document.createElement("span");
    name.className = "srv-name";
    name.textContent = srv.name;

    const chip = document.createElement("span");
    chip.className = "chip";
    chip.textContent = srv.kind === "awg" ? S.awg : S.vless;

    const del = document.createElement("button");
    del.className = "del";
    del.textContent = "×";
    del.title = S.remove;
    del.onclick = async (e) => {
      e.stopPropagation();
      servers = servers.filter((s) => s.id !== srv.id);
      if (selected === srv.id) selected = servers[0]?.id ?? null;
      await chrome.storage.local.set({ servers });
      paintServers();
      paintState();
    };

    li.append(name, chip, del);
    list.append(li);
  }
}

// ── editor ─────────────────────────────────────────────────────────────────────

function openEditor() {
  $("editor").hidden = false;
  $("f-name").value = "";
  $("f-config").value = "";
  syncConfigPlaceholder();
  $("f-name").focus();
}

function syncConfigPlaceholder() {
  $("f-config").placeholder = $("f-kind").value === "awg" ? S.configAwg : S.configVless;
}

async function saveServer() {
  const config = $("f-config").value.trim();
  if (!config) return;
  const kind = $("f-kind").value;
  const name = $("f-name").value.trim() || guessName(config, kind);
  const srv = { id: crypto.randomUUID(), name, kind, config };
  servers.push(srv);
  selected = srv.id;
  await chrome.storage.local.set({ servers });
  $("editor").hidden = true;
  paintServers();
  paintState();
}

/** A readable fallback name: the vless:// fragment, or the AmneziaWG endpoint host. */
function guessName(config, kind) {
  if (kind === "vless") {
    const hash = config.indexOf("#");
    if (hash >= 0) return decodeURIComponent(config.slice(hash + 1)) || "VLESS";
    return config.replace(/^vless:\/\/[^@]*@/, "").split(/[?#]/)[0] || "VLESS";
  }
  const ep = /Endpoint\s*=\s*(\S+)/i.exec(config);
  return ep ? ep[1] : "AmneziaWG";
}

// ── wiring ─────────────────────────────────────────────────────────────────────

$("power").onclick = async () => {
  const power = $("power");
  if (state.connected) {
    await ask({ type: "disconnect" });
  } else {
    if (!selected) return;
    power.classList.add("busy");
    $("state").textContent = S.connecting;
    const reply = await ask({ type: "connect", serverId: selected });
    power.classList.remove("busy");
    if (!reply?.ok) state = { ...state, connected: false, error: reply?.error || "" };
  }
  await refresh();
};

$("add").onclick = openEditor;
$("cancel").onclick = () => { $("editor").hidden = true; };
$("save").onclick = saveServer;
$("f-kind").onchange = syncConfigPlaceholder;

$("lang").onchange = async () => {
  await chrome.storage.local.set({ lang: $("lang").value });
  S = strings($("lang").value);
  paintLabels();
  paintServers();
};

$("bypass").onchange = () => chrome.storage.local.set({ bypass: $("bypass").value });

$("copy-cmd").onclick = async () => {
  await navigator.clipboard.writeText($("install-cmd").textContent);
  $("copy-cmd").textContent = S.copied;
  setTimeout(() => ($("copy-cmd").textContent = S.copy), 1500);
};

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === "state") { state = msg.state; paintState(); }
});

async function refresh() {
  const fresh = await ask({ type: "state" });
  if (fresh && typeof fresh.connected === "boolean") state = fresh;
  paintState();
}

/** The host is only reachable once it's registered; until then show the one-liner that does it. */
async function checkHost() {
  const probe = await ask({ type: "probe" });
  const missing = !probe?.ok;
  $("host-warning").hidden = !missing;
  if (missing) {
    const exe = navigator.userAgent.includes("Windows") ? "yptunhost.exe" : "./yptunhost";
    $("install-cmd").textContent = `${exe} --install ${chrome.runtime.id}`;
  }
}

(async function init() {
  const store = await chrome.storage.local.get(["servers", "lastServerId", "lang", "bypass"]);
  servers = store.servers || [];
  selected = store.lastServerId || servers[0]?.id || null;
  $("lang").value = store.lang || "auto";
  $("bypass").value = store.bypass || "";
  S = strings($("lang").value);
  paintLabels();
  paintServers();
  await refresh();
  await checkHost();
})();
