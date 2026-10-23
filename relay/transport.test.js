const assert = require('node:assert/strict');
const http = require('node:http');
const { EventEmitter, once } = require('node:events');
const { WebSocket, WebSocketServer } = require('ws');
const { attachRelayTransport } = require('./transport');

async function fixture(secureWebSocketUrl) {
  const server = http.createServer();
  const wss = new WebSocketServer({ noServer: true });
  let connections = 0;
  const messages = [];
  wss.on('connection', client => {
    connections++;
    client.on('message', value => { messages.push(String(value)); client.send(value); });
  });
  attachRelayTransport(server, wss, secureWebSocketUrl);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return { url: `ws://127.0.0.1:${server.address().port}/`,
    get connections() { return connections; }, messages,
    close: async () => { for (const client of wss.clients) client.terminate(); wss.close(); await new Promise(resolve => server.close(resolve)); },
  };
}

async function echo(url, headers = {}) {
  const ws = new WebSocket(url, { headers, handshakeTimeout: 2000 });
  try {
    await once(ws, 'open');
    ws.send('test-only-message');
    const [message] = await once(ws, 'message');
    assert.equal(String(message), 'test-only-message');
  } finally { ws.terminate(); }
}

(async () => {
  const redirect = 'wss://relay.example.net/openintel-relay';
  const modern = await fixture(redirect);
  try {
    const response = await new Promise((resolve, reject) => {
      const ws = new WebSocket(modern.url, { headers: { 'X-OpenIntel-Prefer-TLS': '1' }, handshakeTimeout: 2000 });
      ws.on('unexpected-response', (request, response) => {
        response.resume();
        resolve({ status: response.statusCode, location: response.headers.location, cache: response.headers['cache-control'] });
        ws.terminate();
      });
      ws.on('error', error => { if (!/closed before the connection was established/.test(error.message)) reject(error); });
      ws.on('open', () => { ws.terminate(); reject(new Error('TLS opt-in unexpectedly opened a plaintext session')); });
    });
    assert.equal(response.status, 307);
    assert.equal(response.location, redirect);
    assert.equal(response.cache, 'no-store');
    assert.equal(modern.connections, 0, 'Redirect never creates an unauthenticated plaintext WebSocket');
    assert.deepEqual(modern.messages, [], 'No token or payload can be delivered before the redirect');
    await echo(modern.url);
    assert.equal(modern.connections, 1, 'Older clients remain compatible');
  } finally { await modern.close(); }

  const legacy = await fixture('');
  try {
    await echo(legacy.url, { 'X-OpenIntel-Prefer-TLS': '1' });
    assert.equal(legacy.connections, 1, 'Unconfigured TLS keeps legacy relays working');
  } finally { await legacy.close(); }

  for (const bad of ['ws://relay.example.net/', 'https://relay.example.net/',
    'wss://user:password@relay.example.net/', 'wss://relay.example.net/#fragment', 'wss://relay.example.net/\r\nInjected: value']) {
    assert.throws(() => attachRelayTransport(new EventEmitter(), {}, bad));
  }
  console.log('Relay transport tests passed: opt-in redirect, no pre-upgrade payload, legacy compatibility, unsafe URL rejection');
})().catch(error => { console.error(error); process.exitCode = 1; });
