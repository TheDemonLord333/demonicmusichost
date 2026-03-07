// DMH Spotify Web Playback — Variante B
// Token und Spotify-API-Calls laufen über shouldInterceptRequest,
// kein externer Server nötig.

console.log('DMH app.js isSecureContext=' + window.isSecureContext
  + ' origin=' + location.origin
  + ' ua=' + navigator.userAgent.slice(0, 60));

var wasPlaying = false;

// Holt den aktuellen Access-Token über den von Kotlin abgefangenen Endpunkt.
function fetchToken() {
  return fetch('/api/token')
    .then(function(r) { return r.json(); })
    .then(function(j) { return j.access_token; });
}

window.onSpotifyWebPlaybackSDKReady = function() {
  var player = new Spotify.Player({
    name: 'DMH',
    getOAuthToken: function(cb) {
      fetchToken().then(cb);
    },
    volume: 0.8
  });

  window.spotifyPlayer = player;

  player.addListener('ready', function(data) {
    window.deviceId = data.device_id;
    Android.onDeviceReady(data.device_id);
  });

  player.addListener('not_ready', function(data) {
    Android.onDeviceNotReady(data.device_id);
  });

  player.addListener('player_state_changed', function(state) {
    if (!state) { wasPlaying = false; return; }
    // Natürliches Track-Ende: Position springt auf 0, Player pausiert
    var naturalEnd = state.paused && state.position === 0 && wasPlaying;
    wasPlaying = !state.paused;
    if (naturalEnd) {
      Android.onTrackEnded();
    }
  });

  player.addListener('initialization_error', function(e) {
    Android.onError('init_error:' + e.message);
  });
  player.addListener('authentication_error', function(e) {
    Android.onError('auth_error:' + e.message);
  });
  player.addListener('account_error', function(e) {
    Android.onError('account_error:' + e.message);
  });

  player.connect();
};

// Aufgerufen von Android via evaluateJavascript()
function pausePlayback() {
  if (window.spotifyPlayer) window.spotifyPlayer.pause();
}

function resumePlayback() {
  if (window.spotifyPlayer) window.spotifyPlayer.resume();
}
