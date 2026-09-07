// Inspects a chunk's raw data from the client's point of view.
//
// The server-side audit cannot see the shield's changes: the packet has not been
// re-serialised yet. The only valid vantage point is the client's - which is
// exactly what a cheat sees.

import mc from 'minecraft-protocol';

const [, , X, Y, Z] = process.argv;
const bx = Number(X), by = Number(Y), bz = Number(Z);
const cx = bx >> 4, cz = bz >> 4;

const client = mc.createClient({
  host: '127.0.0.1', port: 25565,
  username: 'oos_probe', auth: 'offline', version: '1.21.11',
});

let reported = false;

function handle(packet) {
  if (reported || packet.x !== cx || packet.z !== cz) return;
  reported = true;

  const data = packet.chunkData ?? packet;
  const entities = data.blockEntities ?? packet.blockEntities ?? [];
  console.log(`[probe] chunk ${cx},${cz} received, block entities: ${entities.length}`);

  const localX = ((bx % 16) + 16) % 16;
  const localZ = ((bz % 16) + 16) % 16;

  const hit = entities.find((e) => {
    const ex = e.x ?? (e.packedXZ !== undefined ? (e.packedXZ >> 4) & 15 : -1);
    const ez = e.z ?? (e.packedXZ !== undefined ? e.packedXZ & 15 : -1);
    return ex === localX && ez === localZ && e.y === by;
  });

  if (hit) {
    console.log(`[probe] LEAKED - block entity present at target:`,
      JSON.stringify({ x: hit.x, y: hit.y, z: hit.z, type: hit.type }));
  } else {
    console.log(`[probe] HIDDEN - no block entity at target`);
    if (entities.length) {
      console.log('[probe] sample record:', JSON.stringify(entities[0]).slice(0, 160));
    }
  }
  setTimeout(() => { client.end(); process.exit(0); }, 300);
}

client.on('level_chunk_with_light', handle);
client.on('map_chunk', handle);
client.on('error', (e) => { console.error('[probe] error:', e.message); process.exit(1); });
setTimeout(() => {
  console.error(`[probe] chunk ${cx},${cz} never arrived`);
  process.exit(1);
}, 20000);
