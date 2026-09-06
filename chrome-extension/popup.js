import { strings } from "./i18n.js";
import { parseLink } from "./link.js";

const $ = (id) => document.getElementById(id);
let S = strings("auto");
let servers = [];
let selected = null;
let state = { connected: false, serverId: null, via: "", error: "" };

const ask = (msg) => chrome.runtime.sendMessage(msg).catch((e) => ({ ok: false, error: String(e) }));

/** Turns a probe code from the service worker into a sentence that names the actual problem. */
function errorText(code, srv) {
  const where = srv ? `${srv.host}:${srv.port}` : "";
  if (code === "auth") return S.errAuth;
  if (code === "unreachable") return S.errUnreachable.replace("%s", where);
  const http = /^http:(\d+)$/.exec(code);
  return http ? S.errHttp.replace("%s", http[1]) : code;
}

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
  $("f-name").placeholder = S.name;
  $("f-link").placeholder = S.link;
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
    ? errorText(state.error, active)
    : state.connected
      ? `${S.connected} · ${S.via} ${state.via}`
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
    li.onclick = () => { selected = srv.id; paintServers(); paintState(); };

    const name = document.createElement("span");
    name.className = "srv-name";
    name.textContent = srv.name;

    const chip = document.createElement("span");
    chip.className = "chip";
    chip.textContent = `${srv.host}:${srv.port}`;

    const del = document.createElement("button");
    del.className = "del";
    del.textContent = "×";
    del.title = S.remove;
    del.onclick = async (e) => {
      e.stopPropagation();
      if (state.connected && state.serverId === srv.id) await ask({ type: "disconnect" });
      servers = servers.filter((s) => s.id !== srv.id);
      if (selected === srv.id) selected = servers[0]?.id ?? null;
      await chrome.storage.local.set({ servers });
      paintServers();
      await refresh();
    };

    li.append(name, chip, del);
    list.append(li);
  }
}

// ── editor ─────────────────────────────────────────────────────────────────────

async function saveServer() {
  let parsed;
  try {
    parsed = parseLink($("f-link").value);
  } catch {
    $("editor-error").textContent = S.badLink;
    $("editor-error").hidden = false;
    return;
  }
  const srv = { id: crypto.randomUUID(), ...parsed, name: $("f-name").value.trim() || parsed.name };
  servers.push(srv);
  selected = srv.id;
  await chrome.storage.local.set({ servers });
  $("editor").hidden = true;
  paintServers();
  paintState();
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
    if (reply?.state) { state = reply.state; paintState(); return; }
  }
  await refresh();
};

$("add").onclick = () => {
  $("editor").hidden = false;
  $("editor-error").hidden = true;
  $("f-name").value = "";
  $("f-link").value = "";
  $("f-link").focus();
};
$("cancel").onclick = () => { $("editor").hidden = true; };
$("save").onclick = saveServer;

$("lang").onchange = async () => {
  await chrome.storage.local.set({ lang: $("lang").value });
  S = strings($("lang").value);
  paintLabels();
  paintServers();
};

$("bypass").onchange = () => chrome.storage.local.set({ bypass: $("bypass").value });

async function refresh() {
  const reply = await ask({ type: "state" });
  if (reply?.state) state = reply.state;
  paintState();
}

(async function init() {
  const store = await chrome.storage.local.get(["servers", "activeId", "lang", "bypass"]);
  servers = store.servers || [];
  selected = store.activeId || servers[0]?.id || null;
  $("lang").value = store.lang || "auto";
  $("bypass").value = store.bypass || "";
  S = strings($("lang").value);
  paintLabels();
  paintServers();
  await refresh();
})();
