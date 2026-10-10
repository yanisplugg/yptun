// Script-transport port of transport/mailru/mailru.go. Line-for-line the
// same protocol: tunnels packets through the "cursor" field of Mail.ru's
// public-document coauthoring WebSocket. Every constant, timing, and
// message shape below matches the native transport exactly - this file is
// a faithfulness test for the host API, not a rewrite of the protocol.
//
// Kept in step with the native transport (parity is tested byte for byte in
// transport/script/parity_test.go): the saveChanges "editor activity" stream,
// every cursor entry of a batched server message, and a failed keep-alive
// closing the socket so the transport reconnects.
//
// The Go<->JS packet boundary (write()/emit()) carries raw bytes as
// ArrayBuffers; mailru's own wire format needs those bytes as base64 text
// spliced into the cursor field, so this script calls the host's
// base64.encode/decode explicitly at that one seam - fast (native Go
// codec, no hand-rolled JS base64) and the only place bytes<->text
// conversion happens at all.

var USER_AGENT =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";
var CURSOR_RE = /"cursor":"[^;]+;([^"]+)"/g;
var KEEPALIVE_MS = 10000; // transport.DefaultConfig().KeepAliveInterval
var ACTIVITY_MIN_MS = 500; // editorActivityLoop: a random delay in [0.5 s, 5 s]
var ACTIVITY_MAX_MS = 5000;

// saveChanges message in the Mail.ru web client's format; the two %s are
// UserId and UserShortId in excelAdditionalInfo. Byte-identical to
// saveChangesMessageTemplate in mailru.go. isCoAuthoring/releaseLocks are
// false here (true for yandex.docs).
var SAVE_CHANGES_TEMPLATE_PARTS = [
  '42["message",{"type":"saveChanges","changes":"[\\"76;AgAAADEA//8BAOwbfF7pEAAALQEAAAMAAAAAAAAAAAAAAAAAAAAAAAAA9v///xoAAAAyADAAMgA2AC4AMgAuADEALgAyADIANgA4AA==\\",\\"35;BgAAADYAMgA3AAEAHAABAAAAAAAAAAEAAABhAAAAAAMAAAA=\\",\\"35;BgAAADYAMgA3AAEAHAABAAAAAQAAAAEAAABzAAAAAAMAAAA=\\",\\"35;BgAAADYAMgA3AAEAHAABAAAAAgAAAAEAAABkAAAAAAMAAAA=\\"]","startSaveChanges":true,"endSaveChanges":true,"isCoAuthoring":false,"isExcel":false,"deleteIndex":null,"excelAdditionalInfo":"{\\"UserId\\":\\"',
  '\\",\\"UserShortId\\":\\"',
  '\\",\\"CursorInfo\\":\\"14;BgAAADYAMgA3AAMAAAA=\\"}","unlock":false,"releaseLocks":false}]',
];
var MAX_RECONNECT_ATTEMPTS = 999999; // transport.DefaultConfig().MaxReconnectAttempts

var weblink = "";
var running = false;
var sock = null;
var baseUserID = "";
var userID = null;
var userCounter = 0;
var connectedAt = null;
var keepAliveTimer = null;
var activityTimer = null;
var docInfo = null; // the live session's fetchDocInfo result (editor user id for saveChanges)
var connectGen = 0; // bumped by every connectToDoc: a stale attempt closes what it opened
var reconnectTimer = null; // at most one reconnect pending

function pad(n, width) {
  var s = String(n);
  while (s.length < width) s = "0" + s;
  return s;
}

function randUserID() {
  return pad(Math.floor(Math.random() * 1000000000), 10);
}

// buildSaveChanges is the saveChanges message for one participant: UserId is
// the editor's real user id (the session's generated one when the server gave
// none), UserShortId the same without its last character. Same as
// BuildSaveChanges in mailru.go.
function buildSaveChanges(userId) {
  var short = userId;
  if (short.length > 1) short = short.slice(0, short.length - 1);
  return (
    SAVE_CHANGES_TEMPLATE_PARTS[0] + userId +
    SAVE_CHANGES_TEMPLATE_PARTS[1] + short +
    SAVE_CHANGES_TEMPLATE_PARTS[2]
  );
}

// cursorPayloads: the base64 payload of every cursor entry in a server
// message, in order, without the keep-alive entries (a batched message may
// carry several, a peer's keep-alive among them).
function cursorPayloads(text) {
  var out = [];
  var m;
  CURSOR_RE.lastIndex = 0;
  while ((m = CURSOR_RE.exec(text)) !== null) {
    if (m[1] && m[1] !== "---KA---") out.push(m[1]);
  }
  return out;
}

// normalizeWeblink accepts either a bare weblink ("AbCdEfGh1/IjKlMnOp2") or
// a full public URL, same as NewMailruDocsTransport.
function normalizeWeblink(link) {
  link = String(link).trim();
  var prefixes = [
    "https://cloud.mail.ru/public/",
    "http://cloud.mail.ru/public/",
    "https://cloud.mail.ru/",
    "http://cloud.mail.ru/",
  ];
  for (var i = 0; i < prefixes.length; i++) {
    if (link.indexOf(prefixes[i]) === 0) {
      return link.slice(prefixes[i].length).replace(/^\/+|\/+$/g, "");
    }
  }
  return link;
}

// reconnectBackoff: exponential with jitter, capped at 15s - identical
// formula to reconnectBackoff() in mailru.go.
function reconnectBackoff(n) {
  if (n < 1) n = 1;
  var shift = n - 1;
  if (shift > 5) shift = 5;
  var d = 500 * Math.pow(2, shift);
  if (d > 15000) d = 15000;
  d += Math.floor(Math.random() * (d / 2 + 1));
  return d;
}

function scheduleReconnect(attempt) {
  var next = attempt + 1;
  if (!running || next >= MAX_RECONNECT_ATTEMPTS) return;
  if (reconnectTimer) return; // one is already pending: a second would open a duplicate session
  var d = reconnectBackoff(next);
  reconnectTimer = setTimeout(function () {
    reconnectTimer = null;
    if (!running) return;
    connectToDoc(next);
  }, d);
}

// fetchDocInfo POSTs to Mail.ru's public-document editor API and returns
// the fields needed to open the collaborative WebSocket - same endpoint,
// headers and field extraction as fetchDocInfo() in mailru.go.
async function fetchDocInfo(link) {
  var body = JSON.stringify({
    "x-email": "anonym",
    public: "/" + link,
    platform: "desktop_web",
  });
  var res = await http.fetch({
    url: "https://cloud.mail.ru/api/v4/r7/edit",
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json, text/plain, */*",
      "User-Agent": USER_AGENT,
      "X-Api-Version": "4",
      Referer: "https://cloud.mail.ru/public/" + link + "?weblink=" + link,
    },
    body: body,
  });
  if (res.status !== 200) {
    throw new Error("mailru: api returned status " + res.status);
  }
  var data = JSON.parse(res.body);
  var doc = data.document;
  if (!doc) throw new Error("mailru: document object missing");
  var editorConfig = data.editorConfig;
  if (!editorConfig) throw new Error("mailru: editorConfig object missing");

  var apiBase = data.api || "";
  var wsBase = apiBase.replace("https://", "wss://");
  return {
    token: data.token || "",
    docKey: doc.key,
    wsURL: wsBase + "/doc/" + doc.key + "/c/?EIO=4&transport=websocket",
    fileType: doc.fileType,
    docUrl: doc.url,
    docTitle: doc.title,
    // document.permissions is an object of booleans, not a number -
    // sending it as anything else makes the editor server reject the auth
    // message with "access deny" (same caveat as the native transport).
    permissions: doc.permissions || {},
    callbackUrl: editorConfig.callbackUrl || "",
    editorUserId: (editorConfig.user && editorConfig.user.id) || "",
  };
}

function startKeepAlive() {
  if (keepAliveTimer) clearInterval(keepAliveTimer);
  keepAliveTimer = setInterval(function () {
    if (!sock) return;
    try {
      sock.send('42["message",{"type":"cursor","cursor":"18;---KA---"}]');
    } catch (e) {
      // Same as the native keep-alive: a write that fails means a dead
      // connection - close it so the reconnect (via onclose) happens.
      setState("reconnecting", String(e));
      try {
        sock.close();
      } catch (e2) {}
    }
  }, KEEPALIVE_MS);
}

// editorActivityLoop: sends a saveChanges message at a random interval in
// [0.5 s, 5 s] so the stream looks like a live person editing. Skipped while
// there is no session (mid-reconnect), exactly like the native loop.
function startEditorActivity() {
  if (activityTimer) clearTimeout(activityTimer);
  var tick = function () {
    activityTimer = null;
    if (!running) return;
    if (sock && docInfo) {
      try {
        sock.send(buildSaveChanges(docInfo.editorUserId || userID));
      } catch (e) {
        // the next tick tries again; a dead socket is the keep-alive's to notice
      }
    }
    activityTimer = setTimeout(tick, ACTIVITY_MIN_MS + Math.floor(Math.random() * (ACTIVITY_MAX_MS - ACTIVITY_MIN_MS + 1)));
  };
  activityTimer = setTimeout(tick, ACTIVITY_MIN_MS + Math.floor(Math.random() * (ACTIVITY_MAX_MS - ACTIVITY_MIN_MS + 1)));
}

function handleMessage(text) {
  // Socket.IO ping/pong.
  if (text === "2") {
    if (sock) {
      try {
        sock.send("3");
      } catch (e) {}
    }
    return;
  }
  if (text === "3") return;

  if (text.indexOf('"type":"auth"') !== -1 && text.indexOf('"result":1') !== -1) {
    return; // auth ack - fire-and-forget, same as the native transport
  }

  if (text.indexOf("cursor") !== -1) {
    // One server message may carry several cursor entries (it batches them
    // under load), a peer's keep-alive among them: deliver every payload, in
    // order.
    var payloads = cursorPayloads(text);
    for (var i = 0; i < payloads.length; i++) {
      var bytes;
      try {
        bytes = base64.decode(payloads[i]); // cursor field is base64 text -> bytes
      } catch (e) {
        continue;
      }
      emit(bytes);
    }
  }
}

function onSocketClose(attempt) {
  sock = null;
  docInfo = null;
  var next = attempt;
  // A connection that had been up for >15s gets the fast (attempt=1)
  // backoff on its next try instead of continuing to climb - identical to
  // the `next = -1` branch in mailru.go's read-error handler.
  if (connectedAt !== null && Date.now() - connectedAt > 15000) {
    next = -1;
  }
  if (running) setState("reconnecting");
  scheduleReconnect(next);
}

function connectToDoc(attempt) {
  if (!running) return;
  var myGen = ++connectGen;

  (async function () {
    try {
      var info = await fetchDocInfo(weblink);
      if (myGen !== connectGen || !running) return; // superseded while fetching
      var newSock = await ws.open(info.wsURL, {
        "User-Agent": USER_AGENT,
        Origin: "https://docs.datacloudmail.ru",
      });
      if (myGen !== connectGen || !running) {
        try {
          newSock.close();
        } catch (e) {}
        return;
      }

      sock = newSock;
      docInfo = info;
      if (userID === null) {
        userID = baseUserID + pad(userCounter++ % 1000, 3);
      }

      sock.onmessage = handleMessage;
      sock.onclose = function () {
        if (sock !== newSock) return; // a socket we already replaced or dropped on purpose
        onSocketClose(attempt);
      };

      // Auth fires immediately, same as the native transport: Mail.ru's
      // coauthoring server buffers these until its own session state
      // catches up, and waiting for an explicit ack here only stretches
      // the outage window on every reconnect.
      sock.send('40{"token":"' + info.token + '"}');

      var authMsg = {
        type: "auth",
        docid: info.docKey,
        documentCallbackUrl: info.callbackUrl,
        token: "fghhfgsjdgfjs",
        user: { id: info.editorUserId, username: userID, indexUser: -1 },
        editorType: 0,
        lastOtherSaveTime: -1,
        block: [],
        documentFormatSave: 65,
        view: false,
        isCloseCoAuthoring: false,
        openCmd: {
          c: "open",
          id: info.docKey,
          userid: info.editorUserId,
          format: info.fileType,
          url: info.docUrl,
          title: info.docTitle,
          lcid: 25,
          nobase64: true,
          convertToOrigin: ".pdf.xps.oxps.djvu",
        },
        lang: "ru",
        mode: "edit",
        permissions: info.permissions,
        IsAnonymousUser: false,
        timezoneOffset: -180,
        coEditingMode: "fast",
        jwtOpen: info.token,
        time: 1000,
        supportAuthChangesAck: true,
      };
      sock.send('42' + JSON.stringify(["message", authMsg]));

      connectedAt = Date.now();
      setState("connected");
    } catch (e) {
      setState("reconnecting", String(e));
      scheduleReconnect(attempt);
    }
  })();
}

var Transport = {
  info: function () {
    return {
      name: "mailru",
      version: "1.1.0",
      cookieDomain: "https://cloud.mail.ru/",
      mtu: 0, // unbounded - native mailru never fragments either
      reliable: false,
      ordered: false,
      halfDuplex: false,
      minIntervalMs: 0,
      params: [
        {
          key: "url",
          label: "Mail.ru public document link or weblink",
          type: "url",
          required: true,
        },
      ],
    };
  },

  open: function (cfg) {
    var raw = (cfg.params && cfg.params.url) || cfg.url || "";
    weblink = normalizeWeblink(raw);
    baseUserID = randUserID();
    running = true;
    startKeepAlive();
    startEditorActivity();
    connectToDoc(0);
  },

  write: function (bytes) {
    if (!sock) throw new Error("mailru: not connected");
    sock.send('42["message",{"type":"cursor","cursor":"18;' + base64.encode(bytes) + '"}]');
  },

  close: function () {
    running = false;
    connectGen++;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = null;
    if (keepAliveTimer) clearInterval(keepAliveTimer);
    if (activityTimer) clearTimeout(activityTimer);
    activityTimer = null;
    if (sock) {
      try {
        sock.close();
      } catch (e) {}
    }
    sock = null;
  },

  // Generic cookie exchange (see host.go / transport.go FetchCookies /
  // ApplyCookies): Go reads cookies straight out of the shared jar with no
  // help from this script, but only the script can make an already-open
  // socket pick up freshly applied cookies - so it reacts to this event by
  // forcing a reconnect, same as ApplyCookies forcing t.session = nil in
  // the native transport.
  onEvent: function (kind) {
    if (kind === "cookiesApplied") {
      userID = null;
      docInfo = null;
      connectGen++; // drop an attempt in flight: the one below starts from the new cookies
      var old = sock;
      sock = null; // its onclose is ignored (sock !== newSock): one reconnect, below
      if (old) {
        try {
          old.close();
        } catch (e) {}
      }
      setState("reconnecting");
      scheduleReconnect(0);
    }
  },
};
