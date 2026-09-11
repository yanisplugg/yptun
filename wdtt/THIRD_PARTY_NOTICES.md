# Third-party notices

The client in `wdtt/` and the server in `../wdtt-server/` (plus the bundled `deploy.sh`) are
adapted from qWDTT, [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android),
commit `fae121e`, licensed under the GNU General Public License v3.0 — the same license as this
project (see `../LICENSE`). Local changes: the CLI client became a library (`Run`, host hooks for
CAPTCHA and VK auth instead of the stdin/stdout protocol) and gained a Windows listener.
