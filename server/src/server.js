// Serwer Digital Signage – panel www, API dla tabletów, WebSocket do sterowania.
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFile } = require('child_process');
const express = require('express');
const multer = require('multer');
const bcrypt = require('bcryptjs');
const QRCode = require('qrcode');
const { WebSocketServer } = require('ws');
const store = require('./db');

const PORT = +process.env.PORT || 3000;
const PUBLIC_URL = (process.env.PUBLIC_URL || '').replace(/\/+$/, '');
const VERSION = require('../package.json').version;
const db = () => store.db();

// ---------- hasło administratora ----------
(function initAdmin() {
  const s = db().settings;
  if (process.env.ADMIN_PASSWORD && !s.adminPasswordHash) {
    s.adminPasswordHash = bcrypt.hashSync(process.env.ADMIN_PASSWORD, 10);
    store.saveNow();
  }
  if (!s.adminPasswordHash) {
    const pass = crypto.randomBytes(6).toString('base64url');
    s.adminPasswordHash = bcrypt.hashSync(pass, 10);
    store.saveNow();
    fs.writeFileSync(path.join(store.DATA_DIR, 'admin-password.txt'), `login: admin\nhasło: ${pass}\n`);
    console.log(`\n=== Pierwsze uruchomienie. Panel: login "admin", hasło: ${pass} ===\n`);
  }
})();

// ---------- sesje panelu (podpisane cookie) ----------
const SESSION_H = 12;
function sign(v) { return crypto.createHmac('sha256', db().settings.sessionSecret).update(v).digest('base64url'); }
function makeSession(user) { const v = `${user}|${Date.now() + SESSION_H * 3600e3}`; return `${Buffer.from(v).toString('base64url')}.${sign(v)}`; }
function checkSession(cookie) {
  if (!cookie) return null;
  const [b, s] = cookie.split('.');
  if (!b || !s) return null;
  const v = Buffer.from(b, 'base64url').toString();
  const good = sign(v);
  if (good.length !== s.length || !crypto.timingSafeEqual(Buffer.from(good), Buffer.from(s))) return null;
  const [user, exp] = v.split('|');
  return +exp > Date.now() ? user : null;
}
function cookies(req) {
  const o = {};
  (req.headers.cookie || '').split(';').forEach(p => { const i = p.indexOf('='); if (i > 0) o[p.slice(0, i).trim()] = decodeURIComponent(p.slice(i + 1).trim()); });
  return o;
}
function requireAdmin(req, res, next) {
  const u = checkSession(cookies(req).sid);
  if (!u) return res.status(401).json({ error: 'Zaloguj się' });
  req.user = u; next();
}

// prosta ochrona przed zgadywaniem hasła
const fails = new Map();
function tooMany(ip) { const f = fails.get(ip); return f && f.n >= 8 && Date.now() - f.t < 10 * 60e3; }
function fail(ip) { const f = fails.get(ip) || { n: 0, t: 0 }; f.n = Date.now() - f.t > 10 * 60e3 ? 1 : f.n + 1; f.t = Date.now(); fails.set(ip, f); }

// ---------- pomocnicze ----------
const DEFAULT_SETTINGS = {
  orientation: 'landscape',      // landscape | portrait | reverse_landscape | reverse_portrait | auto
  volume: 50,                    // 0–100
  brightness: -1,                // -1 = systemowa, 0–100
  screenOn: '',                  // "07:00" – włączenie ekranu
  screenOff: '',                 // "22:00" – wyłączenie ekranu
  imageFit: 'contain',           // contain | cover
  transition: 'fade',            // fade | none
  ticker: { enabled: false, text: '', speed: 3, fg: '#ffffff', bg: '#000000', size: 28 },
  kiosk: true,                   // wracaj na wierzch, gdy ktoś wyjdzie z aplikacji
  kioskReturnSec: 60,
  rebootAt: '',                  // "04:00" – codzienny restart tabletu (root)
};
function deepMerge(a, b) {
  const o = Array.isArray(a) ? [...a] : { ...a };
  for (const [k, v] of Object.entries(b || {})) {
    if (v && typeof v === 'object' && !Array.isArray(v) && a && typeof a[k] === 'object') o[k] = deepMerge(a[k], v);
    else if (v !== undefined && v !== null) o[k] = v;
  }
  return o;
}
const groupOf = d => db().groups.find(g => g.id === d.groupId);
function effective(d) {
  const g = groupOf(d);
  const settings = deepMerge(deepMerge(DEFAULT_SETTINGS, g?.settings), d.ownSettings ? d.settings : null);
  const content = (d.ownContent ? d.content : g?.content) || { playlistId: null, rules: [] };
  return { settings, content };
}
function mediaPublic(m) {
  const o = { id: m.id, type: m.type, name: m.name, duration: m.duration };
  if (m.type === 'web') o.url = m.url; else { o.file = m.file; o.size = m.size; o.sha256 = m.sha256; }
  return o;
}
function manifestFor(d) {
  const { settings, content } = effective(d);
  const ids = new Set([content.playlistId, ...(content.rules || []).map(r => r.playlistId)].filter(Boolean));
  const playlists = {};
  for (const pid of ids) {
    const p = db().playlists.find(x => x.id === pid);
    if (!p) continue;
    playlists[pid] = {
      name: p.name,
      items: p.items.map(it => {
        const m = db().media.find(x => x.id === it.mediaId);
        if (!m) return null;
        return { ...mediaPublic(m), duration: +it.duration || m.duration || 10 };
      }).filter(Boolean),
    };
  }
  const override = d.override && d.override.until > Date.now() ? d.override : null;
  const body = { device: { id: d.id, name: d.name }, settings, schedule: { playlistId: content.playlistId || null, rules: content.rules || [] }, playlists, override };
  const rev = crypto.createHash('sha1').update(JSON.stringify(body)).digest('hex').slice(0, 16);
  return { rev, serverTime: Date.now(), ...body };
}
function nowPlaylist(d) {
  const { content } = effective(d);
  const now = new Date();
  const day = now.getDay() || 7, hm = now.toTimeString().slice(0, 5);
  for (const r of content.rules || []) {
    if (r.days?.length && !r.days.includes(day)) continue;
    const inRange = r.from <= r.to ? hm >= r.from && hm < r.to : hm >= r.from || hm < r.to;
    if (inRange) return r.playlistId;
  }
  return content.playlistId;
}
function publicBase(req) {
  if (PUBLIC_URL) return PUBLIC_URL;
  const proto = req.headers['x-forwarded-proto'] || req.protocol;
  return `${proto}://${req.headers['x-forwarded-host'] || req.headers.host}`;
}
function sha256File(f) {
  return new Promise((res, rej) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(f).on('data', c => h.update(c)).on('end', () => res(h.digest('hex'))).on('error', rej);
  });
}
function run(cmd, args) {
  return new Promise(res => execFile(cmd, args, { timeout: 60e3 }, (e, out) => res(e ? null : out)));
}
async function probeVideo(file) {
  const out = await run('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file]);
  return out ? Math.ceil(parseFloat(out)) || null : null;
}

// ---------- tablety online ----------
const online = new Map();   // deviceId -> { ws, pending: Map }
const pairing = new Map();  // pairId -> { code, info, ip, created, approved: {deviceId, token} }

function send(deviceId, msg) {
  const c = online.get(deviceId);
  if (c && c.ws.readyState === 1) { c.ws.send(JSON.stringify(msg)); return true; }
  return false;
}
function notifyAll() { for (const id of online.keys()) send(id, { type: 'manifest' }); }
function command(deviceId, cmd, args = {}, timeoutMs = 25e3) {
  const c = online.get(deviceId);
  if (!c) return Promise.resolve({ ok: false, error: 'Tablet offline' });
  const id = store.id(6);
  return new Promise(resolve => {
    const t = setTimeout(() => { c.pending.delete(id); resolve({ ok: false, error: 'Brak odpowiedzi tabletu' }); }, timeoutMs);
    c.pending.set(id, r => { clearTimeout(t); resolve(r); });
    c.ws.send(JSON.stringify({ type: 'cmd', id, cmd, args }));
  });
}
function deviceView(d) {
  const { settings, content } = effective(d);
  return {
    id: d.id, name: d.name, groupId: d.groupId, notes: d.notes || '',
    ownContent: !!d.ownContent, content: d.content || { playlistId: null, rules: [] },
    ownSettings: !!d.ownSettings, settings: d.settings || null,
    effective: { settings, content }, nowPlaylistId: nowPlaylist(d),
    online: online.has(d.id), lastSeen: d.lastSeen, status: d.status || {}, created: d.created,
    override: d.override && d.override.until > Date.now() ? d.override : null,
    shotAt: d.shotAt || null,
  };
}
function createDevice(info, name, groupId) {
  const d = {
    id: store.id(6), token: store.id(24), name: name || info?.model || 'Tablet',
    groupId: groupId || null, created: Date.now(), lastSeen: null, status: { ...info },
    ownContent: false, content: null, ownSettings: false, settings: null,
  };
  db().devices.push(d); store.save();
  store.addLog(`Dodano tablet „${d.name}”`, d.id);
  return d;
}
function deviceAuth(req) {
  const h = req.headers.authorization || '';
  const t = h.startsWith('Bearer ') ? h.slice(7) : req.query.t;
  return t ? db().devices.find(d => d.token === t) : null;
}

// ---------- aplikacja ----------
const app = express();
app.set('trust proxy', true);
app.disable('x-powered-by');
app.use(express.json({ limit: '2mb' }));
app.use((req, res, next) => { res.set('X-Content-Type-Options', 'nosniff'); next(); });

// --- logowanie ---
app.post('/api/login', (req, res) => {
  if (tooMany(req.ip)) return res.status(429).json({ error: 'Za dużo prób. Spróbuj za 10 minut.' });
  const { user, password } = req.body || {};
  const s = db().settings;
  if (user !== s.adminUser || !bcrypt.compareSync(String(password || ''), s.adminPasswordHash)) {
    fail(req.ip); return res.status(401).json({ error: 'Błędny login lub hasło' });
  }
  fails.delete(req.ip);
  const secure = publicBase(req).startsWith('https') ? '; Secure' : '';
  res.set('Set-Cookie', `sid=${makeSession(user)}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SESSION_H * 3600}${secure}`);
  res.json({ ok: true });
});
app.post('/api/logout', (req, res) => { res.set('Set-Cookie', 'sid=; Path=/; Max-Age=0'); res.json({ ok: true }); });

const A = express.Router();
A.use(requireAdmin);

A.post('/password', (req, res) => {
  const { old, password } = req.body || {};
  const s = db().settings;
  if (!bcrypt.compareSync(String(old || ''), s.adminPasswordHash)) return res.status(400).json({ error: 'Błędne obecne hasło' });
  if (!password || password.length < 8) return res.status(400).json({ error: 'Hasło musi mieć min. 8 znaków' });
  s.adminPasswordHash = bcrypt.hashSync(password, 10);
  s.sessionSecret = crypto.randomBytes(32).toString('hex'); // wyloguj inne sesje
  store.saveNow();
  try { fs.unlinkSync(path.join(store.DATA_DIR, 'admin-password.txt')); } catch {}
  res.set('Set-Cookie', `sid=${makeSession(s.adminUser)}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SESSION_H * 3600}`);
  res.json({ ok: true });
});

A.get('/state', (req, res) => {
  const now = Date.now();
  db().enrollCodes = db().enrollCodes.filter(c => c.expires > now);
  res.json({
    version: VERSION, publicUrl: publicBase(req), defaults: DEFAULT_SETTINGS,
    media: db().media, playlists: db().playlists, groups: db().groups,
    devices: db().devices.map(deviceView),
    enrollCodes: db().enrollCodes, apk: db().apk,
    pairing: [...pairing.entries()].filter(([, p]) => !p.approved).map(([pid, p]) => ({ code: p.code, model: p.info?.model, ip: p.ip, created: p.created })),
  });
});
A.get('/log', (req, res) => res.json(db().log.slice(0, 300)));

// --- media ---
const upload = multer({
  storage: multer.diskStorage({
    destination: store.MEDIA_DIR,
    filename: (req, f, cb) => cb(null, store.id(8) + path.extname(f.originalname).toLowerCase().replace(/[^.a-z0-9]/g, '')),
  }),
  limits: { fileSize: 4 * 1024 ** 3 },
});
A.post('/media', upload.array('files', 50), async (req, res) => {
  const out = [];
  for (const f of req.files || []) {
    const isVideo = /^video\//.test(f.mimetype) || /\.(mp4|m4v|mkv|webm|mov|3gp)$/i.test(f.filename);
    const isImage = /^image\//.test(f.mimetype) || /\.(jpe?g|png|gif|webp|bmp)$/i.test(f.filename);
    if (!isVideo && !isImage) { fs.unlinkSync(f.path); continue; }
    const name = Buffer.from(f.originalname, 'latin1').toString('utf8');
    const m = {
      id: store.id(6), type: isVideo ? 'video' : 'image', name: name.replace(/\.[^.]+$/, ''),
      file: f.filename, size: f.size, sha256: await sha256File(f.path), created: Date.now(),
      duration: isVideo ? (await probeVideo(f.path)) || 30 : 10,
    };
    if (isVideo) {
      const thumb = f.filename + '.thumb.jpg';
      const ok = await run('ffmpeg', ['-y', '-ss', '1', '-i', f.path, '-frames:v', '1', '-vf', 'scale=320:-2', path.join(store.MEDIA_DIR, thumb)]);
      if (ok !== null && fs.existsSync(path.join(store.MEDIA_DIR, thumb))) m.thumb = thumb;
    }
    db().media.push(m); out.push(m);
  }
  store.save();
  res.json(out);
});
A.post('/media/url', (req, res) => {
  const { name, url, duration } = req.body || {};
  if (!/^https?:\/\//i.test(url || '')) return res.status(400).json({ error: 'Adres musi zaczynać się od http:// lub https://' });
  const m = { id: store.id(6), type: 'web', name: name || url, url, duration: +duration || 30, created: Date.now() };
  db().media.push(m); store.save(); res.json(m);
});
A.put('/media/:id', (req, res) => {
  const m = db().media.find(x => x.id === req.params.id);
  if (!m) return res.status(404).end();
  if (req.body.name) m.name = String(req.body.name);
  if (req.body.duration) m.duration = Math.max(1, +req.body.duration);
  if (m.type === 'web' && req.body.url) m.url = String(req.body.url);
  store.save(); notifyAll(); res.json(m);
});
A.delete('/media/:id', (req, res) => {
  const i = db().media.findIndex(x => x.id === req.params.id);
  if (i < 0) return res.status(404).end();
  const [m] = db().media.splice(i, 1);
  for (const p of db().playlists) p.items = p.items.filter(it => it.mediaId !== m.id);
  for (const f of [m.file, m.thumb]) if (f) fs.rm(path.join(store.MEDIA_DIR, f), () => {});
  store.save(); notifyAll(); res.json({ ok: true });
});
A.use('/files', express.static(store.MEDIA_DIR, { maxAge: '1d' }));

// --- playlisty ---
A.post('/playlists', (req, res) => {
  const p = { id: store.id(6), name: req.body.name || 'Nowa playlista', items: [] };
  db().playlists.push(p); store.save(); res.json(p);
});
A.put('/playlists/:id', (req, res) => {
  const p = db().playlists.find(x => x.id === req.params.id);
  if (!p) return res.status(404).end();
  if (req.body.name) p.name = String(req.body.name);
  if (Array.isArray(req.body.items)) p.items = req.body.items
    .filter(it => db().media.some(m => m.id === it.mediaId))
    .map(it => ({ mediaId: it.mediaId, duration: Math.max(1, +it.duration || 10) }));
  store.save(); notifyAll(); res.json(p);
});
A.delete('/playlists/:id', (req, res) => {
  db().playlists = db().playlists.filter(x => x.id !== req.params.id);
  const clean = c => { if (!c) return; if (c.playlistId === req.params.id) c.playlistId = null; c.rules = (c.rules || []).filter(r => r.playlistId !== req.params.id); };
  db().groups.forEach(g => clean(g.content)); db().devices.forEach(d => clean(d.content));
  store.save(); notifyAll(); res.json({ ok: true });
});

// --- grupy ---
function cleanContent(c) {
  if (!c) return { playlistId: null, rules: [] };
  return {
    playlistId: c.playlistId || null,
    rules: (c.rules || []).filter(r => r.playlistId && /^\d\d:\d\d$/.test(r.from) && /^\d\d:\d\d$/.test(r.to))
      .map(r => ({ days: (r.days || []).map(Number).filter(n => n >= 1 && n <= 7), from: r.from, to: r.to, playlistId: r.playlistId })),
  };
}
A.post('/groups', (req, res) => {
  const g = { id: store.id(6), name: req.body.name || 'Nowa grupa', content: { playlistId: null, rules: [] }, settings: {} };
  db().groups.push(g); store.save(); res.json(g);
});
A.put('/groups/:id', (req, res) => {
  const g = db().groups.find(x => x.id === req.params.id);
  if (!g) return res.status(404).end();
  if (req.body.name) g.name = String(req.body.name);
  if (req.body.content) g.content = cleanContent(req.body.content);
  if (req.body.settings) g.settings = req.body.settings;
  store.save(); notifyAll(); res.json(g);
});
A.delete('/groups/:id', (req, res) => {
  db().groups = db().groups.filter(x => x.id !== req.params.id);
  db().devices.forEach(d => { if (d.groupId === req.params.id) d.groupId = null; });
  store.save(); notifyAll(); res.json({ ok: true });
});

// --- tablety ---
A.put('/devices/:id', (req, res) => {
  const d = db().devices.find(x => x.id === req.params.id);
  if (!d) return res.status(404).end();
  const b = req.body || {};
  if (b.name) d.name = String(b.name);
  if ('notes' in b) d.notes = String(b.notes || '');
  if ('groupId' in b) d.groupId = b.groupId || null;
  if ('ownContent' in b) d.ownContent = !!b.ownContent;
  if (b.content) d.content = cleanContent(b.content);
  if ('ownSettings' in b) d.ownSettings = !!b.ownSettings;
  if (b.settings) d.settings = b.settings;
  store.save(); send(d.id, { type: 'manifest' }); res.json(deviceView(d));
});
A.delete('/devices/:id', (req, res) => {
  const d = db().devices.find(x => x.id === req.params.id);
  if (!d) return res.status(404).end();
  send(d.id, { type: 'unpaired' });
  online.get(d.id)?.ws.close();
  db().devices = db().devices.filter(x => x !== d);
  store.addLog(`Usunięto tablet „${d.name}”`); store.save();
  res.json({ ok: true });
});
A.post('/devices/:id/cmd', async (req, res) => {
  const d = db().devices.find(x => x.id === req.params.id);
  if (!d) return res.status(404).end();
  const { cmd, args } = req.body || {};
  if (cmd === 'override') { // pokaż stronę/medium natychmiast, na określony czas
    d.override = args?.clear ? null : { mediaId: args.mediaId || null, url: args.url || null, until: Date.now() + (+args.minutes || 10) * 60e3 };
    if (d.override?.mediaId) { const m = db().media.find(x => x.id === d.override.mediaId); d.override.media = m ? mediaPublic(m) : null; }
    store.save(); send(d.id, { type: 'manifest' }); return res.json({ ok: true });
  }
  if (!['screenshot', 'tap', 'swipe', 'key', 'text'].includes(cmd)) store.addLog(`Polecenie „${cmd}” → ${d.name}`, d.id);
  const r = await command(d.id, cmd, args || {}, cmd === 'update' ? 120e3 : 25e3);
  if (cmd === 'screenshot' && r.ok && r.data?.jpeg) {
    fs.writeFileSync(path.join(store.SHOT_DIR, d.id + '.jpg'), Buffer.from(r.data.jpeg, 'base64'));
    d.shotAt = Date.now(); delete r.data.jpeg;
  }
  res.json(r);
});
A.post('/devices/cmd-all', async (req, res) => {
  const { cmd, args, ids } = req.body || {};
  const targets = db().devices.filter(d => !ids || ids.includes(d.id));
  const results = await Promise.all(targets.map(d => command(d.id, cmd, args || {}).then(r => ({ id: d.id, name: d.name, ...r }))));
  store.addLog(`Polecenie „${cmd}” → ${targets.length} tabletów`);
  res.json(results);
});
A.get('/devices/:id/screenshot.jpg', (req, res) => {
  const f = path.join(store.SHOT_DIR, path.basename(req.params.id) + '.jpg');
  if (!fs.existsSync(f)) return res.status(404).end();
  res.set('Cache-Control', 'no-store'); res.sendFile(f);
});

// --- dodawanie tabletów ---
A.post('/enroll', async (req, res) => {
  const minutes = Math.min(24 * 60, Math.max(5, +req.body.minutes || 30));
  const c = { code: store.code(6), groupId: req.body.groupId || null, expires: Date.now() + minutes * 60e3, uses: 0 };
  db().enrollCodes.push(c); store.save();
  res.json(c);
});
A.get('/enroll/:code/qr.svg', async (req, res) => {
  const url = `${publicBase(req)}/e/${encodeURIComponent(req.params.code)}`;
  res.type('image/svg+xml').send(await QRCode.toString(url, { type: 'svg', margin: 1, errorCorrectionLevel: 'M' }));
});
A.delete('/enroll/:code', (req, res) => {
  db().enrollCodes = db().enrollCodes.filter(c => c.code !== req.params.code); store.save(); res.json({ ok: true });
});
A.post('/pair', (req, res) => { // kod wyświetlany na ekranie tabletu
  const code = String(req.body.code || '').replace(/\s/g, '').toUpperCase();
  const entry = [...pairing.values()].find(p => p.code === code && !p.approved);
  if (!entry) return res.status(404).json({ error: 'Nie znaleziono tabletu z tym kodem. Sprawdź, czy tablet jest włączony i połączony z internetem.' });
  const d = createDevice({ ...entry.info, publicIp: entry.ip }, req.body.name, req.body.groupId);
  entry.approved = { deviceId: d.id, token: d.token, name: d.name };
  res.json(deviceView(d));
});

// --- aktualizacja aplikacji ---
const apkUpload = multer({ dest: store.APK_DIR, limits: { fileSize: 300 * 1024 ** 2 } });
A.post('/apk', apkUpload.single('apk'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'Brak pliku' });
  const target = path.join(store.APK_DIR, 'signage.apk');
  fs.renameSync(req.file.path, target);
  db().apk = { size: req.file.size, sha256: await sha256File(target), uploaded: Date.now(), version: String(req.body.version || '').trim() || null };
  store.save(); res.json(db().apk);
});
A.post('/apk/push', async (req, res) => {
  if (!db().apk) return res.status(400).json({ error: 'Najpierw wgraj plik APK' });
  const ids = req.body.ids || [...online.keys()];
  const results = await Promise.all(ids.map(id => command(id, 'update', { sha256: db().apk.sha256 }, 180e3)
    .then(r => ({ id, name: db().devices.find(d => d.id === id)?.name, ...r }))));
  store.addLog(`Aktualizacja aplikacji → ${ids.length} tabletów`);
  res.json(results);
});

app.use('/api/admin', A);

// ---------- API tabletów ----------
// 1) rejestracja kodem z panelu (ręcznie albo z kodu QR)
app.post('/api/device/register', (req, res) => {
  const code = String(req.body.code || '').trim().toUpperCase();
  const c = db().enrollCodes.find(x => x.code === code && x.expires > Date.now());
  if (!c) return res.status(404).json({ error: 'Kod nieprawidłowy lub wygasł' });
  c.uses++;
  const d = createDevice({ ...(req.body.info || {}), publicIp: req.ip }, null, c.groupId);
  res.json({ deviceId: d.id, token: d.token, name: d.name });
});
// 2) tablet pokazuje swój kod, administrator wpisuje go w panelu
app.post('/api/device/hello', (req, res) => {
  const now = Date.now();
  for (const [k, p] of pairing) if (now - p.created > 3600e3) pairing.delete(k);
  let pid = req.body.pairId;
  let p = pid && pairing.get(pid);
  if (p?.approved) { pairing.delete(pid); return res.json({ approved: true, ...p.approved }); }
  if (!p) {
    pid = store.id(12);
    let code; do code = store.code(6); while ([...pairing.values()].some(x => x.code === code));
    p = { code, info: req.body.info || {}, ip: req.ip, created: now };
    pairing.set(pid, p);
  }
  res.json({ approved: false, pairId: pid, code: p.code });
});
app.get('/api/device/manifest', (req, res) => {
  const d = deviceAuth(req);
  if (!d) return res.status(401).json({ error: 'unpaired' });
  res.json(manifestFor(d));
});
app.get('/api/device/file/:file', (req, res) => {
  if (!deviceAuth(req)) return res.status(401).end();
  res.sendFile(path.join(store.MEDIA_DIR, path.basename(req.params.file)), { maxAge: '365d', immutable: true });
});
app.get('/api/device/apk', (req, res) => {
  if (!deviceAuth(req) || !db().apk) return res.status(404).end();
  res.sendFile(path.join(store.APK_DIR, 'signage.apk'));
});

// strona otwierana po zeskanowaniu kodu QR zwykłym aparatem
app.get('/e/:code', (req, res) => {
  res.type('html').send(`<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width"><title>Dodawanie tabletu</title>
<body style="font-family:sans-serif;max-width:520px;margin:40px auto;padding:0 16px"><h2>Dodawanie tabletu</h2>
<p>Ten kod QR należy zeskanować w aplikacji <b>Signage Player</b> na tablecie (przycisk „Skanuj kod QR”).</p>
<p>Można też wpisać ręcznie:<br>adres serwera: <b>${publicBase(req)}</b><br>kod: <b style="font-size:1.6em;letter-spacing:3px">${String(req.params.code).replace(/[^A-Z0-9]/gi, '')}</b></p></body>`);
});

app.use(express.static(path.join(__dirname, '..', 'public'), { index: 'index.html' }));
app.get('/health', (req, res) => res.json({ ok: true, version: VERSION, devices: db().devices.length, online: online.size }));

// ---------- WebSocket ----------
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 ** 2 });
server.on('upgrade', (req, sock, head) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname !== '/ws/device') return sock.destroy();
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : url.searchParams.get('token');
  const d = token && db().devices.find(x => x.token === token);
  if (!d) { sock.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); return sock.destroy(); }
  wss.handleUpgrade(req, sock, head, ws => onDevice(ws, d.id, req));
});

function onDevice(ws, deviceId, req) {
  const prev = online.get(deviceId);
  if (prev) prev.ws.terminate();
  const conn = { ws, pending: new Map(), alive: true };
  online.set(deviceId, conn);
  const d = () => db().devices.find(x => x.id === deviceId);
  const ip = (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
  if (d()) { d().lastSeen = Date.now(); d().status = { ...(d().status || {}), publicIp: ip }; store.addLog(`Tablet „${d().name}” połączony`, deviceId); }
  ws.on('pong', () => { conn.alive = true; });
  ws.on('message', raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    const dev = d(); if (!dev) return ws.close();
    dev.lastSeen = Date.now(); conn.alive = true;
    if (m.type === 'status') { dev.status = { ...dev.status, ...m.data, publicIp: ip }; store.save(); }
    else if (m.type === 'result') { const cb = conn.pending.get(m.id); if (cb) { conn.pending.delete(m.id); cb({ ok: !!m.ok, data: m.data, error: m.error }); } }
    else if (m.type === 'log') store.addLog(`[${dev.name}] ${String(m.text).slice(0, 500)}`, deviceId);
    else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong', t: Date.now() }));
  });
  ws.on('close', () => {
    if (online.get(deviceId) === conn) {
      online.delete(deviceId);
      for (const cb of conn.pending.values()) cb({ ok: false, error: 'Tablet rozłączył się' });
      if (d()) { d().lastSeen = Date.now(); store.addLog(`Tablet „${d().name}” rozłączony`, deviceId); }
    }
  });
  ws.send(JSON.stringify({ type: 'hello', serverTime: Date.now(), manifestRev: d() ? manifestFor(d()).rev : null }));
}
// wykrywanie zerwanych połączeń
setInterval(() => {
  for (const [id, c] of online) {
    if (!c.alive) { c.ws.terminate(); continue; }
    c.alive = false; try { c.ws.ping(); } catch {}
  }
}, 30e3);
// wygasłe nadpisania treści
setInterval(() => {
  for (const d of db().devices) if (d.override && d.override.until < Date.now()) { d.override = null; store.save(); send(d.id, { type: 'manifest' }); }
}, 30e3);

server.listen(PORT, () => console.log(`Signage server ${VERSION} – port ${PORT}, dane: ${store.DATA_DIR}`));
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => { store.saveNow(); process.exit(0); });
