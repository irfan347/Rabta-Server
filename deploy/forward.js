'use strict';
// After a move to a new server, the old host runs only this: every request and WebSocket goes on to FORWARD_TO, so
// apps that still use the old address keep working until they update. Start command on the old host:
//   node deploy/forward.js        with FORWARD_TO=https://api.your-domain.com
// No packages needed; nothing is stored here.
const http = require('http');
const https = require('https');
const net = require('net');
const tls = require('tls');

function createForwarder(targetUrl) {
  const target = new URL(targetUrl);
  const secure = target.protocol === 'https:';
  const port = Number(target.port || (secure ? 443 : 80));
  const headersFor = req => ({ ...req.headers, host: target.host });

  const server = http.createServer((req, res) => {
    const upstream = (secure ? https : http).request({ hostname: target.hostname, port, method: req.method, path: req.url, headers: headersFor(req) }, answer => {
      res.writeHead(answer.statusCode, answer.headers);
      answer.pipe(res);
    });
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
    req.pipe(upstream);
  });

  // WebSockets: the same upgrade request is replayed to the new server, then both connections are joined.
  server.on('upgrade', (req, socket, head) => {
    const upstream = secure ? tls.connect({ host: target.hostname, port, servername: target.hostname }) : net.connect(port, target.hostname);
    // Either side going away ends the other, so no half-closed socket is left behind.
    const close = () => { socket.destroy(); upstream.destroy(); };
    upstream.on('error', close); socket.on('error', close);
    upstream.on('close', close); socket.on('close', close);
    upstream.once(secure ? 'secureConnect' : 'connect', () => {
      let request = `${req.method} ${req.url} HTTP/1.1\r\n`;
      for (const [name, value] of Object.entries(headersFor(req))) for (const v of [].concat(value)) request += `${name}: ${v}\r\n`;
      upstream.write(request + '\r\n');
      if (head && head.length) upstream.write(head);
      socket.pipe(upstream).pipe(socket);
    });
  });
  return server;
}

module.exports = { createForwarder };

if (require.main === module) {
  if (!process.env.FORWARD_TO) { console.error('Set FORWARD_TO to the new server, e.g. https://api.your-domain.com'); process.exit(1); }
  const port = process.env.PORT || 3000;
  createForwarder(process.env.FORWARD_TO).listen(port, () => console.log('Rabta forwarding to', process.env.FORWARD_TO, 'on', port));
}
