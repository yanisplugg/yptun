// Self-contained local echo transport: no network at all. open() goes
// straight to connected, write() loops the exact bytes back up via emit().
// A keepalive interval keeps this transport's goja loop alive (jobCount>=1),
// the liveness invariant the host layer relies on.
var Transport = {
  info: function () {
    return { name: "local-echo", version: "1.0.0" };
  },
  open: function (cfg) {
    console.log("local-echo open, url=" + (cfg && cfg.url));
    this._ka = setInterval(function () {}, 1000);
    setState("connected");
  },
  write: function (bytes) {
    // zero-copy ArrayBuffer straight back up the stack
    emit(bytes);
  },
  close: function () {
    if (this._ka) clearInterval(this._ka);
    console.log("local-echo close");
  },
};
