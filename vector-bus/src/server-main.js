// Runnable entrypoint for the networked E2 Bus service. Reads --port or PORT env
// (default 8090), binds 0.0.0.0, and shuts down cleanly on SIGINT/SIGTERM.
import { createBusServer } from './server.js';

function resolvePort() {
  const argv = process.argv.slice(2);
  const idx = argv.indexOf('--port');
  if (idx !== -1 && argv[idx + 1]) {
    const p = Number(argv[idx + 1]);
    if (Number.isFinite(p)) return p;
  }
  if (process.env.PORT) {
    const p = Number(process.env.PORT);
    if (Number.isFinite(p)) return p;
  }
  return 8090;
}

const port = resolvePort();
const host = process.env.HOST || '0.0.0.0';
const server = createBusServer();

server.listen(port, host).then(({ port: boundPort, host: boundHost }) => {
  // eslint-disable-next-line no-console
  console.log('vector-bus networked E2 Bus listening on http://' + boundHost + ':' + boundPort);
}).catch((err) => {
  // eslint-disable-next-line no-console
  console.error('vector-bus failed to start:', err && err.message);
  process.exit(1);
});

let shuttingDown = false;
function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;
  server.close().then(() => process.exit(0));
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
