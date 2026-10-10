// Script-transport port of transport/cupsonline/cupsonline.go. Smuggles
// packets through a real Centrifugo pub/sub channel: data rides as
// {row, column} integer pairs in a "shared_editor_change_cursors" RPC
// (cups.online rejects a non-numeric column but relays a cursor array of
// integers verbatim, in order), split across N=4 independent rooms for
// throughput and redundancy, round-robined on send.
//
// Each room needs its OWN cookie jar (its own csrftoken/session) - see
// http.newSession() in transport/script/host.go, added specifically for
// this: cookie names collide across sessions on one domain, so a single
// shared jar would let the last room's authorize() call silently clobber
// every earlier room's cookies. With isolated sessions, joining all rooms
// at once (Promise.all, matching native joinListed) is both safe and
// faster - no race to avoid, so no reason to serialize it.
//
// An exit (params.exit, which the core passes to every script transport)
// started without rooms - or with only closed ones - creates N fresh rooms and
// reports the packed list with raise("roomList", {rooms}) so the app can put
// it into the share link; a client never creates rooms. Kept in step with the
// native transport (parity is tested in transport/script/parity_test.go).

var UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";
var BASE_ROOM_URL = "https://interview.cups.online/live-coding/";

var NUM_ROOMS = 4;
var WS_HANDSHAKE_MS = 15000;
var WS_READ_TIMEOUT_MS = 90000;
var KEEPALIVE_MS = 20000;
var RECONNECT_MIN_MS = 100;
var RECONNECT_MAX_MS = 10000;
var RECONNECT_MULTIPLIER = 1.3;
var ROOM_GONE_RETRY_MIN_MS = 60000;
var ROOM_GONE_RETRY_MAX_MS = 30 * 60000;
var ROOM_DEAD_AFTER_FAILS = 5;

var BATCH_MAX_PACKETS = 256;
var BATCH_MAX_BYTES = 11000;
var MAX_MESSAGE_DATA = 4096;
var BATCH_TIMEOUT_MS = 2;
var SEND_INTERVAL_MS = 18;
var MAX_PAYLOAD_BYTES = 65535;
var BYTES_PER_NUMBER = 6;

var ROOM_RE = /data-room="\{&quot;uuid&quot;:\s*&quot;([0-9a-f-]{36})&quot;/;
var USER_RE = /data-user="\{&quot;uuid&quot;:\s*&quot;([0-9a-f-]{36})&quot;/;
var CONN_TOKEN_RE = /<meta[^>]+name="centrifuge-connection-token"[^>]+content="([^"]+)"/;
var CONN_URL_RE = /<meta[^>]+name="centrifuge-connection-url"[^>]+content="([^"]+)"/;
var SUB_URL_RE = /<meta[^>]+name="centrifuge-subscription-token-url"[^>]+content="([^"]+)"/;

var ROOM_CREATE_PAUSE_MS = 500;
var ROOM_CREATE_ATTEMPTS = 6;

var running = false;
var isClient = true;
var rooms = [];
var rrIndex = 0;

// ---- room list packing (base64.RawURLEncoding of a JSON array, native's
// packRooms/unpackRooms) - no host primitive for that alphabet, so it's a
// thin substitution wrapper around base64.encode/decode. ----

function b64urlDecode(s) {
  var b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  var pad = (4 - (b64.length % 4)) % 4;
  for (var i = 0; i < pad; i++) b64 += "=";
  return base64.decode(b64);
}

function b64urlEncode(bytes) {
  return base64.encode(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// packRooms: the base64url (no padding) of a JSON array of room uuids, as
// packRooms in cupsonline.go.
function packRooms(ids) {
  return b64urlEncode(text.encode(JSON.stringify(ids)));
}

function parseRoomList(rawURL) {
  rawURL = (rawURL || "").trim();
  if (!rawURL) return [];
  try {
    var p = url.parse(rawURL);
    var qs = parseQuery(p.search);
    if (qs.rooms) {
      var ids = JSON.parse(text.decode(b64urlDecode(qs.rooms)));
      if (Array.isArray(ids)) return ids;
    } else if (qs.room) {
      return [qs.room];
    }
  } catch (e) { /* not a URL with query params - fall through */ }
  try {
    var direct = JSON.parse(text.decode(b64urlDecode(rawURL)));
    if (Array.isArray(direct)) return direct;
  } catch (e) {}
  return [];
}

function parseQuery(qs) {
  var out = {};
  if (!qs) return out;
  var parts = qs.split("&");
  for (var i = 0; i < parts.length; i++) {
    if (!parts[i]) continue;
    var kv = parts[i].split("=");
    out[decodeURIComponent(kv[0])] = decodeURIComponent((kv[1] || "").replace(/\+/g, "%20"));
  }
  return out;
}

function originOf(u) {
  var p = url.parse(u);
  return p.protocol + "//" + p.host;
}

// ---- per-room object ----

function Room(idx, id) {
  this.idx = idx;
  this.id = id;
  this.session = http.newSession();
  this.auth = null; // { roomUUID, userUUID, connToken, connURL, subURL, subToken, channel, csrfToken, cookieHeader }
  this.sock = null;
  this.connected = false;
  this.dead = false;
  this.goneDelay = ROOM_GONE_RETRY_MIN_MS;
  this.reconnectDelay = RECONNECT_MIN_MS;
  this.fails = 0;
  this.rpcId = 2;
  this.pendingWaiters = [];
  this.sendQueue = [];
  this.batch = [];
  this.batchBytes = 0;
  this.batchTimer = null;
  // Batches go out one after another (the native sendLoop is one goroutine per
  // room): a batch is cut into several messages, and the far side puts them
  // back together by stream order, so two batches sending at once would
  // interleave their pieces and corrupt both.
  this.sendChain = Promise.resolve();
  this.lastSend = 0;
  this.recvBufs = {}; // per sender (room member): a packet cut across messages stays with its sender
  this.needJoin = true;
}

Room.prototype.nextID = function () { return ++this.rpcId; };

Room.prototype.joinURL = function () {
  return BASE_ROOM_URL + "?room=" + this.id;
};

// authorize (this room's own session -> own jar, own csrftoken).
Room.prototype.authorize = function () {
  return this.authorizeAt(this.joinURL(), this.id);
};

// authorizeAt loads a room page and collects what it takes to join the room's
// channel. expectId, when set, is the room it must turn out to be: a closed
// room can come back as a fresh one (another uuid), which counts as gone.
Room.prototype.authorizeAt = async function (roomURL, expectId) {
  var res = await this.session.fetch({
    url: roomURL,
    headers: {
      "User-Agent": UA,
      Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "ru-RU,ru;q=0.9",
    },
  });
  if (res.status === 404 || res.status === 410) {
    throw new RoomGoneError("GET room status " + res.status);
  }
  if (res.status !== 200) throw new Error("GET room status " + res.status);

  var html = res.body;
  var roomUUID = firstMatch(ROOM_RE, html);
  var userUUID = firstMatch(USER_RE, html);
  var connToken = firstMatch(CONN_TOKEN_RE, html);
  var connURL = firstMatch(CONN_URL_RE, html);
  var subURL = firstMatch(SUB_URL_RE, html);

  if (!roomUUID || !userUUID) throw new RoomGoneError("room/user uuid missing");
  if (expectId && roomUUID !== expectId) throw new RoomGoneError("вместо неё выдана новая " + roomUUID.slice(0, 8));
  if (!connToken || !connURL || !subURL) throw new Error("centrifuge meta missing");

  var cookies = this.session.cookies.get(roomURL);
  var csrfToken = cookies["csrftoken"] || "";
  if (!csrfToken) throw new Error("csrftoken missing");

  var channel = "$shared_editor:room-" + roomUUID;

  var subRes = await this.session.fetch({
    url: subURL,
    method: "POST",
    headers: {
      "User-Agent": UA,
      "Content-Type": "application/json",
      "X-CSRFToken": csrfToken,
      Origin: originOf(roomURL),
      Referer: roomURL,
    },
    body: JSON.stringify({ channel: channel }),
  });
  if (subRes.status !== 200) throw new Error("sub token status " + subRes.status);
  var subToken = (JSON.parse(subRes.body) || {}).token;
  if (!subToken) throw new Error("empty sub token");

  var finalCookies = this.session.cookies.get(roomURL);
  var cookieHeader = Object.keys(finalCookies).map(function (k) { return k + "=" + finalCookies[k]; }).join("; ");

  this.auth = {
    roomUUID: roomUUID, userUUID: userUUID, connToken: connToken,
    connURL: connURL, subURL: subURL, subToken: subToken,
    channel: channel, cookieHeader: cookieHeader,
  };
};

function RoomGoneError(msg) {
  this.message = msg;
  this.isRoomGone = true;
}
RoomGoneError.prototype = Object.create(Error.prototype);

function firstMatch(re, s) {
  var m = re.exec(s);
  return m ? m[1] : "";
}

// ---- reply waiting (a frame may pack several JSON lines; a ping "{}"
// answers itself; anything else is either a reply to a pending id or a
// push) ----

function isPing(line) { return line === "{}"; }

Room.prototype.waitForReply = function (id, timeoutMs) {
  var room = this;
  return new Promise(function (resolve, reject) {
    var waiter = { done: false };
    waiter.pred = function (line) {
      if (isPing(line)) return false;
      var reply;
      try { reply = JSON.parse(line); } catch (e) { return false; }
      return reply && reply.id === id;
    };
    var timer = setTimeout(function () {
      if (waiter.done) return;
      waiter.done = true;
      var i = room.pendingWaiters.indexOf(waiter);
      if (i >= 0) room.pendingWaiters.splice(i, 1);
      reject(new Error("timeout waiting for reply " + id));
    }, timeoutMs);
    waiter.settle = function (line) {
      if (waiter.done) return;
      waiter.done = true;
      clearTimeout(timer);
      var reply = JSON.parse(line);
      if (reply.error) {
        var err = new Error(reply.error.code + " " + reply.error.message);
        err.isRefused = true; // Centrifugo turned the token down - only a fresh join fixes that
        reject(err);
      } else {
        resolve(reply);
      }
    };
    room.pendingWaiters.push(waiter);
  });
};

Room.prototype.dispatchToWaiters = function (line) {
  for (var i = 0; i < this.pendingWaiters.length; i++) {
    var w = this.pendingWaiters[i];
    if (w.pred(line)) {
      this.pendingWaiters.splice(i, 1);
      w.settle(line);
      return true;
    }
  }
  return false;
};

Room.prototype.writeRaw = function (data) {
  if (!this.sock) throw new Error("ws not connected");
  try {
    this.sock.send(data);
  } catch (e) {
    // A failed (or timed-out) write leaves the socket unusable: close it so
    // the read side notices and the room reconnects, instead of sitting on a
    // dead channel until the read timeout - as the native writeRaw does.
    try { this.sock.close(); } catch (e2) {}
    throw e;
  }
};

Room.prototype.writeJSON = function (v) {
  this.writeRaw(JSON.stringify(v));
};

// ---- connect + handshake ----

Room.prototype.wsURL = function () {
  var u = this.auth.connURL.replace("https://", "wss://").replace("http://", "ws://");
  return u.replace(/\/+$/, "") + "/websocket";
};

Room.prototype.connectAndServe = async function () {
  var room = this;
  var sock = await ws.open(this.wsURL(), {
    Origin: originOf(this.auth.connURL),
    "User-Agent": UA,
    Cookie: this.auth.cookieHeader,
  }, { readTimeoutMs: WS_READ_TIMEOUT_MS });

  this.sock = sock;
  // Fresh socket, fresh stream: drop any half-assembled packet from before.
  this.recvBufs = {};

  sock.onmessage = function (raw) { room.onFrame(raw); };
  var closed = new Promise(function (resolve) {
    sock.onclose = function (reason) { resolve(reason); };
  });

  // Centrifugo hanging up mid-handshake is how it can turn our tokens down
  // without a word: only a fresh join fixes that, so it counts as a refusal.
  var hungUp = closed.then(function (reason) {
    var err = new Error("refused: connection closed during the handshake: " + reason);
    err.isRefused = true;
    throw err;
  });
  hungUp.catch(function () {}); // consumed below; no unhandled rejection if the handshake wins

  this.writeJSON({ id: 1, connect: { token: this.auth.connToken, name: "js" } });
  await Promise.race([this.waitForReply(1, WS_HANDSHAKE_MS), hungUp]);

  this.writeJSON({ id: 2, subscribe: { channel: this.auth.channel, token: this.auth.subToken } });
  await Promise.race([this.waitForReply(2, WS_HANDSHAKE_MS), hungUp]);

  this.connected = true;
  this.markAlive();
  this.reconnectDelay = RECONNECT_MIN_MS;
  this.fails = 0;
  setState("connected");

  var ka = setInterval(function () {
    try {
      room.writeJSON({
        rpc: { method: "shared_editor_ping", data: { room: room.auth.roomUUID, user: room.auth.userUUID } },
        id: room.nextID(),
      });
    } catch (e) {}
  }, KEEPALIVE_MS);

  await closed;
  clearInterval(ka);
  this.connected = false;
  this.sock = null;
};

Room.prototype.onFrame = function (raw) {
  var lines = raw.split("\n");
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (!line) continue;
    if (this.dispatchToWaiters(line)) continue;
    this.handleLine(line);
  }
};

Room.prototype.handleLine = function (line) {
  if (isPing(line)) {
    try { this.writeRaw("{}"); } catch (e) {}
    return;
  }
  var obj;
  try { obj = JSON.parse(line); } catch (e) { return; }
  var push = obj && obj.push;
  var pub = push && push.pub;
  var data = pub && pub.data;
  if (!data || data.type !== "cursors_update") return;
  var payload = data.payload;
  if (!payload) return;
  if (payload.user_uuid === this.auth.userUUID) return; // our own echo
  var cursors = payload.cursors;
  if (!cursors || cursors.length === 0) return;

  var nums = [];
  for (var i = 0; i < cursors.length; i++) {
    var c = cursors[i];
    if (!c || typeof c.row !== "number" || c.row < 0) return;
    nums.push(c.row);
    nums.push(typeof c.column === "number" && c.column >= 0 ? c.column : 0);
  }
  var decoded = numbersToBytes(nums);
  if (decoded.length < 2) return;
  var dataLen = (decoded[0] << 8) | decoded[1];
  if (2 + dataLen > decoded.length) return;
  var chunk = decoded.slice(2, 2 + dataLen);

  // Append to this sender's running stream and pull out whole packets; a
  // packet split across messages completes once the rest of it arrives.
  var sender = payload.user_uuid || "";
  var prev = this.recvBufs[sender] || new Uint8Array(0);
  var merged = new Uint8Array(prev.length + chunk.length);
  merged.set(prev, 0);
  merged.set(chunk, prev.length);

  var off = 0;
  while (merged.length - off >= 2) {
    var ln = (merged[off] << 8) | merged[off + 1];
    if (ln === 0) { off = merged.length; break; } // not a real length: the stream is out of sync
    if (merged.length - off < 2 + ln) break; // the rest of this packet has not arrived yet
    emit(merged.slice(off + 2, off + 2 + ln).buffer);
    off += 2 + ln;
  }
  var rest = merged.slice(off);
  if (rest.length === 0) {
    delete this.recvBufs[sender]; // a member with nothing pending holds no buffer
  } else if (rest.length > MAX_PAYLOAD_BYTES + MAX_MESSAGE_DATA) {
    delete this.recvBufs[sender]; // a stream that never yields a packet must not grow without bound
  } else {
    this.recvBufs[sender] = rest;
  }
};

function numbersToBytes(nums) {
  var out = new Uint8Array(nums.length * BYTES_PER_NUMBER);
  for (var i = 0; i < nums.length; i++) {
    var v = nums[i];
    for (var j = BYTES_PER_NUMBER - 1; j >= 0; j--) {
      out[i * BYTES_PER_NUMBER + j] = v % 256;
      v = Math.floor(v / 256);
    }
  }
  return out;
}

function bytesToNumbers(blob) {
  var n = Math.ceil(blob.length / BYTES_PER_NUMBER);
  var out = [];
  for (var i = 0; i < n; i++) {
    var v = 0;
    for (var j = 0; j < BYTES_PER_NUMBER; j++) {
      var idx = i * BYTES_PER_NUMBER + j;
      v = v * 256 + (idx < blob.length ? blob[idx] : 0);
    }
    out.push(v);
  }
  return out;
}

// ---- send ----

Room.prototype.pace = function () {
  var room = this;
  var wait = SEND_INTERVAL_MS - (Date.now() - this.lastSend);
  if (wait <= 0) { this.lastSend = Date.now(); return Promise.resolve(); }
  return new Promise(function (resolve) {
    setTimeout(function () { room.lastSend = Date.now(); resolve(); }, wait);
  });
};

Room.prototype.sendChunk = async function (chunk) {
  var payload = new Uint8Array(2 + chunk.length);
  payload[0] = (chunk.length >> 8) & 0xff;
  payload[1] = chunk.length & 0xff;
  payload.set(chunk, 2);

  var nums = bytesToNumbers(payload);
  var cursors = [];
  for (var i = 0; i < nums.length; i += 2) {
    cursors.push({ row: nums[i], column: i + 1 < nums.length ? nums[i + 1] : 0 });
  }
  await this.pace();
  this.writeJSON({
    rpc: {
      method: "shared_editor_change_cursors",
      data: { cursors: cursors, ranges: [], room: this.auth.roomUUID, user: this.auth.userUUID },
    },
    id: this.nextID(),
  });
};

Room.prototype.sendBatch = async function (batch) {
  var totalRaw = 0;
  for (var i = 0; i < batch.length; i++) totalRaw += batch[i].length;
  var blob = new Uint8Array(totalRaw + batch.length * 2);
  var off = 0;
  for (i = 0; i < batch.length; i++) {
    var p = batch[i];
    blob[off] = (p.length >> 8) & 0xff;
    blob[off + 1] = p.length & 0xff;
    off += 2;
    blob.set(p, off);
    off += p.length;
  }
  for (off = 0; off < blob.length; off += MAX_MESSAGE_DATA) {
    var end = Math.min(off + MAX_MESSAGE_DATA, blob.length);
    await this.sendChunk(blob.slice(off, end));
  }
};

Room.prototype.flushBatch = function () {
  if (this.batchTimer) { clearTimeout(this.batchTimer); this.batchTimer = null; }
  if (this.batch.length === 0) return;
  var batch = this.batch;
  this.batch = [];
  this.batchBytes = 0;
  var room = this;
  this.sendChain = this.sendChain.then(function () {
    // The channel may have gone down while this batch waited its turn: by now
    // it is stale (new traffic has moved to a room that is up), so drop it
    // rather than deliver it late and out of order, like native.
    if (!room.connected) return;
    return room.sendBatch(batch);
  }).catch(function () { /* dropped, same as native */ });
};

Room.prototype.queuePacket = function (bytes) {
  var pkt = new Uint8Array(bytes);
  if (this.batch.length > 0 && this.batchBytes + 2 + pkt.length > BATCH_MAX_BYTES) {
    this.flushBatch();
  }
  this.batch.push(pkt);
  this.batchBytes += 2 + pkt.length;
  if (this.batch.length >= BATCH_MAX_PACKETS || this.batchBytes >= BATCH_MAX_BYTES) {
    this.flushBatch();
  } else if (this.batch.length === 1) {
    var room = this;
    this.batchTimer = setTimeout(function () { room.flushBatch(); }, BATCH_TIMEOUT_MS);
  }
};

Room.prototype.markDead = function () {
  if (this.dead) return;
  this.dead = true;
};

Room.prototype.markAlive = function () {
  this.goneDelay = ROOM_GONE_RETRY_MIN_MS;
  this.dead = false;
};

// ---- per-room run loop (join, retry with backoff, reconnect) ----

Room.prototype.retryDelay = function () {
  if (!this.dead) return this.reconnectDelay;
  var d = this.goneDelay;
  this.goneDelay = Math.min(2 * d, ROOM_GONE_RETRY_MAX_MS);
  return d;
};

Room.prototype.backoff = function () {
  this.reconnectDelay = Math.min(this.reconnectDelay * RECONNECT_MULTIPLIER, RECONNECT_MAX_MS);
};

Room.prototype.run = async function () {
  while (running) {
    if (this.needJoin || this.dead) {
      try {
        await this.authorize();
        this.needJoin = false;
      } catch (e) {
        if (e && e.isRoomGone) this.markDead();
        await sleep(this.retryDelay());
        this.backoff();
        continue;
      }
    }

    var wasReady = false;
    var refused = false;
    try {
      await this.connectAndServe();
      wasReady = true; // connectAndServe only returns (not throws) after a clean, subscribed session
    } catch (e) {
      wasReady = this.connected;
      refused = !!(e && e.isRefused);
    }
    this.connected = false;
    if (!running) return;

    if (wasReady) {
      this.reconnectDelay = RECONNECT_MIN_MS;
      this.fails = 0;
      this.needJoin = false;
    } else {
      this.fails++;
      if (this.fails >= ROOM_DEAD_AFTER_FAILS) this.markDead();
      this.needJoin = refused || this.fails % 2 === 0;
    }
    await sleep(this.retryDelay());
    this.backoff();
  }
};

function sleep(ms) {
  return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

// ---- Transport ----

function pickRoom() {
  var n = rooms.length;
  if (n === 0) return null;
  for (var i = 0; i < n; i++) {
    var r = rooms[(rrIndex + i) % n];
    if (r.connected) { rrIndex = (rrIndex + i + 1) % n; return r; }
  }
  return null;
}

// createRooms makes n fresh rooms (an exit started without any, or whose saved
// ones are all closed), as createRooms in cupsonline.go: every room loaded
// from the base page in a session of its own, retried with a wait that grows
// faster on a rate limit (403/429).
async function createRooms(n) {
  var out = [];
  var delay = ROOM_CREATE_PAUSE_MS;
  var lastErr = null;
  for (var i = 0; i < n; i++) {
    var room = null;
    for (var attempt = 0; attempt < ROOM_CREATE_ATTEMPTS; attempt++) {
      if (!running) return out;
      var candidate = new Room(out.length, "");
      try {
        await candidate.authorizeAt(BASE_ROOM_URL, "");
        room = candidate;
        break;
      } catch (e) {
        lastErr = e;
        var msg = String((e && e.message) || e);
        var wait = msg.indexOf("403") !== -1 || msg.indexOf("429") !== -1
          ? Math.min(delay * Math.pow(2, attempt), 30000)
          : delay * (attempt + 1);
        await sleep(wait);
      }
    }
    if (!room) continue;
    room.id = room.auth.roomUUID;
    room.needJoin = false;
    out.push(room);
    if (i < n - 1) await sleep(delay);
  }
  if (out.length === 0) throw new Error("could not create any room: " + ((lastErr && lastErr.message) || lastErr));
  return out;
}

// reportRooms tells the app which rooms this transport keeps channels to: the
// string a client needs (the exit's share link).
function reportRooms(ids) {
  raise("roomList", { rooms: packRooms(ids) });
}

function beginRooms() {
  setState("connected");
  for (var j = 0; j < rooms.length; j++) rooms[j].run();
}

// startRooms is enterRooms of cupsonline.go: join the listed rooms (all at
// once - on a phone each join is two round trips); one that was not reachable
// keeps being retried. When none could be joined: a client fails; an exit
// whose rooms are merely unreachable fails too (new rooms for a network hiccup
// would cost the phone its string for nothing); an exit whose rooms are all
// closed - or that had none - creates new ones.
async function startRooms(ids) {
  try {
    if (ids.length > 0) {
      rooms = [];
      for (var i = 0; i < ids.length; i++) rooms.push(new Room(i, ids[i]));
      var results = await Promise.all(rooms.map(function (r) {
        return r.authorize().then(
          function () { r.needJoin = false; return { ok: true }; },
          function (e) { if (e && e.isRoomGone) r.markDead(); return { ok: false, gone: !!(e && e.isRoomGone) }; }
        );
      }));
      if (!running) return;
      var joined = results.filter(function (x) { return x.ok; }).length;
      var allGone = results.every(function (x) { return x.ok || x.gone; });
      if (joined > 0) {
        reportRooms(ids);
        beginRooms();
        return;
      }
      if (isClient) {
        setState("dead", allGone ? "cupsonline: all rooms are gone" : "cupsonline: no rooms joined");
        return;
      }
      if (!allGone) {
        setState("dead", "cupsonline: saved rooms unreachable");
        return;
      }
    } else if (isClient) {
      setState("dead", "cupsonline: no room ids in url");
      return;
    }

    var created = await createRooms(NUM_ROOMS);
    if (!running) return;
    rooms = created;
    reportRooms(created.map(function (r) { return r.id; }));
    beginRooms();
  } catch (e) {
    setState("dead", String(e));
  }
}

var Transport = {
  info: function () {
    return {
      name: "cupsonline",
      version: "1.1.0",
      mtu: 0,
      reliable: false,
      ordered: false,
      params: [
        { key: "url", label: "Packed room list (?rooms=... or ?room=...); empty on an exit creates new rooms", type: "text", required: false },
      ],
    };
  },

  open: function (cfg) {
    var p = cfg.params || {};
    var raw = p.url || cfg.url || "";
    var ids = parseRoomList(raw);
    // The core tells every script transport its role (params.exit); a client
    // never creates rooms of its own - one built as an exit everywhere used to
    // create four new ones when it had none, and wait in them for an exit that
    // never came.
    isClient = !(p.exit === true || p.exit === "true");
    running = true;
    setState("connecting");
    startRooms(ids);
  },

  write: function (bytes) {
    if (bytes.byteLength > MAX_PAYLOAD_BYTES) {
      throw new Error("cupsonline: packet " + bytes.byteLength + " bytes over the " + MAX_PAYLOAD_BYTES + " limit");
    }
    var room = pickRoom();
    if (!room) throw new Error("cupsonline: no room connected");
    room.queuePacket(bytes);
  },

  close: function () {
    running = false;
    for (var i = 0; i < rooms.length; i++) {
      var r = rooms[i];
      if (r.batchTimer) clearTimeout(r.batchTimer);
      if (r.sock) { try { r.sock.close(); } catch (e) {} }
    }
    rooms = [];
  },
};
