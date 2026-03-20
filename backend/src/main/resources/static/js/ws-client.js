/**
 * Minimaler WebSocket-Client mit Auto-Reconnect und Event-Emitter.
 * Verwendung:
 *   const ws = new WsClient('ws://...', onMessage);
 *   ws.on('QUEUE_UPDATE', payload => { ... });
 *   ws.connect();
 *   ws.send('JOIN', { role: 'host', ... });
 */
class WsClient {
  constructor(url) {
    this._url      = url;
    this._handlers = {};        // type → [fn, ...]
    this._ws       = null;
    this._retries  = 0;
    this._maxRetry = 8;
    this._closed   = false;     // explizit geschlossen — kein Reconnect
    this.statusEl  = null;      // optionales DOM-Element für Verbindungsstatus
  }

  on(type, fn) {
    (this._handlers[type] = this._handlers[type] || []).push(fn);
    return this;
  }

  connect() {
    this._closed = false;
    this._open();
  }

  close() {
    this._closed = true;
    this._ws?.close();
  }

  send(type, payload = null) {
    if (this._ws?.readyState !== WebSocket.OPEN) return;
    this._ws.send(JSON.stringify({ type, payload }));
  }

  _open() {
    this._setStatus('connecting');
    this._ws = new WebSocket(this._url);

    this._ws.onopen = () => {
      this._retries = 0;
      this._setStatus('connected');
      this._emit('_open');
    };

    this._ws.onmessage = (evt) => {
      let env;
      try { env = JSON.parse(evt.data); } catch { return; }
      this._emit(env.type, env.payload);
    };

    this._ws.onclose = () => {
      this._setStatus('disconnected');
      this._emit('_close');
      if (!this._closed) this._scheduleReconnect();
    };

    this._ws.onerror = () => {
      // onclose wird direkt danach gefeuert
    };
  }

  _scheduleReconnect() {
    if (this._retries >= this._maxRetry) {
      console.warn('WsClient: max retries reached');
      return;
    }
    const delay = Math.min(1000 * 2 ** this._retries, 30_000);
    this._retries++;
    console.log(`WsClient: reconnecting in ${delay}ms (attempt ${this._retries})`);
    setTimeout(() => this._open(), delay);
  }

  _emit(type, payload) {
    (this._handlers[type] || []).forEach(fn => {
      try { fn(payload); } catch (e) { console.error('WsClient handler error:', e); }
    });
  }

  _setStatus(state) {
    if (!this.statusEl) return;
    this.statusEl.className = `status-dot ${state}`;
    const label = this.statusEl.nextElementSibling;
    if (label) {
      label.textContent = state === 'connected' ? 'Verbunden'
                        : state === 'connecting' ? 'Verbindet…'
                        : 'Getrennt';
    }
  }
}
