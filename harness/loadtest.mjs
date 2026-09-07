// Connects N bots that teleport constantly to force chunk loading.
//
// The point is measuring the shield's cost under a realistic load. A chunk
// packet is most expensive the moment a new chunk is sent, so the bots keep
// moving into fresh terrain rather than standing still.

import mc from 'minecraft-protocol';

const COUNT = Number(process.argv[2] ?? 20);
const SECONDS = Number(process.argv[3] ?? 60);
// A narrow area: terrain is generated once and reused on later rounds.
// Over a wide area the measurement captures world generation, not the plugin.
const SPAN = Number(process.argv[4] ?? 400);
const CX = Number(process.argv[5] ?? 0);
const CZ = Number(process.argv[6] ?? 0);

const clients = [];
let connected = 0;

function spawnBot(i) {
  const name = `load_${String(i).padStart(2, '0')}`;
  const client = mc.createClient({
    host: '127.0.0.1', port: 25565,
    username: name, auth: 'offline', version: '1.21.11',
  });

  let pos = null;
  client.on('position', (p) => {
    client.write('teleport_confirm', { teleportId: p.teleportId });
    pos = { x: p.x, y: p.y, z: p.z, yaw: p.yaw, pitch: p.pitch };
  });

  client.on('login', () => {
    connected++;
    client.write('player_loaded', {});
    // Her bot farkli bolgeye gitsin ki ayni chunk'lar tekrar tekrar gonderilmesin.
    setInterval(() => {
      if (!pos) return;
      const x = CX + Math.round((Math.random() - 0.5) * 2 * SPAN);
      const z = CZ + Math.round((Math.random() - 0.5) * 2 * SPAN);
      client.write('chat_command', { command: `tp @s ${x} 80 ${z}` });
    }, 4000 + i * 120);

    setInterval(() => {
      if (!pos) return;
      try {
        client.write('position_look', {
          x: pos.x, y: pos.y, z: pos.z, yaw: pos.yaw ?? 0, pitch: pos.pitch ?? 0,
          flags: { _value: 0, onGround: false, horizontalCollision: false },
        });
      } catch { /* ignored */ }
    }, 200);
  });

  client.on('error', () => { /* one bot dropping must not break the run */ });
  clients.push(client);
}

for (let i = 0; i < COUNT; i++) {
  setTimeout(() => spawnBot(i), i * 250);
}

setTimeout(() => {
  console.log(`[load] ${connected}/${COUNT} bots connected, running for ${SECONDS}s`);
}, COUNT * 250 + 2000);

setTimeout(() => {
  console.log(`[load] done, ${connected} bots were connected`);
  clients.forEach((c) => { try { c.end(); } catch { /* yoksay */ } });
  process.exit(0);
}, COUNT * 250 + SECONDS * 1000);
