(() => {
    'use strict';

    const { local: LOCAL, upstream: UPSTREAM } = window.__ftpCaptcha ?? {};
    const PROXY = '/generic_proxy?proxy_url=';
    const RESULT = window.__ftpCaptcha?.resultPath;
    const POW_RESULT = window.__ftpCaptcha?.powPath;
    const CHECK = 'captchaNotRobot.check';

    function proxyUrl(abs) {
        try {
            const url = new URL(abs);
            if (url.protocol === 'https:') {
                return '/_p/' + url.host + url.pathname + url.search;
            }
        } catch (e) { /* use the legacy proxy URL below */ }
        return PROXY + encodeURIComponent(abs);
    }

    // In auto mode, use the actual browser's computed PoW envelope. This runs
    // before VK's inline PoW script, so its final assignment is observable.
    if (window.__ftpCaptcha?.capturePow && POW_RESULT) {
        let powValue = window.captchaPowResult;
        const capturePow = (value) => {
            if (typeof value !== 'string') return;
            const dot = value.indexOf('.');
            if (dot < 0) return;
            try {
                const envelope = JSON.parse(atob(value.slice(dot + 1)));
                if (!/^[0-9a-f]{64}$/.test(envelope.hash || '') || envelope.error || !envelope.telemetry) return;
                const body = `pow=${encodeURIComponent(value)}`;
                fetch(POW_RESULT, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body,
                }).then(() => showDone()).catch(() => {});
            } catch {}
        };
        Object.defineProperty(window, 'captchaPowResult', {
            configurable: true,
            enumerable: true,
            get: () => powValue,
            set: (value) => { powValue = value; capturePow(value); },
        });
    }

    function rewriteUrl(url) {
        if (!url || typeof url !== 'string') return url;
        if (url.startsWith(LOCAL)) return url;
        if (url.startsWith(UPSTREAM)) return LOCAL + url.slice(UPSTREAM.length);
        if (url.startsWith('//')) return proxyUrl(new URL(UPSTREAM).protocol + url);
        if (url.startsWith('http://') || url.startsWith('https://')) return proxyUrl(url);
        return url;
    }

    function rewriteAttr(el, attr) {
        const value = el.getAttribute?.(attr);
        if (!value) return;
        const rewritten = rewriteUrl(value);
        if (rewritten !== value) el.setAttribute(attr, rewritten);
    }

    function rewriteTree(root) {
        if (!root.querySelectorAll) return;
        for (const attr of ['href', 'src', 'action']) {
            root.querySelectorAll(`[${attr}]`).forEach((el) => rewriteAttr(el, attr));
        }
    }

    const extractToken = (data) => data?.response?.success_token;

    function sendToken(token) {
        if (!token || !RESULT) return;
        const body = `token=${encodeURIComponent(token)}`;

        // sendBeacon переживает уход со страницы (mobile Safari)
        if (navigator.sendBeacon?.(RESULT, new Blob([body], { type: 'application/x-www-form-urlencoded' }))) {
            showDone();
            return;
        }

        fetch(RESULT, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body,
        }).then(showDone).catch(() => {
            const form = document.createElement('form');
            form.method = 'POST';
            form.action = RESULT;
            const input = document.createElement('input');
            input.type = 'hidden';
            input.name = 'token';
            input.value = token;
            form.append(input);
            document.body.append(form);
            form.submit();
        });
    }

    function showPending() {
        document.body.style.background = '#000';
        const banner = document.createElement('div');
        banner.textContent = 'free turn proxy - captcha';
        banner.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:99999;padding:12px;text-align:center;font:13.5px ui-monospace,monospace;color:#e1e1e1;background:#000';
        document.body.append(banner);
    }

    function showDone() {
        document.body.style.background = '#000';
        document.body.innerHTML =
            '<div style="min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;font-family:ui-monospace,monospace;text-align:center">' +
            '<div style="font-size:16.5px;font-weight:700;color:#e1e1e1">free turn proxy</div>' +
            '<div style="font-size:13.5px;color:#818181">gg</div></div>';
        setTimeout(() => window.close(), 1000);
    }

    const xhrOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (...args) {
        if (typeof args[1] === 'string') {
            this._origUrl = args[1];
            args[1] = rewriteUrl(args[1]);
        }
        return xhrOpen.apply(this, args);
    };

    const xhrSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function (...args) {
        if (this._origUrl?.includes(CHECK)) {
            this.addEventListener('load', () => {
                try { sendToken(extractToken(JSON.parse(this.responseText))); } catch {}
            });
        }
        return xhrSend.apply(this, args);
    };

    const origFetch = window.fetch;
    window.fetch = function (...args) {
        const urlStr = typeof args[0] === 'object' ? args[0]?.url : args[0];
        if (typeof args[0] === 'string') args[0] = rewriteUrl(args[0]);

        const promise = origFetch.apply(this, args);
        if (typeof urlStr === 'string' && urlStr.includes(CHECK)) {
            promise
                .then((res) => res.clone().json())
                .then((data) => sendToken(extractToken(data)))
                .catch(() => {});
        }
        return promise;
    };

    document.addEventListener('submit', (event) => {
        if (event.target?.action) event.target.action = rewriteUrl(event.target.action);
    }, true);

    document.addEventListener('click', (event) => {
        const link = event.target?.closest?.('a[href]');
        if (link?.href) link.href = rewriteUrl(link.href);
    }, true);

    const formSubmit = HTMLFormElement.prototype.submit;
    HTMLFormElement.prototype.submit = function (...args) {
        if (this.action) this.action = rewriteUrl(this.action);
        return formSubmit.apply(this, args);
    };

    const windowOpen = window.open;
    window.open = function (...args) {
        if (typeof args[0] === 'string') args[0] = rewriteUrl(args[0]);
        return windowOpen.apply(this, args);
    };

    rewriteTree(document);
    new MutationObserver((mutations) => {
        for (const { type, target, attributeName, addedNodes } of mutations) {
            if (type === 'attributes') {
                rewriteAttr(target, attributeName);
                continue;
            }
            for (const node of addedNodes) {
                if (node.nodeType === 1) rewriteTree(node);
            }
        }
    }).observe(document.documentElement, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ['href', 'src', 'action'],
    });

    if (document.body) showPending();
    else document.addEventListener('DOMContentLoaded', showPending);
})();
