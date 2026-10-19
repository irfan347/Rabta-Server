const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { once } = require('node:events');
const { createForwarder } = require('../deploy/forward');

const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };

test('old address forwards requests and sockets to the new server', async () => {
  const target = http.createServer((req, res) => res.end(JSON.stringify({ path: req.url, host: req.headers.host, auth: req.headers.authorization })));
  target.on('upgrade', (req, socket) => {
    if (req.headers.upgrade !== 'websocket' || req.headers.authorization !== 'Bearer token') { socket.destroy(); return; }
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n');
    socket.on('data', d => socket.write('echo:' + d));
    socket.on('end', () => socket.destroy());
  });
  const targetPort = await listen(target);
  const forwarder = createForwarder(`http://127.0.0.1:${targetPort}`);
  const port = await listen(forwarder);
  try {
    // Plain requests: same path and headers, addressed to the new host.
    const res = await new Promise((resolve, reject) => http.get({ port, agent: false, path: '/health?x=1', headers: { authorization: 'Bearer token' } }, resolve).on('error', reject));
    let body = ''; for await (const chunk of res) body += chunk;
    assert.deepEqual(JSON.parse(body), { path: '/health?x=1', host: `127.0.0.1:${targetPort}`, auth: 'Bearer token' });

    // WebSocket upgrade: the new server answers, then bytes flow both ways.
    const req = http.request({ port, agent: false, path: '/ws', headers: { Connection: 'Upgrade', Upgrade: 'websocket', Authorization: 'Bearer token' } });
    req.end();
    const [answer, socket] = await once(req, 'upgrade');
    assert.equal(answer.statusCode, 101);
    socket.write('hello');
    const [data] = await once(socket, 'data');
    assert.equal(data.toString(), 'echo:hello');
    socket.destroy();
  } finally {
    forwarder.close(); target.close();
    forwarder.closeAllConnections?.(); target.closeAllConnections?.();
  }
});
