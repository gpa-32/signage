// Prosta baza w pliku JSON (zapis atomowy). Wystarcza dla kilkuset tabletów.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const MEDIA_DIR = path.join(DATA_DIR, 'media');
const APK_DIR = path.join(DATA_DIR, 'apk');
const SHOT_DIR = path.join(DATA_DIR, 'screenshots');
for (const d of [DATA_DIR, MEDIA_DIR, APK_DIR, SHOT_DIR]) fs.mkdirSync(d, { recursive: true });

const empty = () => ({
  settings: { adminUser: 'admin', adminPasswordHash: null, sessionSecret: crypto.randomBytes(32).toString('hex') },
  media: [],
  playlists: [],
  groups: [],
  devices: [],
  enrollCodes: [],
  apk: null,
  log: [],
});

let db;
try {
  db = Object.assign(empty(), JSON.parse(fs.readFileSync(DB_FILE, 'utf8')));
} catch (e) {
  if (fs.existsSync(DB_FILE)) {
    fs.copyFileSync(DB_FILE, DB_FILE + '.broken-' + Date.now());
    console.error('Uszkodzony db.json – zapisano kopię, start z pustą bazą', e.message);
  }
  db = empty();
}

let timer = null;
function saveNow() {
  clearTimeout(timer); timer = null;
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 1));
  fs.renameSync(tmp, DB_FILE);
}
function save() { if (!timer) timer = setTimeout(saveNow, 300); }
process.on('exit', () => { if (timer) saveNow(); });

const id = (n = 8) => crypto.randomBytes(n).toString('hex');
// Kod bez mylących znaków (0/O, 1/I/L)
const ALPH = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
const code = (len = 6) => Array.from(crypto.randomBytes(len), b => ALPH[b % ALPH.length]).join('');

function addLog(text, deviceId) {
  db.log.unshift({ t: Date.now(), text, deviceId: deviceId || null });
  if (db.log.length > 1000) db.log.length = 1000;
  save();
}

module.exports = { db: () => db, save, saveNow, id, code, addLog, DATA_DIR, MEDIA_DIR, APK_DIR, SHOT_DIR };
