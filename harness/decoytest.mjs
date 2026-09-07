// Measures that decoys stay far away and are cleared near the player.
//
// The claim the defence rests on: decoys sit in the distant chunks a cheat
// reads, and not within reach of a legitimate player's digging. This counts
// both separately.

import mc from 'minecraft-protocol';

const client = mc.createClient({
  host: '127.0.0.1', port: 25565,
  username: 'oos_decoy', auth: 'offline', version: '1.21.11',
});

const entities = [];          // {cx, cz, x, y, z}
const corrected = new Set();  // "x,y,z"
let me = null;

client.on('position', (p) => {
  client.write('teleport_confirm', { teleportId: p.teleportId });
  me = { cx: Math.floor(p.x) >> 4, cz: Math.floor(p.z) >> 4 };
});

function onChunk(packet) {
  const data = packet.chunkData ?? packet;
  const list = data.blockEntities ?? packet.blockEntities ?? [];
  for (const e of list) {
    entities.push({
      cx: packet.x, cz: packet.z,
      x: (packet.x << 4) + (e.x ?? 0), y: e.y, z: (packet.z << 4) + (e.z ?? 0),
    });
  }
}
client.on('level_chunk_with_light', onChunk);
client.on('map_chunk', onChunk);

function onUpdate(p) {
  if (p.location) corrected.add(`${p.location.x},${p.location.y},${p.location.z}`);
}
client.on('block_update', onUpdate);
client.on('block_change', onUpdate);

client.on('login', () => {
  client.write('player_loaded', {});
  setTimeout(() => {
    const near = entities.filter((e) => Math.max(Math.abs(e.cx - me.cx), Math.abs(e.cz - me.cz)) <= 3);
    const far = entities.filter((e) => Math.max(Math.abs(e.cx - me.cx), Math.abs(e.cz - me.cz)) > 3);
    const nearFixed = near.filter((e) => corrected.has(`${e.x},${e.y},${e.z}`)).length;
    const farFixed = far.filter((e) => corrected.has(`${e.x},${e.y},${e.z}`)).length;

    console.log(`[decoy] total block entities: ${entities.length}`);
    console.log(`[decoy] NEAR (<=3 chunks): ${near.length}, ${nearFixed} corrected`);
    console.log(`[decoy] FAR  (>3 chunks):  ${far.length}, ${farFixed} corrected`);
    console.log(`[decoy] block updates received: ${corrected.size}`);
    client.end();
    process.exit(0);
  }, 9000);
});

client.on('error', (e) => { console.error('[decoy] error:', e.message); process.exit(1); });
