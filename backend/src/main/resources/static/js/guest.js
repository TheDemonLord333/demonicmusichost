/* ══════════════════════════════════════════════════════════════
   Guest Dashboard — Join, Queue, Search
   ══════════════════════════════════════════════════════════════ */

// ── State ─────────────────────────────────────────────────────
let sessionCode = '';
let userId      = '';
let displayName = '';
let ws;
let searchSource = 'all';

// ── Boot ──────────────────────────────────────────────────────
(function init() {
  // Persistente Gast-ID damit ein Reload nicht als neuer Gast zählt
  userId = sessionStorage.getItem('dmh_guest_id') || crypto.randomUUID();
  sessionStorage.setItem('dmh_guest_id', userId);

  // URL-Parameter ?code= vorausfüllen
  const urlCode = new URLSearchParams(location.search).get('code')?.toUpperCase();
  if (urlCode) {
    document.getElementById('code-input').value = urlCode;
  }

  document.getElementById('join-form').addEventListener('submit', e => {
    e.preventDefault();
    joinSession();
  });

  // Code-Eingabe automatisch uppercase
  document.getElementById('code-input').addEventListener('input', function() {
    this.value = this.value.toUpperCase();
  });

  setSource('all');
})();

// ── Join ──────────────────────────────────────────────────────
async function joinSession() {
  const code = document.getElementById('code-input').value.trim().toUpperCase();
  const name = document.getElementById('name-input').value.trim();
  const errEl = document.getElementById('join-error');
  errEl.style.display = 'none';

  if (code.length !== 6) {
    showJoinError('Bitte gib einen 6-stelligen Code ein');
    return;
  }
  if (!name) {
    showJoinError('Bitte gib deinen Namen ein');
    return;
  }

  try {
    const r = await fetch(`/session/${code}/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, displayName: name })
    });

    if (r.status === 404) { showJoinError('Session nicht gefunden'); return; }
    if (!r.ok)            { showJoinError('Beitreten fehlgeschlagen'); return; }

    const data = await r.json();
    sessionCode = code;
    displayName = name;

    // Dashboard befüllen
    document.getElementById('session-code-top').textContent = code;
    document.getElementById('host-name').textContent = 'Host: ' + data.hostDisplayName;
    if (data.currentSong) renderNowPlaying(data.currentSong, data.playbackInfo);
    renderQueue(data.queue || []);

    // Screens tauschen
    document.getElementById('join-screen').style.display  = 'none';
    document.getElementById('dashboard').style.display    = 'grid';

    initWebSocket();

  } catch {
    showJoinError('Verbindung zum Server fehlgeschlagen');
  }
}

function showJoinError(msg) {
  const el = document.getElementById('join-error');
  el.textContent = msg;
  el.style.display = 'block';
}

// ── WebSocket ─────────────────────────────────────────────────
function initWebSocket() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WsClient(`${proto}//${location.host}/ws/${sessionCode}`);
  ws.statusEl = document.getElementById('ws-dot');

  ws.on('_open', () => {
    ws.send('JOIN', { role: 'guest', userId, displayName });
  });

  ws.on('WELCOME', p => {
    if (p.currentSong) renderNowPlaying(p.currentSong, p.playbackInfo);
    renderQueue(p.queue || []);
  });

  ws.on('QUEUE_UPDATE', p => renderQueue(p.queue || []));

  ws.on('NOW_PLAYING', p => {
    renderNowPlaying(p.song, p.playbackInfo);
  });

  ws.on('SESSION_ENDED', () => {
    toast('Die Session wurde vom Host beendet');
    setTimeout(() => location.href = '/', 2000);
  });

  ws.connect();
}

// ── Search ────────────────────────────────────────────────────
function setSource(src) {
  searchSource = src;
  ['all','spotify','youtube'].forEach(s => {
    const btn = document.getElementById('src-' + s);
    if (!btn) return;
    btn.classList.toggle('btn-primary', s === src);
    btn.classList.toggle('btn-ghost',   s !== src);
  });
}

async function doSearch() {
  const q = document.getElementById('search-input').value.trim();
  if (!q) return;
  const list = document.getElementById('search-results');
  list.innerHTML = '<li style="color:var(--text-muted);padding:.5rem 0">Suche…</li>';

  try {
    const r = await fetch(
      `/api/search?q=${encodeURIComponent(q)}&source=${searchSource}&sessionCode=${sessionCode}`
    );
    const d = await r.json();
    const results = [
      ...(d.spotify || []).map(s => ({ ...s, _src: 'spotify' })),
      ...(d.youtube || []).map(s => ({ ...s, _src: 'youtube' }))
    ];
    renderSearchResults(list, results);
  } catch {
    list.innerHTML = '<li style="color:var(--accent);padding:.5rem 0">Suche fehlgeschlagen</li>';
  }
}

function renderSearchResults(list, items) {
  list.innerHTML = '';
  if (!items.length) {
    list.innerHTML = '<li style="color:var(--text-muted);padding:.5rem 0">Keine Ergebnisse</li>';
    return;
  }
  items.forEach(song => {
    const li = document.createElement('li');
    li.className = 'search-item';
    li.innerHTML = `
      <img src="${song.thumbnailUrl || ''}" class="queue-thumb" alt=""
           onerror="this.style.display='none'">
      <div class="queue-info">
        <div class="queue-title">${esc(song.title)}</div>
        <div class="queue-artist">${esc(song.artist)}</div>
      </div>
      <span class="badge badge-${song._src || song.source?.toLowerCase()}">${song._src || song.source}</span>
      <button class="btn btn-sm btn-primary add-btn" title="Hinzufügen">＋</button>
    `;
    li.querySelector('.add-btn').addEventListener('click', e => {
      e.stopPropagation();
      addToQueue(song);
    });
    li.addEventListener('click', () => addToQueue(song));
    list.appendChild(li);
  });
}

async function addToQueue(song) {
  const r = await fetch(`/session/${sessionCode}/queue`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...song,
      addedByUserId:   userId,
      addedByUserName: displayName,
      queueTimestamp:  Date.now()
    })
  });
  if (r.ok) toast(`"${song.title}" hinzugefügt`);
  else      toast('Fehler beim Hinzufügen', true);
}

// ── Render ────────────────────────────────────────────────────
function renderNowPlaying(song, info) {
  if (!song) {
    document.getElementById('np-title').textContent  = '—';
    document.getElementById('np-artist').textContent = '—';
    document.getElementById('np-state').textContent  = 'Gestoppt';
    return;
  }
  const art = document.getElementById('np-art');
  const ph  = document.getElementById('np-placeholder');
  if (song.thumbnailUrl) {
    art.src = song.thumbnailUrl;
    art.style.display = 'block';
    ph.style.display  = 'none';
  } else {
    art.style.display = 'none';
    ph.style.display  = 'flex';
  }
  document.getElementById('np-title').textContent  = song.title  || '—';
  document.getElementById('np-artist').textContent = song.artist || '—';

  const state = info?.state || 'STOPPED';
  document.getElementById('np-state').textContent =
    state === 'PLAYING' ? '▶ Läuft'
    : state === 'PAUSED' ? '⏸ Pausiert'
    : '⏹ Gestoppt';
}

function renderQueue(queue) {
  const list  = document.getElementById('queue-list');
  const empty = document.getElementById('queue-empty');
  const count = document.getElementById('queue-count');
  count.textContent = `${queue.length} Song${queue.length !== 1 ? 's' : ''}`;

  if (!queue.length) {
    list.innerHTML = '';
    empty.style.display = 'block';
    return;
  }
  empty.style.display = 'none';
  list.innerHTML = '';
  queue.forEach((song, i) => {
    const li = document.createElement('li');
    li.className = 'queue-item';
    li.innerHTML = `
      <span style="color:var(--text-muted);font-size:.8rem;flex-shrink:0">${i+1}</span>
      <img src="${song.thumbnailUrl || ''}" class="queue-thumb" alt=""
           onerror="this.style.display='none'">
      <div class="queue-info">
        <div class="queue-title">${esc(song.title)}</div>
        <div class="queue-artist">${esc(song.artist)}</div>
      </div>
      <span class="queue-duration text-muted" style="font-size:.78rem">
        ${song.addedByUserName ? '+ ' + esc(song.addedByUserName) : ''}
      </span>
    `;
    list.appendChild(li);
  });
}

// ── Hilfsfunktionen ───────────────────────────────────────────
function esc(str) {
  return String(str || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function toast(msg, isError = false) {
  const tc = document.getElementById('toast-container');
  const t  = document.createElement('div');
  t.className = 'toast' + (isError ? ' error' : '');
  t.textContent = msg;
  tc.appendChild(t);
  setTimeout(() => t.remove(), 3500);
}

// Enter-Taste für Suche
document.getElementById('search-input')?.addEventListener('keydown', e => {
  if (e.key === 'Enter') doSearch();
});
