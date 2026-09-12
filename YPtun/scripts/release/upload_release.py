"""Uploads every file of a release folder to an existing GitHub release — and keeps at it.

GitHub takes an asset in ONE request (no ranges, no resume), so an interrupted upload has to start that
file over. What this does instead of giving up:
  * retries each file until it lands — no attempt limit;
  * a stall (no bytes accepted for STALL_SEC) counts as a break, not a hang;
  * after a break it waits until GitHub is reachable again, then continues;
  * a half-uploaded leftover ("starter") of that name is deleted first, or GitHub refuses the name;
  * files already on the release are never touched, so a break costs at most the current file;
  * smallest first, so progress is banked early.
Ends when every local file is on the release with the same size.

    python -u upload_release.py <release_id> <folder>
"""
import http.client, json, os, subprocess, sys, time, urllib.error, urllib.parse, urllib.request

REPO = "yanisplugg/olcvpn-client"
RID = sys.argv[1]
DIR = sys.argv[2]
STALL_SEC = 120
CHUNK = 256 * 1024
# The PAT is embedded in the origin URL of this checkout.
remote = subprocess.check_output(
    ["git", "-C", os.path.dirname(os.path.abspath(__file__)), "remote", "get-url", "origin"], text=True
).strip()
TOKEN = remote.split("://", 1)[1].split("@", 1)[0].split(":", 1)[1]
AUTH = {"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def wait_online():
    announced = False
    while True:
        try:
            urllib.request.urlopen("https://api.github.com/zen", timeout=15).read()
            if announced:
                log("связь вернулась")
            return
        except Exception:
            if not announced:
                log("нет связи с GitHub — жду")
                announced = True
            time.sleep(15)


def api(method, path):
    while True:
        wait_online()
        try:
            req = urllib.request.Request(f"https://api.github.com{path}", method=method, headers=AUTH)
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            if e.code == 404 and method == "DELETE":
                return None
            if e.code < 500:
                raise
            time.sleep(10)
        except Exception:
            time.sleep(10)


def assets():
    return {a["name"]: a for a in api("GET", f"/repos/{REPO}/releases/{RID}/assets?per_page=100")}


def upload(name):
    path = os.path.join(DIR, name)
    size = os.path.getsize(path)
    ctype = ("application/vnd.android.package-archive" if name.endswith(".apk")
             else "application/vnd.debian.binary-package" if name.endswith(".deb")
             else "application/gzip" if name.endswith(".gz") else "application/octet-stream")
    conn = http.client.HTTPSConnection("uploads.github.com", timeout=STALL_SEC)
    try:
        conn.putrequest("POST", f"/repos/{REPO}/releases/{RID}/assets?name={urllib.parse.quote(name)}")
        for k, v in AUTH.items():
            conn.putheader(k, v)
        conn.putheader("Content-Type", ctype)
        conn.putheader("Content-Length", str(size))
        conn.endheaders()
        sent, mark, t0 = 0, 0, time.time()
        with open(path, "rb") as fh:
            while chunk := fh.read(CHUNK):
                conn.send(chunk)
                sent += len(chunk)
                if sent * 10 // size > mark:
                    mark = sent * 10 // size
                    rate = sent / max(time.time() - t0, 1) / 1024
                    log(f"  {name}: {mark * 10}% ({rate:.0f} КБ/с)")
        resp = conn.getresponse()
        body = resp.read()
        if resp.status != 201:
            raise RuntimeError(f"HTTP {resp.status}: {body[:200]!r}")
    finally:
        conn.close()


local = {f: os.path.getsize(os.path.join(DIR, f)) for f in os.listdir(DIR)
         if f.endswith((".apk", ".exe", ".patch.gz", ".deb"))}
log(f"файлов: {len(local)}, всего {sum(local.values()) / 1e9:.2f} ГБ")
while True:
    have = assets()
    done = {n for n, a in have.items() if a["state"] == "uploaded" and a["size"] == local.get(n)}
    todo = sorted((f for f in local if f not in done), key=local.get)
    log(f"на релизе: {len(done)}/{len(local)}")
    if not todo:
        break
    name = todo[0]
    leftover = have.get(name)
    if leftover:
        api("DELETE", f"/repos/{REPO}/releases/assets/{leftover['id']}")
    wait_online()
    log(f"гружу {name} ({local[name] / 1e6:.1f} МБ)")
    try:
        upload(name)
        log(f"готово: {name}")
    except Exception as e:
        log(f"обрыв на {name}: {e} — повторю")
        time.sleep(5)
log("ВСЕ ФАЙЛЫ НА РЕЛИЗЕ")
