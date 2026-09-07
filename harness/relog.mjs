// Headless client that connects to the local test server and receives chunks.
//
// Its purpose is repeatable verification: the server only sends a chunk while a
// client is connected, so every test round needs a relog. Doing that by hand
// ties verification to human speed. The bot just connects, waits for chunks and
// leaves - the plugin does the auditing.

import mc from 'minecraft-protocol';

const HOST = '127.0.0.1';
const PORT = 25565;
const VERSION = '1.21.11';
const USERNAME = process.argv[2] ?? 'aclab_bot';
const WAIT_MS = Number(process.argv[3] ?? 6000);
const COMMAND = process.argv[4] ?? null;

const client = mc.createClient({
  host: HOST,
  port: PORT,
  username: USERNAME,
  auth: 'offline',
  version: VERSION,
});

let chunks = 0;
let spawned = false;

client.on('level_chunk_with_light', () => { chunks++; });
client.on('map_chunk', () => { chunks++; });

client.on('login', () => {
  spawned = true;
  console.log(`[bot] logged in (${USERNAME})`);
  if (COMMAND) {
    setTimeout(() => {
      console.log(`[bot] command: /${COMMAND}`);
      client.write('chat_command', { command: COMMAND });
    }, 1500);
  }
  setTimeout(() => {
    console.log(`[bot] ${chunks} chunk packets received, leaving`);
    client.end();
    process.exit(0);
  }, WAIT_MS);
});

client.on('kick_disconnect', (p) => {
  console.error('[bot] kicked by server:', JSON.stringify(p).slice(0, 300));
  process.exit(1);
});

client.on('error', (err) => {
  console.error('[bot] error:', err.message);
  process.exit(1);
});

client.on('end', (reason) => {
  if (!spawned) {
    console.error('[bot] disconnected before login:', reason);
    process.exit(1);
  }
});

setTimeout(() => {
  console.error('[bot] timed out before login');
  process.exit(1);
}, WAIT_MS + 20000);
