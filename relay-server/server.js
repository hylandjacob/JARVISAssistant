const http = require("http");
const crypto = require("crypto");
const WebSocket = require("ws");

const PORT = process.env.PORT || 8787;
// code -> { device, controller, createdAt, fixed, lastLocation }
const sessions = new Map();

function code6() { return String(Math.floor(100000 + Math.random() * 900000)); }

// Shown to whoever opens the location-request link. Browsers always show
// their own native "Allow location access?" prompt before anything is sent -
// this page cannot skip or hide that, by design of every modern browser.
// Only send this link to a device/person who has agreed to share it with you.
const locHtml = (code) => `<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Share location</title>
<style>body{background:#02070d;color:#e2e8f0;font-family:sans-serif;text-align:center;margin:0;padding:40px 20px}
button{margin-top:20px;padding:14px 24px;background:#0ea5e9;color:#02070d;border:none;border-radius:8px;font-size:16px;font-weight:600}
p{max-width:420px;margin:16px auto;line-height:1.5;color:#94a3b8}
#status{color:#4ade80;font-weight:600}</style></head>
<body>
<h2>Share your location</h2>
<p>This page asks your browser to share your device's current location. You'll see your browser's own permission prompt first — nothing is sent unless you tap Allow.</p>
<button onclick="share()">SHARE MY LOCATION</button>
<p id="status"></p>
<script>
function share() {
  if (!navigator.geolocation) { document.getElementById('status').textContent = 'Not supported on this browser.'; return; }
  navigator.geolocation.getCurrentPosition(async (pos) => {
    try {
      await fetch('/loc?code=${code}', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy })
      });
      document.getElementById('status').textContent = 'Location shared. You can close this page.';
    } catch (e) {
      document.getElementById('status').textContent = 'Could not send location.';
    }
  }, (err) => {
    document.getElementById('status').textContent = 'Location permission denied or unavailable.';
  }, { enableHighAccuracy: true, timeout: 15000 });
}
</script></body></html>`;

const html = `<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>JARVIS Remote</title>
<style>body{background:#02070d;color:#38bdf8;font-family:monospace;text-align:center;margin:20px}
#screen{max-width:96vw;max-height:72vh;border:1px solid #38bdf8;touch-action:none}
button{margin:5px;padding:12px;background:#06131c;color:#38bdf8;border:1px solid #38bdf8;border-radius:6px}
input{padding:12px;width:170px;text-align:center;text-transform:uppercase}</style></head>
<body><h2>JARVIS REMOTE</h2>
<div id="login"><input id="code" inputmode="text" maxlength="16" placeholder="Device code">
<button onclick="manualPair()">PAIR</button><p id="msg"></p></div>
<div id="ctrl" style="display:none"><img id="screen"><br>
<button onclick="cmd('back')">BACK</button><button onclick="cmd('home')">HOME</button>
<button onclick="cmd('recents')">RECENTS</button><button onclick="swipe('up')">↑</button>
<button onclick="cmd('wake')" style="background:#0c2b1a;border-color:#4ade80;color:#4ade80">WAKE SCREEN</button>
<button onclick="cmd('get_location')" style="background:#0c1f2b;border-color:#38bdf8;color:#38bdf8">GET LOCATION</button>
<button onclick="lockPhone()" style="background:#3a0f0f;border-color:#f87171;color:#f87171">LOCK PHONE</button>
<button onclick="swipe('down')">↓</button><button onclick="swipe('left')">←</button><button onclick="swipe('right')">→</button><br>
<button onclick="disconnectRemote()" style="background:#3a0f0f;border-color:#f87171;color:#f87171;margin-top:10px">DISCONNECT</button>
<p id="status"></p>
<p id="locResult"></p></div>
<script>
let ws, retryTimer, currentCode, manualStop = false;
const img = document.getElementById('screen');

function connect(c) {
  clearTimeout(retryTimer);
  currentCode = c;
  ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/control?code=' + encodeURIComponent(c));
  ws.binaryType = 'blob';
  ws.onopen = () => { login.style.display = 'none'; ctrl.style.display = 'block'; status.textContent = 'Connected'; msg.textContent = ''; };
  ws.onmessage = e => {
    if (typeof e.data === 'string') {
      try {
        const m = JSON.parse(e.data);
        if (m.type === 'device') status.textContent = m.online ? 'Phone 1 online - loading screen…' : 'Phone 1 disconnected';
        else if (m.type === 'location') {
          const mapsUrl = 'https://www.google.com/maps?q=' + m.lat + ',' + m.lng;
          document.getElementById('locResult').innerHTML =
            'Location: ' + m.lat + ', ' + m.lng + (m.accuracy ? ' (±' + Math.round(m.accuracy) + ' m)' : '') +
            '<br><a href="' + mapsUrl + '" target="_blank" style="color:#38bdf8">Open in Google Maps</a>';
        }
        else if (m.type === 'location_error') document.getElementById('locResult').textContent = m.message;
        else if (m.type === 'locked') status.textContent = m.message || 'Phone locked';
        else status.textContent = m.message || m.type;
      } catch (_) {}
    } else { img.src = URL.createObjectURL(e.data); }
  };
  ws.onclose = () => {
    if (manualStop) { status.textContent = 'Disconnected'; return; }
    status.textContent = 'Disconnected - retrying…';
    retryTimer = setTimeout(() => connect(c), 3000);
  };
  ws.onerror = () => {
    if (manualStop) return;
    msg.textContent = 'Not paired yet, retrying…';
    retryTimer = setTimeout(() => connect(c), 3000);
  };
}

function lockPhone() {
  if (!confirm('Lock Phone 1 right now?')) return;
  cmd('lock');
}

function disconnectRemote() {
  manualStop = true;
  clearTimeout(retryTimer);
  if (ws) { try { ws.close(); } catch (_) {} }
  ctrl.style.display = 'none';
  login.style.display = 'block';
  msg.textContent = '';
  history.replaceState(null, '', location.pathname);
}

function manualPair() {
  manualStop = false;
  const c = document.getElementById('code').value.trim();
  if (!c) return;
  history.replaceState(null, '', '?code=' + encodeURIComponent(c));
  connect(c);
}

// Auto-pair straight from a saved/bookmarked link like ?code=XXXXXXXX
window.addEventListener('load', () => {
  const p = new URLSearchParams(location.search);
  const c = p.get('code') || p.get('id');
  if (c) { document.getElementById('code').value = c; manualPair(); }
});

function cmd(c) { if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'cmd', cmd: c })); }
function tap(e) {
  const r = img.getBoundingClientRect();
  const x = (e.clientX - r.left) * img.naturalWidth / r.width;
  const y = (e.clientY - r.top) * img.naturalHeight / r.height;
  if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'cmd', cmd: 'tap', x, y }));
}
img.addEventListener('pointerup', tap);
function swipe(d) {
  const w = img.naturalWidth, h = img.naturalHeight;
  const a = { up: [w/2,h*.75,w/2,h*.25], down: [w/2,h*.25,w/2,h*.75], left: [w*.8,h/2,w*.2,h/2], right: [w*.2,h/2,w*.8,h/2] }[d];
  if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'cmd', cmd: 'swipe', x1: a[0], y1: a[1], x2: a[2], y2: a[3] }));
}
</script></body></html>`;

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === "/health") { res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" }); res.end("ok"); return; }

  if (url.pathname === "/loc") {
    const code = (url.searchParams.get("code") || "").trim().toUpperCase();
    if (!code) { res.writeHead(400); res.end("Missing code"); return; }

    if (req.method === "GET") {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
      res.end(locHtml(code));
      return;
    }

    if (req.method === "POST") {
      let body = "";
      req.on("data", (chunk) => { body += chunk; if (body.length > 4096) req.destroy(); });
      req.on("end", () => {
        try {
          const data = JSON.parse(body);
          const lat = Number(data.lat), lng = Number(data.lng), accuracy = Number(data.accuracy) || null;
          if (!Number.isFinite(lat) || !Number.isFinite(lng)) { res.writeHead(400); res.end("Bad location"); return; }
          let s = sessions.get(code);
          if (!s) { s = { device: null, controller: null, createdAt: Date.now(), fixed: true }; sessions.set(code, s); }
          s.lastLocation = { lat, lng, accuracy, at: Date.now() };
          if (s.device && s.device.readyState === WebSocket.OPEN) {
            s.device.send(JSON.stringify({ type: "location", lat, lng, accuracy, at: s.lastLocation.at }));
          }
          res.writeHead(200, { "Content-Type": "application/json" }); res.end("{\"ok\":true}");
        } catch (_) {
          res.writeHead(400); res.end("Bad request");
        }
      });
      return;
    }

    res.writeHead(405); res.end("Method not allowed");
    return;
  }

  if (url.pathname === "/loc-view") {
    const code = (url.searchParams.get("code") || "").trim().toUpperCase();
    const s = sessions.get(code);
    const loc = s && s.lastLocation;
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    if (!loc) {
      res.end(`<!doctype html><html><body style="background:#02070d;color:#94a3b8;font-family:sans-serif;text-align:center;padding:40px"><h3>No location shared yet for this code.</h3><p>This page refreshes every 10 seconds.</p><script>setTimeout(()=>location.reload(),10000)</script></body></html>`);
      return;
    }
    const mapsUrl = `https://www.google.com/maps?q=${loc.lat},${loc.lng}`;
    res.end(`<!doctype html><html><body style="background:#02070d;color:#e2e8f0;font-family:sans-serif;text-align:center;padding:40px">
<h3>Last shared location</h3>
<p>Lat: ${loc.lat}<br>Lng: ${loc.lng}<br>Accuracy: ${loc.accuracy ? Math.round(loc.accuracy) + ' m' : 'unknown'}<br>At: ${new Date(loc.at).toLocaleString()}</p>
<a href="${mapsUrl}" style="color:#0ea5e9" target="_blank">Open in Google Maps</a>
<script>setTimeout(()=>location.reload(),10000)</script>
</body></html>`);
    return;
  }

  if (url.pathname === "/" || url.pathname === "") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" }); res.end(html); return;
  }

  res.writeHead(404); res.end("Not found");
});

// Both are noServer: we route every upgrade ourselves below. Letting either
// one auto-bind to `server` with its own `path` is what caused the earlier
// bug - it would abort (and destroy the socket for) any upgrade request
// whose path didn't match its own, before our own logic ever ran.
const wss = new WebSocket.Server({ noServer: true });
const wsc = new WebSocket.Server({ noServer: true });

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const fixedId = (url.searchParams.get("id") || "").trim().toUpperCase().slice(0, 32);
  let code;

  if (fixedId) {
    // Fixed per-install device ID: reuse the same session slot across
    // reconnects so a bookmarked controller link keeps working, and so a
    // controller can pair in advance and just wait for the phone to reconnect.
    code = fixedId;
    const existing = sessions.get(code);
    if (existing) {
      if (existing.device && existing.device !== ws && existing.device.readyState === WebSocket.OPEN) {
        try { existing.device.close(); } catch (_) {}
      }
      existing.device = ws;
      existing.createdAt = Date.now();
      if (existing.controller && existing.controller.readyState === WebSocket.OPEN) {
        existing.controller.send(JSON.stringify({ type: "device", online: true }));
        ws.send(JSON.stringify({ type: "controller", connected: true }));
      }
    } else {
      sessions.set(code, { device: ws, controller: null, createdAt: Date.now(), fixed: true });
    }
  } else {
    do { code = code6(); } while (sessions.has(code));
    sessions.set(code, { device: ws, controller: null, createdAt: Date.now(), fixed: false });
  }

  ws.send(JSON.stringify({ type: "session", code }));

  ws.on("message", (data, isBinary) => {
    if (isBinary) {
      const s = sessions.get(code);
      if (s && s.controller && s.controller.readyState === WebSocket.OPEN) s.controller.send(data, { binary: true });
    }
  });

  ws.on("close", () => {
    const s = sessions.get(code);
    if (!s || s.device !== ws) return; // a newer connection already replaced this one
    if (s.controller && s.controller.readyState === WebSocket.OPEN) {
      s.controller.send(JSON.stringify({ type: "device", online: false }));
    }
    if (s.fixed) {
      s.device = null; // keep the session slot so a paired controller can wait for reconnect
    } else {
      sessions.delete(code);
    }
  });
});

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === "/ws/device") {
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
    return;
  }

  if (url.pathname === "/ws/control") {
    const code = (url.searchParams.get("code") || "").trim().toUpperCase();
    const s = sessions.get(code);
    if (!s) { socket.write("HTTP/1.1 404 Not Found\r\n\r\n"); socket.destroy(); return; }

    wsc.handleUpgrade(req, socket, head, (ws) => {
      if (s.controller && s.controller !== ws && s.controller.readyState === WebSocket.OPEN) {
        try { s.controller.close(); } catch (_) {}
      }
      s.controller = ws;
      const online = s.device && s.device.readyState === WebSocket.OPEN;
      ws.send(JSON.stringify({ type: "paired", message: online ? "Paired" : "Paired - waiting for phone to come online" }));
      // This was missing before: the device needs to know a controller just
      // joined so it starts sending screen frames (it only streams frames
      // while RemoteRelayClient.controllerConnected is true).
      if (online) s.device.send(JSON.stringify({ type: "controller", connected: true }));
      ws.on("message", (data, isBinary) => {
        if (!isBinary && s.device && s.device.readyState === WebSocket.OPEN) s.device.send(data.toString());
      });
      ws.on("close", () => {
        if (s.controller !== ws) return; // superseded by a newer connection (e.g. page refresh) - ignore this stale close
        s.controller = null;
        if (s.device && s.device.readyState === WebSocket.OPEN) s.device.send(JSON.stringify({ type: "controller", connected: false }));
      });
    });
    return;
  }

  socket.destroy();
});

// Fixed-id sessions whose device never comes back would otherwise sit in
// memory forever; sweep out ones that have been offline for a long time.
setInterval(() => {
  const now = Date.now();
  for (const [code, s] of sessions) {
    const deviceOffline = !s.device || s.device.readyState !== WebSocket.OPEN;
    const controllerOffline = !s.controller || s.controller.readyState !== WebSocket.OPEN;
    if (deviceOffline && controllerOffline && now - s.createdAt > 24 * 60 * 60 * 1000) {
      sessions.delete(code);
    }
  }
}, 60 * 60 * 1000);

server.listen(PORT, () => console.log(`JARVIS relay listening on ${PORT}`));
