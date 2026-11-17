// Panel administracyjny Digital Signage (bez bibliotek)
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const DAYS = ['Pn', 'Wt', 'Śr', 'Cz', 'Pt', 'Sb', 'Nd'];
let S = null, tab = location.hash.slice(1) || 'devices', openDevice = null, liveTimer = null;

async function api(path, opt = {}) {
  const o = { method: opt.method || (opt.body ? 'POST' : 'GET'), headers: {} };
  if (opt.body instanceof FormData) o.body = opt.body;
  else if (opt.body) { o.body = JSON.stringify(opt.body); o.headers['Content-Type'] = 'application/json'; }
  const r = await fetch(path, o);
  if (r.status === 401 && !path.startsWith('/api/login')) { showLogin(); throw new Error('Zaloguj się'); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `Błąd ${r.status}`);
  return j;
}
const A = (p, opt) => api('/api/admin' + p, opt);
function toast(t) { const e = $('#toast'); e.textContent = t; e.classList.add('show'); clearTimeout(e._t); e._t = setTimeout(() => e.classList.remove('show'), 2800); }
const fmtDate = t => t ? new Date(t).toLocaleString('pl-PL') : '—';
const fmtAgo = t => { if (!t) return 'nigdy'; const s = (Date.now() - t) / 1000; return s < 60 ? 'przed chwilą' : s < 3600 ? `${Math.round(s / 60)} min temu` : s < 86400 ? `${Math.round(s / 3600)} godz. temu` : fmtDate(t); };
const fmtSize = b => b == null ? '' : b > 1e9 ? (b / 1e9).toFixed(1) + ' GB' : b > 1e6 ? (b / 1e6).toFixed(1) + ' MB' : Math.round(b / 1e3) + ' kB';
const plName = id => S.playlists.find(p => p.id === id)?.name || '—';
const grName = id => S.groups.find(g => g.id === id)?.name || 'bez grupy';
const opts = (list, sel, empty) => (empty !== undefined ? `<option value="">${esc(empty)}</option>` : '') + list.map(x => `<option value="${x.id}" ${x.id === sel ? 'selected' : ''}>${esc(x.name)}</option>`).join('');

// ---------- logowanie ----------
function showLogin() { $('#app').classList.add('hidden'); $('#login').classList.remove('hidden'); clearInterval(window._poll); }
$('#loginForm').onsubmit = async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  try { await api('/api/login', { body: { user: f.get('user'), password: f.get('password') } }); start(); }
  catch (er) { $('#loginErr').textContent = er.message; }
};
$('#logout').onclick = async () => { await api('/api/logout', { body: {} }); showLogin(); };

async function load() { S = await A('/state'); }
async function start() {
  try { await load(); } catch { return; }
  $('#login').classList.add('hidden'); $('#app').classList.remove('hidden');
  render();
  clearInterval(window._poll);
  window._poll = setInterval(async () => {
    if (document.hidden) return;
    const active = document.activeElement;
    const editing = active && ['INPUT', 'TEXTAREA', 'SELECT'].includes(active.tagName);
    await load().catch(() => {});
    if (tab === 'devices' && !editing && $('#modal').classList.contains('hidden')) render();
    if (openDevice && !$('#modal').classList.contains('hidden')) refreshDeviceInfo();
  }, 5000);
}
$$('#tabs a').forEach(a => a.onclick = () => { tab = a.dataset.tab; location.hash = tab; render(); });
function render() {
  $('#main').onclick = null;
  $$('#tabs a').forEach(a => a.classList.toggle('on', a.dataset.tab === tab));
  ({ devices, media, playlists, groups, update, log, account }[tab] || devices)();
}

// ---------- modal ----------
function modal(html) { $('#modalBody').innerHTML = html; $('#modal').classList.remove('hidden'); return $('#modalBody'); }
function closeModal() { $('#modal').classList.add('hidden'); openDevice = null; stopLive(); }
$('#modalClose').onclick = closeModal;
$('#modal').onclick = e => { if (e.target.id === 'modal') closeModal(); };

// ---------- formularze wspólne ----------
function settingsForm(s, prefix = 's') {
  const t = s.ticker || {};
  const sel = (n, v, list) => `<select name="${prefix}.${n}">${list.map(([k, l]) => `<option value="${k}" ${String(v) === String(k) ? 'selected' : ''}>${l}</option>`).join('')}</select>`;
  return `<div class="form">
    <label>Orientacja ekranu ${sel('orientation', s.orientation, [['landscape', 'pozioma'], ['portrait', 'pionowa'], ['reverse_landscape', 'pozioma odwrócona'], ['reverse_portrait', 'pionowa odwrócona'], ['auto', 'automatyczna']])}</label>
    <label>Dopasowanie obrazów ${sel('imageFit', s.imageFit, [['contain', 'cały obraz (pasy)'], ['cover', 'wypełnij ekran (przytnij)']])}</label>
    <label>Przejście ${sel('transition', s.transition, [['fade', 'przenikanie'], ['none', 'bez efektu']])}</label>
    <label>Głośność wideo (0–100)<input type="number" min="0" max="100" name="${prefix}.volume" value="${s.volume}"></label>
    <label>Jasność (0–100, -1 = systemowa)<input type="number" min="-1" max="100" name="${prefix}.brightness" value="${s.brightness}"></label>
    <label>Włączenie ekranu (GG:MM)<input type="time" name="${prefix}.screenOn" value="${esc(s.screenOn)}"></label>
    <label>Wyłączenie ekranu (GG:MM)<input type="time" name="${prefix}.screenOff" value="${esc(s.screenOff)}"></label>
    <label>Codzienny restart tabletu<input type="time" name="${prefix}.rebootAt" value="${esc(s.rebootAt)}"></label>
    <label class="inline"><input type="checkbox" name="${prefix}.kiosk" ${s.kiosk ? 'checked' : ''}> Tryb kiosku (powrót na wierzch)</label>
    <label>Powrót po (s)<input type="number" min="10" name="${prefix}.kioskReturnSec" value="${s.kioskReturnSec}"></label>
  </div>
  <h3>Pasek z przewijanym tekstem</h3>
  <div class="form">
    <label class="inline"><input type="checkbox" name="${prefix}.ticker.enabled" ${t.enabled ? 'checked' : ''}> Pokazuj pasek</label>
    <label style="grid-column:span 2">Tekst<input name="${prefix}.ticker.text" value="${esc(t.text)}"></label>
    <label>Prędkość (1–10)<input type="number" min="1" max="10" name="${prefix}.ticker.speed" value="${t.speed}"></label>
    <label>Wielkość czcionki<input type="number" min="10" max="120" name="${prefix}.ticker.size" value="${t.size}"></label>
    <label>Kolor tekstu<input type="color" name="${prefix}.ticker.fg" value="${esc(t.fg)}"></label>
    <label>Kolor tła<input type="color" name="${prefix}.ticker.bg" value="${esc(t.bg)}"></label>
  </div>`;
}
function readSettings(root, prefix = 's') {
  const o = { ticker: {} };
  $$(`[name^="${prefix}."]`, root).forEach(el => {
    const keys = el.name.slice(prefix.length + 1).split('.');
    let v = el.type === 'checkbox' ? el.checked : el.type === 'number' ? +el.value : el.value;
    let t = o; keys.slice(0, -1).forEach(k => t = t[k] = t[k] || {}); t[keys.at(-1)] = v;
  });
  return o;
}
function contentForm(c) {
  c = c || { playlistId: null, rules: [] };
  return `<label>Playlista domyślna<select name="c.playlistId">${opts(S.playlists, c.playlistId, '— brak (czarny ekran) —')}</select></label>
  <h3>Harmonogram <span class="mut">– pierwsza pasująca reguła wygrywa; poza regułami gra playlista domyślna</span></h3>
  <div class="rules">${(c.rules || []).map(ruleRow).join('')}</div>
  <button type="button" class="ghost small addRule">+ Dodaj regułę</button>`;
}
function ruleRow(r = { days: [1, 2, 3, 4, 5], from: '08:00', to: '16:00', playlistId: '' }) {
  return `<div class="rule"><span class="days">${DAYS.map((d, i) => `<label><input type="checkbox" value="${i + 1}" ${r.days?.includes(i + 1) ? 'checked' : ''}>${d}</label>`).join('')}</span>
  od <input type="time" class="from" value="${r.from}"> do <input type="time" class="to" value="${r.to}">
  <select class="pl">${opts(S.playlists, r.playlistId, '— wybierz playlistę —')}</select>
  <button type="button" class="ghost small delRule">usuń</button></div>`;
}
function bindContent(root) {
  root.addEventListener('click', e => {
    if (e.target.classList.contains('addRule')) $('.rules', root).insertAdjacentHTML('beforeend', ruleRow());
    if (e.target.classList.contains('delRule')) e.target.closest('.rule').remove();
  });
}
function readContent(root) {
  return {
    playlistId: $('[name="c.playlistId"]', root).value || null,
    rules: $$('.rule', root).map(r => ({ days: $$('.days input:checked', r).map(i => +i.value), from: $('.from', r).value, to: $('.to', r).value, playlistId: $('.pl', r).value })).filter(r => r.playlistId),
  };
}

// ---------- TABLETY ----------
function devices() {
  const now = Date.now();
  $('#main').innerHTML = `
  <div class="card"><div class="row" style="justify-content:space-between">
    <h2 style="margin:0">Tablety <span class="mut">(${S.devices.filter(d => d.online).length} online z ${S.devices.length})</span></h2>
    <div class="row"><button id="addDev">+ Dodaj tablety</button>
    <button class="ghost" id="reloadAll">Odśwież treść na wszystkich</button></div></div>
    ${S.pairing.length ? `<p class="mut">Tablety czekające na dodanie (kod na ekranie): ${S.pairing.map(p => `<b>${esc(p.code)}</b> ${esc(p.model || '')} ${esc(p.ip)}`).join(', ')}</p>` : ''}
    <table><thead><tr><th>Nazwa</th><th>Grupa</th><th>Teraz gra</th><th>Stan</th><th>Wersja</th><th>Ostatnio</th></tr></thead><tbody>
    ${S.devices.map(d => `<tr class="click" data-id="${d.id}">
      <td><span class="dot ${d.online ? 'on' : ''}"></span><b>${esc(d.name)}</b><div class="mut">${esc(d.status.model || '')} ${esc(d.status.ip || '')}</div></td>
      <td>${esc(grName(d.groupId))}${d.ownContent ? ' <span class="badge">własna treść</span>' : ''}</td>
      <td>${d.override ? '<span class="badge">nadpisane</span> ' : ''}${esc(d.status.playing || plName(d.nowPlaylistId))}</td>
      <td>${d.online ? (d.status.screenOn === false ? 'ekran wyłączony' : 'działa') : 'offline'}${d.status.battery != null ? ` · 🔋${d.status.battery}%` : ''}</td>
      <td class="mut">${esc(d.status.appVersion || '')}</td>
      <td class="mut">${d.online ? 'online' : fmtAgo(d.lastSeen)}</td></tr>`).join('') || '<tr><td colspan="6" class="mut">Brak tabletów – kliknij „Dodaj tablety”.</td></tr>'}
    </tbody></table></div>`;
  $$('tr[data-id]').forEach(tr => tr.onclick = () => deviceModal(tr.dataset.id));
  $('#addDev').onclick = addDevicesModal;
  $('#reloadAll').onclick = async () => { const r = await A('/devices/cmd-all', { body: { cmd: 'reload' } }); toast(`Wysłano do ${r.filter(x => x.ok).length} tabletów`); };
}

async function addDevicesModal() {
  const m = modal(`<h2>Dodaj tablety</h2>
  <div class="tabs2"><a class="on" data-t="qr">Kod QR / kod rejestracji</a><a data-t="code">Kod z ekranu tabletu</a></div>
  <div data-p="qr">
    <p>Utwórz kod rejestracji i zeskanuj go w aplikacji na tablecie (przycisk <b>Skanuj kod QR</b>) albo wpisz na tablecie adres serwera i kod. Jeden kod działa dla wielu tabletów do czasu wygaśnięcia.</p>
    <div class="row"><label>Grupa dla nowych tabletów<select id="enGroup">${opts(S.groups, null, '— bez grupy —')}</select></label>
    <label>Ważny przez (min)<input id="enMin" type="number" value="30" min="5" max="1440"></label>
    <button id="enNew">Utwórz kod</button></div>
    <div id="enList"></div>
  </div>
  <div data-p="code" class="hidden">
    <p>Zainstaluj aplikację na tablecie i podaj adres serwera <b>${esc(S.publicUrl)}</b>. Tablet wyświetli 6-znakowy kod – wpisz go tutaj.</p>
    <div class="row"><label>Kod z ekranu<input id="pCode" maxlength="6" style="text-transform:uppercase;font-size:20px;width:150px"></label>
    <label>Nazwa tabletu<input id="pName" placeholder="np. Recepcja"></label>
    <label>Grupa<select id="pGroup">${opts(S.groups, null, '— bez grupy —')}</select></label>
    <button id="pGo">Dodaj</button></div>
  </div>`);
  $$('.tabs2 a', m).forEach(a => a.onclick = () => { $$('.tabs2 a', m).forEach(x => x.classList.toggle('on', x === a)); $$('[data-p]', m).forEach(p => p.classList.toggle('hidden', p.dataset.p !== a.dataset.t)); });
  const list = () => { $('#enList').innerHTML = S.enrollCodes.map(c => `<div class="card row" style="align-items:center">
      <img class="qr" src="/api/admin/enroll/${c.code}/qr.svg">
      <div><div class="code">${c.code}</div><div class="mut">Adres serwera: ${esc(S.publicUrl)}<br>Grupa: ${esc(grName(c.groupId))}<br>Ważny do: ${fmtDate(c.expires)} · użyto: ${c.uses}×</div>
      <p><button class="ghost small" data-copy="${esc(S.publicUrl)}/e/${c.code}">Kopiuj link</button> <button class="danger small" data-del="${c.code}">Unieważnij</button></p></div></div>`).join(''); };
  list();
  m.onclick = async e => {
    if (e.target.dataset.copy) { navigator.clipboard?.writeText(e.target.dataset.copy); toast('Skopiowano link'); }
    if (e.target.dataset.del) { await A('/enroll/' + e.target.dataset.del, { method: 'DELETE' }); await load(); list(); }
  };
  $('#enNew').onclick = async () => { await A('/enroll', { body: { groupId: $('#enGroup').value, minutes: +$('#enMin').value } }); await load(); list(); };
  $('#pGo').onclick = async () => {
    try { const d = await A('/pair', { body: { code: $('#pCode').value, name: $('#pName').value, groupId: $('#pGroup').value } }); toast(`Dodano: ${d.name}`); await load(); render(); deviceModal(d.id); }
    catch (e) { toast(e.message); }
  };
}

function deviceModal(id) {
  openDevice = id;
  const d = S.devices.find(x => x.id === id); if (!d) return;
  const m = modal(`<h2><span class="dot ${d.online ? 'on' : ''}" id="dDot"></span>${esc(d.name)}</h2>
  <div class="tabs2"><a class="on" data-t="info">Stan i sterowanie</a><a data-t="screen">Zdalny ekran</a><a data-t="content">Treść</a><a data-t="settings">Ustawienia</a></div>
  <div data-p="info">
    <div class="row" style="align-items:flex-start;gap:24px">
      <div style="flex:1;min-width:300px"><div class="kv" id="dInfo"></div></div>
      <div style="flex:1;min-width:300px">
        <h3>Polecenia</h3>
        <div class="cmds">
          <button data-cmd="reload">Odśwież treść</button>
          <button data-cmd="identify" class="ghost">Pokaż nazwę na ekranie</button>
          <button data-cmd="screen_on" class="ghost">Włącz ekran</button>
          <button data-cmd="screen_off" class="ghost">Wyłącz ekran</button>
          <button data-cmd="restart_app" class="ghost">Restart aplikacji</button>
          <button data-cmd="reboot" class="danger">Restart tabletu</button>
          <button data-cmd="clear_cache" class="ghost">Wyczyść pamięć podręczną</button>
          <button data-cmd="logs" class="ghost">Dziennik tabletu</button>
        </div>
        <h3>Pokaż teraz (nadpisanie harmonogramu)</h3>
        <div class="row"><select id="ovMedia">${opts(S.media, null, '— medium z biblioteki —')}</select>
        <input id="ovUrl" placeholder="lub adres strony https://…" style="flex:1">
        <label>min<input id="ovMin" type="number" value="10" style="width:70px"></label>
        <button id="ovGo">Pokaż</button><button class="ghost" id="ovClear">Przywróć</button></div>
        <h3>Polecenie powłoki (root)</h3>
        <div class="row"><input id="shCmd" placeholder="np. dumpsys battery" style="flex:1"><button id="shGo" class="ghost">Wykonaj</button></div>
        <pre id="out" class="hidden"></pre>
      </div>
    </div>
  </div>
  <div data-p="screen" class="hidden">
    <div class="row" style="margin-bottom:8px">
      <label class="inline"><input type="checkbox" id="live" checked> Odświeżaj na żywo</label>
      <button class="ghost small" data-key="back">◀ Wstecz</button><button class="ghost small" data-key="home">● Ekran główny</button>
      <button class="ghost small" data-key="recents">▢ Ostatnie</button><button class="ghost small" data-key="power">⏻ Zasilanie</button>
      <input id="txt" placeholder="wpisz tekst na tablecie"><button class="ghost small" id="txtGo">Wyślij tekst</button>
      <span class="mut" id="shotInfo"></span>
    </div>
    <div class="screen"><img id="shot" alt="" draggable="false"><span class="mut" id="shotWait">Pobieram obraz ekranu…</span></div>
    <p class="mut">Kliknięcie = dotknięcie ekranu, przeciągnięcie = przesunięcie palcem. Wymaga root na tablecie.</p>
  </div>
  <div data-p="content" class="hidden">
    <label class="inline"><input type="checkbox" id="ownC" ${d.ownContent ? 'checked' : ''}> Własna treść tego tabletu (zamiast treści grupy „${esc(grName(d.groupId))}”)</label>
    <div id="cBox" class="${d.ownContent ? '' : 'hidden'}" style="margin-top:10px">${contentForm(d.content)}</div>
    <p><button id="saveC">Zapisz</button></p>
  </div>
  <div data-p="settings" class="hidden">
    <div class="form"><label>Nazwa<input id="dName" value="${esc(d.name)}"></label>
    <label>Grupa<select id="dGroup">${opts(S.groups, d.groupId, '— bez grupy —')}</select></label>
    <label style="grid-column:span 2">Notatka<input id="dNotes" value="${esc(d.notes)}"></label></div>
    <h3><label class="inline"><input type="checkbox" id="ownS" ${d.ownSettings ? 'checked' : ''}> Własne ustawienia ekranu (zamiast ustawień grupy)</label></h3>
    <div id="sBox" class="${d.ownSettings ? '' : 'hidden'}">${settingsForm(d.ownSettings && d.settings ? { ...S.defaults, ...d.settings, ticker: { ...S.defaults.ticker, ...(d.settings.ticker || {}) } } : d.effective.settings)}</div>
    <p class="row"><button id="saveS">Zapisz</button><span class="grow"></span><button class="danger" id="delDev">Usuń tablet</button></p>
  </div>`);
  $$('.tabs2 a', m).forEach(a => a.onclick = () => {
    $$('.tabs2 a', m).forEach(x => x.classList.toggle('on', x === a));
    $$('[data-p]', m).forEach(p => p.classList.toggle('hidden', p.dataset.p !== a.dataset.t));
    if (a.dataset.t === 'screen') startLive(); else stopLive();
  });
  refreshDeviceInfo();
  bindContent(m);
  const cmd = async (c, args) => {
    const r = await A(`/devices/${id}/cmd`, { body: { cmd: c, args } });
    if (!r.ok) toast(r.error || 'Błąd'); return r;
  };
  $$('[data-cmd]', m).forEach(b => b.onclick = async () => {
    if (b.dataset.cmd === 'reboot' && !confirm('Zrestartować tablet?')) return;
    const r = await cmd(b.dataset.cmd);
    if (r.ok) { toast('Wykonano'); if (r.data?.text) { $('#out').textContent = r.data.text; $('#out').classList.remove('hidden'); } }
  });
  $('#ovGo').onclick = async () => { await cmd('override', { mediaId: $('#ovMedia').value || null, url: $('#ovUrl').value || null, minutes: +$('#ovMin').value }); toast('Wysłano'); };
  $('#ovClear').onclick = async () => { await cmd('override', { clear: true }); toast('Przywrócono harmonogram'); };
  $('#shGo').onclick = async () => { const r = await cmd('shell', { cmd: $('#shCmd').value }); $('#out').textContent = r.ok ? r.data.text : r.error; $('#out').classList.remove('hidden'); };
  $$('[data-key]', m).forEach(b => b.onclick = () => cmd('key', { key: b.dataset.key }).then(liveShot));
  $('#txtGo').onclick = () => cmd('text', { text: $('#txt').value }).then(() => { $('#txt').value = ''; liveShot(); });
  // dotyk / przesunięcie na podglądzie
  const img = $('#shot'); let down = null;
  const pos = e => { const r = img.getBoundingClientRect(); return { x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)), t: Date.now() }; };
  img.onmousedown = e => { e.preventDefault(); down = pos(e); };
  img.onmouseup = e => {
    if (!down) return; const p = pos(e); const dx = Math.abs(p.x - down.x), dy = Math.abs(p.y - down.y);
    const a = (dx + dy > 0.02) ? cmd('swipe', { x1: down.x, y1: down.y, x2: p.x, y2: p.y, ms: Math.max(150, p.t - down.t) }) : cmd('tap', { x: p.x, y: p.y });
    down = null; a.then(() => setTimeout(liveShot, 300));
  };
  $('#live').onchange = () => $('#live').checked ? startLive() : stopLive();
  $('#ownC').onchange = e => $('#cBox').classList.toggle('hidden', !e.target.checked);
  $('#ownS').onchange = e => $('#sBox').classList.toggle('hidden', !e.target.checked);
  $('#saveC').onclick = async () => { await A('/devices/' + id, { method: 'PUT', body: { ownContent: $('#ownC').checked, content: readContent($('#cBox')) } }); await load(); toast('Zapisano'); render(); };
  $('#saveS').onclick = async () => {
    await A('/devices/' + id, { method: 'PUT', body: { name: $('#dName').value, notes: $('#dNotes').value, groupId: $('#dGroup').value, ownSettings: $('#ownS').checked, settings: $('#ownS').checked ? readSettings($('#sBox')) : undefined } });
    await load(); toast('Zapisano'); render();
  };
  $('#delDev').onclick = async () => { if (!confirm('Usunąć tablet? Aplikacja na tablecie wróci do ekranu rejestracji.')) return; await A('/devices/' + id, { method: 'DELETE' }); await load(); closeModal(); render(); };
}
function refreshDeviceInfo() {
  const d = S.devices.find(x => x.id === openDevice); if (!d || !$('#dInfo')) return;
  const s = d.status;
  $('#dDot').className = 'dot ' + (d.online ? 'on' : '');
  const rows = [
    ['Stan', d.online ? 'online' : `offline (${fmtAgo(d.lastSeen)})`], ['Teraz gra', (d.override ? '[nadpisane] ' : '') + (s.playing || plName(d.nowPlaylistId))],
    ['Grupa', grName(d.groupId)], ['Model', `${s.manufacturer || ''} ${s.model || ''}`], ['Android', s.android], ['Aplikacja', s.appVersion],
    ['Root', s.rooted == null ? '' : s.rooted ? 'tak' : 'NIE – część funkcji niedostępna'], ['Adres IP (LAN)', s.ip], ['Adres publiczny', s.publicIp],
    ['Ekran', s.width ? `${s.width}×${s.height}${s.screenOn === false ? ', wyłączony' : ''}` : ''], ['Bateria', s.battery != null ? `${s.battery}%${s.charging ? ' (ładuje)' : ''}` : ''],
    ['Pamięć wolna', s.storageFree ? `${fmtSize(s.storageFree)} z ${fmtSize(s.storageTotal)}` : ''], ['Pamięć podręczna', s.cacheFiles != null ? `${s.cacheFiles} plików, ${fmtSize(s.cacheSize)}` : ''],
    ['Pobieranie', s.sync || ''], ['Działa od', s.uptime ? `${Math.round(s.uptime / 3600)} godz.` : ''], ['Dodany', fmtDate(d.created)],
  ];
  $('#dInfo').innerHTML = rows.map(([k, v]) => `<div>${k}</div><div>${esc(v ?? '')}</div>`).join('');
}
let liveBusy = false;
async function liveShot() {
  if (!openDevice || liveBusy) return; liveBusy = true;
  const id = openDevice, t0 = Date.now();
  try {
    const r = await A(`/devices/${id}/cmd`, { body: { cmd: 'screenshot' } });
    if (r.ok && id === openDevice) {
      const img = $('#shot'); img.src = `/api/admin/devices/${id}/screenshot.jpg?${Date.now()}`;
      $('#shotWait').classList.add('hidden'); $('#shotInfo').textContent = `${new Date().toLocaleTimeString('pl-PL')} · ${Date.now() - t0} ms`;
    } else if (!r.ok) $('#shotWait').textContent = r.error || 'Brak obrazu';
  } finally { liveBusy = false; }
}
function startLive() { stopLive(); liveShot(); liveTimer = setInterval(() => { if ($('#live')?.checked) liveShot(); }, 1500); }
function stopLive() { clearInterval(liveTimer); liveTimer = null; }

// ---------- MULTIMEDIA ----------
function media() {
  $('#main').innerHTML = `<div class="card"><h2>Multimedia</h2>
    <div class="drop" id="drop">Przeciągnij tutaj zdjęcia i filmy (JPG, PNG, GIF, WEBP, MP4, WEBM…) albo kliknij, aby wybrać<input type="file" id="file" multiple accept="image/*,video/*" class="hidden"></div>
    <p class="mut" id="upInfo"></p>
    <h3>Strona www / kanał</h3>
    <div class="row"><label style="flex:1">Adres<input id="wUrl" placeholder="https://…"></label><label>Nazwa<input id="wName"></label><label>Czas (s)<input id="wDur" type="number" value="30" style="width:80px"></label><button id="wAdd">Dodaj stronę</button></div>
  </div>
  <div class="grid">${S.media.slice().reverse().map(m => `<div class="media-card" data-id="${m.id}">
    <div class="th" style="${m.type === 'image' ? `background-image:url('/api/admin/files/${m.file}')` : m.thumb ? `background-image:url('/api/admin/files/${m.thumb}')` : ''}">${m.type === 'web' ? '🌐' : m.type === 'video' && !m.thumb ? '🎬' : ''}</div>
    <div class="bd"><input class="nm" value="${esc(m.name)}">
    <div class="row"><label>Czas (s)<input class="du" type="number" value="${m.duration}" style="width:80px"></label><span class="mut">${m.type === 'web' ? 'strona' : m.type === 'video' ? 'wideo' : 'obraz'} ${fmtSize(m.size)}</span></div>
    ${m.type === 'web' ? `<input class="url" value="${esc(m.url)}">` : ''}
    <div class="row"><button class="small sv">Zapisz</button><button class="small danger dl">Usuń</button></div></div></div>`).join('')}</div>`;
  const drop = $('#drop'), file = $('#file');
  drop.onclick = () => file.click();
  drop.ondragover = e => { e.preventDefault(); drop.classList.add('over'); };
  drop.ondragleave = () => drop.classList.remove('over');
  drop.ondrop = e => { e.preventDefault(); drop.classList.remove('over'); uploadFiles(e.dataTransfer.files); };
  file.onchange = () => uploadFiles(file.files);
  $('#wAdd').onclick = async () => { try { await A('/media/url', { body: { url: $('#wUrl').value, name: $('#wName').value, duration: $('#wDur').value } }); await load(); media(); } catch (e) { toast(e.message); } };
  $$('.media-card').forEach(c => {
    const id = c.dataset.id;
    $('.sv', c).onclick = async () => { await A('/media/' + id, { method: 'PUT', body: { name: $('.nm', c).value, duration: $('.du', c).value, url: $('.url', c)?.value } }); await load(); toast('Zapisano'); };
    $('.dl', c).onclick = async () => { if (!confirm('Usunąć? Zniknie też z playlist.')) return; await A('/media/' + id, { method: 'DELETE' }); await load(); media(); };
  });
}
function uploadFiles(files) {
  if (!files.length) return;
  const fd = new FormData(); [...files].forEach(f => fd.append('files', f));
  const x = new XMLHttpRequest(); x.open('POST', '/api/admin/media');
  x.upload.onprogress = e => $('#upInfo').textContent = `Wysyłanie… ${Math.round(e.loaded / e.total * 100)}%`;
  x.onload = async () => { $('#upInfo').textContent = x.status === 200 ? `Dodano ${JSON.parse(x.responseText).length} plików` : 'Błąd wysyłania'; await load(); media(); };
  x.onerror = () => $('#upInfo').textContent = 'Błąd wysyłania';
  x.send(fd);
}

// ---------- PLAYLISTY ----------
let curPl = null;
function playlists() {
  if (!S.playlists.find(p => p.id === curPl)) curPl = S.playlists[0]?.id || null;
  const p = S.playlists.find(x => x.id === curPl);
  $('#main').innerHTML = `<div class="row" style="align-items:flex-start;gap:16px">
  <div class="card" style="width:260px"><h2>Playlisty</h2>
    ${S.playlists.map(x => `<div><a style="cursor:pointer;${x.id === curPl ? 'font-weight:700' : ''}" data-pl="${x.id}">${esc(x.name)}</a> <span class="mut">(${x.items.length})</span></div>`).join('')}
    <p><button id="plNew" class="small">+ Nowa playlista</button></p></div>
  <div class="card" style="flex:1;min-width:320px">${p ? `
    <div class="row"><label style="flex:1">Nazwa<input id="plName" value="${esc(p.name)}"></label><button id="plSave">Zapisz</button><button class="danger" id="plDel">Usuń</button></div>
    <h3>Kolejność odtwarzania <span class="mut">– łączny czas: <span id="plTotal"></span></span></h3>
    <ul class="items" id="plItems"></ul>
    <h3>Dodaj z biblioteki</h3>
    <div class="grid">${S.media.map(m => `<div class="media-card"><div class="th" style="height:80px;${m.type === 'image' ? `background-image:url('/api/admin/files/${m.file}')` : m.thumb ? `background-image:url('/api/admin/files/${m.thumb}')` : ''}">${m.type === 'web' ? '🌐' : m.type === 'video' && !m.thumb ? '🎬' : ''}</div>
      <div class="bd"><span class="nm">${esc(m.name)}</span><button class="small" data-add="${m.id}">+ Dodaj</button></div></div>`).join('') || '<p class="mut">Biblioteka jest pusta – dodaj pliki w zakładce Multimedia.</p>'}</div>` : '<p class="mut">Utwórz pierwszą playlistę.</p>'}</div></div>`;
  $$('[data-pl]').forEach(a => a.onclick = () => { curPl = a.dataset.pl; playlists(); });
  $('#plNew').onclick = async () => { const n = await A('/playlists', { body: { name: 'Nowa playlista' } }); curPl = n.id; await load(); playlists(); };
  if (!p) return;
  let items = p.items.map(i => ({ ...i }));
  const draw = () => {
    $('#plItems').innerHTML = items.map((it, i) => { const m = S.media.find(x => x.id === it.mediaId) || {}; return `<li>
      <span>${m.type === 'video' ? '🎬' : m.type === 'web' ? '🌐' : '🖼️'}</span><span class="nm">${esc(m.name)}</span>
      <label class="inline">czas <input type="number" min="1" value="${it.duration}" data-i="${i}" class="idur"> s</label>
      <button class="ghost small" data-up="${i}">▲</button><button class="ghost small" data-down="${i}">▼</button><button class="ghost small" data-rm="${i}">✕</button></li>`; }).join('') || '<li class="mut">Pusta – dodaj elementy z biblioteki poniżej.</li>';
    const tot = items.reduce((a, b) => a + (+b.duration || 0), 0); $('#plTotal').textContent = `${Math.floor(tot / 60)} min ${tot % 60} s`;
  };
  draw();
  $('#main').onclick = e => {
    const t = e.target.dataset;
    if (t.add) { const m = S.media.find(x => x.id === t.add); items.push({ mediaId: m.id, duration: m.duration }); draw(); }
    if (t.rm) { items.splice(+t.rm, 1); draw(); }
    if (t.up && +t.up > 0) { const i = +t.up; [items[i - 1], items[i]] = [items[i], items[i - 1]]; draw(); }
    if (t.down && +t.down < items.length - 1) { const i = +t.down; [items[i + 1], items[i]] = [items[i], items[i + 1]]; draw(); }
  };
  $('#plItems').oninput = e => { if (e.target.dataset.i) { items[+e.target.dataset.i].duration = +e.target.value; } };
  $('#plSave').onclick = async () => { await A('/playlists/' + p.id, { method: 'PUT', body: { name: $('#plName').value, items } }); await load(); toast('Zapisano – tablety pobiorą zmiany'); playlists(); };
  $('#plDel').onclick = async () => { if (!confirm('Usunąć playlistę?')) return; await A('/playlists/' + p.id, { method: 'DELETE' }); await load(); playlists(); };
}
// ---------- GRUPY ----------
let curGr = null;
function groups() {
  if (!S.groups.find(g => g.id === curGr)) curGr = S.groups[0]?.id || null;
  const g = S.groups.find(x => x.id === curGr);
  const eff = g ? { ...S.defaults, ...g.settings, ticker: { ...S.defaults.ticker, ...(g.settings?.ticker || {}) } } : null;
  $('#main').innerHTML = `<div class="row" style="align-items:flex-start;gap:16px">
  <div class="card" style="width:260px"><h2>Grupy</h2>
    ${S.groups.map(x => `<div><a style="cursor:pointer;${x.id === curGr ? 'font-weight:700' : ''}" data-gr="${x.id}">${esc(x.name)}</a> <span class="mut">(${S.devices.filter(d => d.groupId === x.id).length} tabl.)</span></div>`).join('')}
    <p><button id="grNew" class="small">+ Nowa grupa</button></p>
    <p class="mut">Grupa to zestaw tabletów z tą samą treścią i ustawieniami. Pojedynczy tablet może mieć własne.</p></div>
  <div class="card" style="flex:1;min-width:320px" id="grBox">${g ? `
    <div class="row"><label style="flex:1">Nazwa<input id="grName" value="${esc(g.name)}"></label><button class="danger" id="grDel">Usuń grupę</button></div>
    <h3>Treść</h3>${contentForm(g.content)}
    <h3>Ustawienia ekranu</h3>${settingsForm(eff)}
    <p><button id="grSave">Zapisz</button></p>` : '<p class="mut">Utwórz grupę, np. „Recepcja”, „Hala produkcyjna”.</p>'}</div></div>`;
  $$('[data-gr]').forEach(a => a.onclick = () => { curGr = a.dataset.gr; groups(); });
  $('#grNew').onclick = async () => { const n = await A('/groups', { body: { name: 'Nowa grupa' } }); curGr = n.id; await load(); groups(); };
  if (!g) return;
  bindContent($('#grBox'));
  $('#grSave').onclick = async () => { await A('/groups/' + g.id, { method: 'PUT', body: { name: $('#grName').value, content: readContent($('#grBox')), settings: readSettings($('#grBox')) } }); await load(); toast('Zapisano – tablety pobiorą zmiany'); groups(); };
  $('#grDel').onclick = async () => { if (!confirm('Usunąć grupę? Tablety zostaną bez grupy.')) return; await A('/groups/' + g.id, { method: 'DELETE' }); await load(); groups(); };
}

// ---------- AKTUALIZACJA ----------
function update() {
  const a = S.apk;
  $('#main').innerHTML = `<div class="card"><h2>Aktualizacja aplikacji na tabletach</h2>
    <p>Wgraj nowy plik <b>signage-player.apk</b> (z zakładki Releases w GitHub), a następnie wyślij go na tablety. Tablety z root instalują aktualizację bez potwierdzania i same uruchamiają aplikację ponownie.</p>
    <div class="row"><input type="file" id="apkF" accept=".apk"><label>Wersja (opcjonalnie)<input id="apkV" placeholder="np. 1.0.3"></label><button id="apkUp">Wgraj</button></div>
    <p class="mut">${a ? `Na serwerze: wersja ${esc(a.version || '?')}, ${fmtSize(a.size)}, wgrano ${fmtDate(a.uploaded)}` : 'Brak pliku APK na serwerze.'}</p>
    ${a ? `<h3>Wyślij na tablety</h3><table>${S.devices.map(d => `<tr><td><label class="inline"><input type="checkbox" class="upd" value="${d.id}" ${d.online ? 'checked' : 'disabled'}> <span class="dot ${d.online ? 'on' : ''}"></span>${esc(d.name)}</label></td><td class="mut">obecnie: ${esc(d.status.appVersion || '?')}</td></tr>`).join('')}</table>
    <p><button id="apkPush">Aktualizuj zaznaczone</button></p><pre id="apkOut" class="hidden"></pre>` : ''}</div>`;
  $('#apkUp').onclick = async () => {
    const f = $('#apkF').files[0]; if (!f) return toast('Wybierz plik');
    const fd = new FormData(); fd.append('version', $('#apkV').value); fd.append('apk', f);
    toast('Wysyłanie…'); await A('/apk', { body: fd }); await load(); update();
  };
  $('#apkPush') && ($('#apkPush').onclick = async () => {
    const ids = $$('.upd:checked').map(i => i.value); if (!ids.length) return;
    $('#apkOut').classList.remove('hidden'); $('#apkOut').textContent = 'Tablety pobierają i instalują aktualizację…';
    const r = await A('/apk/push', { body: { ids } });
    $('#apkOut').textContent = r.map(x => `${x.name}: ${x.ok ? 'OK – instaluję' : 'błąd: ' + x.error}`).join('\n');
  });
}
// ---------- DZIENNIK ----------
async function log() {
  const L = await A('/log');
  $('#main').innerHTML = `<div class="card"><h2>Dziennik</h2><table>${L.map(l => `<tr><td class="mut" style="width:170px">${fmtDate(l.t)}</td><td>${esc(l.text)}</td></tr>`).join('')}</table></div>`;
}
// ---------- KONTO ----------
function account() {
  $('#main').innerHTML = `<div class="card" style="max-width:420px"><h2>Zmiana hasła</h2>
    <label>Obecne hasło<input type="password" id="pOld"></label><br>
    <label>Nowe hasło (min. 8 znaków)<input type="password" id="pNew"></label><br>
    <button id="pSave">Zmień hasło</button></div>
    <div class="card" style="max-width:420px"><h2>Serwer</h2><div class="kv"><div>Wersja</div><div>${esc(S.version)}</div><div>Adres publiczny</div><div>${esc(S.publicUrl)}</div></div></div>`;
  $('#pSave').onclick = async () => { try { await A('/password', { body: { old: $('#pOld').value, password: $('#pNew').value } }); toast('Hasło zmienione'); } catch (e) { toast(e.message); } };
}

start().catch(showLogin);
