// N bot baglar ve surekli isinlayarak chunk yuklenmesini zorlar.
//
// Amac kalkanin maliyetini gercekci bir yuk altinda olcmek. Chunk paketi en
// pahali oldugu an yeni chunk gonderildigi andir, o yuzden botlar duruyor
// degil surekli yeni bolgelere isinlaniyor.

import mc from 'minecraft-protocol';

const COUNT = Number(process.argv[2] ?? 20);
const SECONDS = Number(process.argv[3] ?? 60);
// Dar bir alan: arazi bir kez uretilir, sonraki turlarda yeniden kullanilir.
// Genis alanda olcum plugin'i degil dunya uretimini olcer.
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
      } catch { /* yoksay */ }
    }, 200);
  });

  client.on('error', () => { /* tek bot dususu testi bozmasin */ });
  clients.push(client);
}

for (let i = 0; i < COUNT; i++) {
  setTimeout(() => spawnBot(i), i * 250);
}

setTimeout(() => {
  console.log(`[yuk] ${connected}/${COUNT} bot bagli, ${SECONDS}s boyunca kosuyor`);
}, COUNT * 250 + 2000);

setTimeout(() => {
  console.log(`[yuk] bitti, ${connected} bot baglanmisti`);
  clients.forEach((c) => { try { c.end(); } catch { /* yoksay */ } });
  process.exit(0);
}, COUNT * 250 + SECONDS * 1000);
