/* ══════════════════════════════════════════════════════════════
   Host Dashboard — Spotify Web Playback SDK + WebSocket + UI
   ══════════════════════════════════════════════════════════════ */

// ── State ─────────────────────────────────────────────────────
let sessionCode = '';
let accessToken = '';
let deviceId    = '';
let spotifyPlayer = null;
let ws;
let currentDurationMs = 0;
let positionMs        = 0;
let isPlaying         = false;
let progressInterval  = null;
let searchSource      = 'all';
let guests            = {};     // userId → displayName

// ── Boot ──────────────────────────────────────────────────────
(async function init() {
  sessionCode = new URLSearchParams(location.search).get('code')?.toUpperCase() || '';
  if (!sessionCode) { location.href = '/'; return; }

  document.getElementById('session-code').textContent = sessionCode;
  document.title = `Host [${sessionCode}] — DMH`;

  setSource('all');

  try {
    const resp = await fetch('/auth/token');
    if (!resp.ok) { location.href = '/'; return; }
    accessToken = (await resp.json()).access_token;
  } catch {
    location.href = '/';
    return;
  }

  initWebSocket();
  initSpotifySDK();
})();

// ── WebSocket ─────────────────────────────────────────────────
function initWebSocket() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WsClient(`${proto}//${location.host}/ws/${sessionCode}`);
  ws.statusEl = document.getElementById('ws-dot');

  ws.on('_open', () => {
    ws.send('JOIN', {
      role: 'host',
      userId: 'host-' + sessionCode,
      displayName: 'Host'
    });
  });

  ws.on('WELCOME', p => {
    renderQueue(p.queue || []);
    if (p.currentSong) renderNowPlaying(p.currentSong);
  });

  ws.on('QUEUE_UPDATE', p => renderQueue(p.queue || []));

  ws.on('NOW_PLAYING', p => {
    if (p.song) renderNowPlaying(p.song);
  });

  ws.on('GUEST_JOINED', p => {
    guests[p.guest.userId] = p.guest.displayName;
    renderGuests();
    toast(`${p.guest.displayName} ist beigetreten`);
  });

  ws.on('GUEST_LEFT', p => {
    delete guests[p.userId];
    renderGuests();
  });

  ws.on('SESSION_ENDED', () => {
    toast('Session wurde beendet');
    setTimeout(() => location.href = '/', 1500);
  });

  ws.connect();
}

// ── Spotify Web Playback SDK ──────────────────────────────────
function initSpotifySDK() {
  window.onSpotifyWebPlaybackSDKReady = () => {
    spotifyPlayer = new Spotify.Player({
      name: `DMH [${sessionCode}]`,
      getOAuthToken: cb => {
        // Token bei Bedarf erneuern
        fetch('/auth/token')
          .then(r => r.json())
          .then(d => { accessToken = d.access_token; cb(d.access_token); })
          .catch(() => cb(accessToken));
      },
      volume: 0.8
    });

    spotifyPlayer.addListener('ready', ({ device_id }) => {
      deviceId = device_id;
      setSdkStatus('✅ Spotify Player bereit');
      transferPlayback(device_id);
    });

    spotifyPlayer.addListener('not_ready', () => {
      setSdkStatus('⚠️ Player offline');
    });

    spotifyPlayer.addListener('player_state_changed', state => {
      if (!state) return;
      onPlayerStateChanged(state);
    });

    spotifyPlayer.addListener('initialization_error', ({ message }) => {
      setSdkStatus('❌ Init-Fehler: ' + message);
    });
    spotifyPlayer.addListener('authentication_error', ({ message }) => {
      setSdkStatus('❌ Auth-Fehler: ' + message);
    });
    spotifyPlayer.addListener('account_error', () => {
      setSdkStatus('❌ Spotify Premium erforderlich');
    });

    spotifyPlayer.connect();
  };
}

async function transferPlayback(devId) {
  try {
    await spotifyFetch('PUT', 'https://api.spotify.com/v1/me/player', {
      device_ids: [devId],
      play: false
    });
  } catch (e) {
    console.warn('transferPlayback failed:', e);
  }
}

function onPlayerStateChanged(state) {
  const track = state.track_window?.current_track;
  if (!track) return;

  isPlaying         = !state.paused;
  positionMs        = state.position;
  currentDurationMs = state.duration;

  // Now-Playing UI
  const song = {
    id:           track.id,
    title:        track.name,
    artist:       track.artists?.map(a => a.name).join(', ') || '',
    album:        track.album?.name || '',
    thumbnailUrl: track.album?.images?.[0]?.url || '',
    durationMs:   state.duration,
    source:       'SPOTIFY',
    spotifyUri:   track.uri
  };
  renderNowPlaying(song);
  updatePlayBtn();
  startProgressTick();

  // Backend informieren
  fetch(`/session/${sessionCode}/now-playing`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      song,
      playbackInfo: {
        state:      isPlaying ? 'PLAYING' : 'PAUSED',
        positionMs,
        updatedAt:  Date.now()
      }
    })
  }).catch(() => {});
}

// ── Playback Controls ─────────────────────────────────────────
async function togglePlay() {
  if (!spotifyPlayer) return;
  await spotifyPlayer.togglePlay();
}

async function skipNext() {
  if (!spotifyPlayer) return;
  // Zuerst Queue vom Backend holen und nächsten Song laden
  const resp = await fetch(`/session/${sessionCode}/queue/next`, { method: 'POST' });
  if (resp.status === 204) {
    // Queue leer — Spotify-Skip als Fallback
    await spotifyPlayer.nextTrack();
    return;
  }
  const data = await resp.json();
  if (data.song?.spotifyUri) {
    await playUri(data.song.spotifyUri);
  } else if (data.song?.youtubeVideoId) {
    toast('YouTube-Wiedergabe noch nicht im Browser verfügbar');
  }
}

async function skipPrev() {
  if (!spotifyPlayer) return;
  await spotifyPlayer.previousTrack();
}

async function setVolume(val) {
  if (!spotifyPlayer) return;
  await spotifyPlayer.setVolume(val / 100);
}

async function playNext() {
  await skipNext();
}

async function seekTo(evt) {
  if (!spotifyPlayer || !currentDurationMs) return;
  const rect = evt.currentTarget.getBoundingClientRect();
  const ratio = (evt.clientX - rect.left) / rect.width;
  const pos   = Math.round(ratio * currentDurationMs);
  await spotifyPlayer.seek(pos);
}

async function playUri(uri) {
  if (!deviceId) return;
  await spotifyFetch('PUT', `https://api.spotify.com/v1/me/player/play?device_id=${deviceId}`, {
    uris: [uri]
  });
}

// ── Progress Ticker ───────────────────────────────────────────
function startProgressTick() {
  clearInterval(progressInterval);
  progressInterval = setInterval(() => {
    if (!isPlaying) return;
    positionMs = Math.min(positionMs + 500, currentDurationMs);
    updateProgress();
  }, 500);
}

function updateProgress() {
  if (!currentDurationMs) return;
  const pct = (positionMs / currentDurationMs * 100).toFixed(1);
  document.getElementById('progress-fill').style.width  = pct + '%';
  document.getElementById('pos-label').textContent      = formatMs(positionMs);
  document.getElementById('dur-label').textContent      = formatMs(currentDurationMs);
}

// ── Search ────────────────────────────────────────────────────
let searchDebounce;
function setSource(src) {
  searchSource = src;
  ['all','spotify','youtube'].forEach(s => {
    const btn = document.getElementById('src-' + s);
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
    const r = await fetch(`/api/search?q=${encodeURIComponent(q)}&source=${searchSource}&sessionCode=${sessionCode}`);
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
      <button class="btn btn-sm btn-primary add-btn" title="Zur Queue hinzufügen">＋</button>
    `;
    li.querySelector('.add-btn').addEventListener('click', (e) => {
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
      addedByUserId: 'host',
      addedByUserName: 'Host',
      queueTimestamp: Date.now()
    })
  });
  if (r.ok) toast(`"${song.title}" zur Queue hinzugefügt`);
  else      toast('Fehler beim Hinzufügen', true);
}

// ── Session ───────────────────────────────────────────────────
async function endSession() {
  if (!confirm('Session wirklich beenden?')) return;
  await fetch(`/session/${sessionCode}`, { method: 'DELETE' });
  spotifyPlayer?.disconnect();
  location.href = '/';
}

function copyCode() {
  navigator.clipboard.writeText(sessionCode)
    .then(() => toast('Code kopiert!'))
    .catch(() => {});
}

// ── Render ────────────────────────────────────────────────────
function renderNowPlaying(song) {
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
  document.getElementById('np-album').textContent  = song.album  || '';
  currentDurationMs = song.durationMs || 0;
  updateProgress();
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
      <span class="queue-duration">${formatMs(song.durationMs)}</span>
      <button class="btn btn-sm btn-icon" title="Entfernen"
              onclick="removeSong('${song.id}')">✕</button>
    `;
    list.appendChild(li);
  });
}

function renderGuests() {
  const list  = document.getElementById('guest-list');
  const count = document.getElementById('guest-count');
  const entries = Object.entries(guests);
  count.textContent = `${entries.length} Gast${entries.length !== 1 ? 'e' : ''}`;
  if (!entries.length) {
    list.innerHTML = '<li class="text-muted" style="font-size:.85rem;padding:.3rem 0">Noch keine Gäste</li>';
    return;
  }
  list.innerHTML = '';
  entries.forEach(([, name]) => {
    const li = document.createElement('li');
    li.className = 'queue-item';
    li.innerHTML = `<span>👤</span><span>${esc(name)}</span>`;
    list.appendChild(li);
  });
}

function updatePlayBtn() {
  document.getElementById('play-btn').textContent = isPlaying ? '⏸' : '▶';
}

function setSdkStatus(msg) {
  document.getElementById('sdk-status').textContent = msg;
}

async function removeSong(id) {
  await fetch(`/session/${sessionCode}/queue/${id}`, { method: 'DELETE' });
}

// ── Hilfsfunktionen ───────────────────────────────────────────
function formatMs(ms) {
  if (!ms) return '0:00';
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`;
}

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

async function spotifyFetch(method, url, body) {
  return fetch(url, {
    method,
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      'Content-Type': 'application/json'
    },
    body: body ? JSON.stringify(body) : undefined
  });
}

// Suche bei Enter
document.getElementById('search-input')?.addEventListener('keydown', e => {
  if (e.key === 'Enter') doSearch();
});
