// Belirli bir chunk'in ham verisini istemci gozuyle inceler.
//
// Sunucu tarafi denetim, kalkanin degisikligini goremez: paket henuz yeniden
// serialize edilmemistir. Tek gecerli bakis acisi istemcininki - hilenin
// gordugu de tam olarak budur.

import mc from 'minecraft-protocol';

const [, , X, Y, Z] = process.argv;
const bx = Number(X), by = Number(Y), bz = Number(Z);
const cx = bx >> 4, cz = bz >> 4;

const client = mc.createClient({
  host: '127.0.0.1', port: 25565,
  username: 'aclab_probe', auth: 'offline', version: '1.21.11',
});

let reported = false;

function handle(packet) {
  if (reported || packet.x !== cx || packet.z !== cz) return;
  reported = true;

  const data = packet.chunkData ?? packet;
  const entities = data.blockEntities ?? packet.blockEntities ?? [];
  console.log(`[probe] chunk ${cx},${cz} alindi, block entity sayisi: ${entities.length}`);

  const localX = ((bx % 16) + 16) % 16;
  const localZ = ((bz % 16) + 16) % 16;

  const hit = entities.find((e) => {
    const ex = e.x ?? (e.packedXZ !== undefined ? (e.packedXZ >> 4) & 15 : -1);
    const ez = e.z ?? (e.packedXZ !== undefined ? e.packedXZ & 15 : -1);
    return ex === localX && ez === localZ && e.y === by;
  });

  if (hit) {
    console.log(`[probe] SIZDI - hedef konumda block entity var:`,
      JSON.stringify({ x: hit.x, y: hit.y, z: hit.z, type: hit.type }));
  } else {
    console.log(`[probe] GIZLENDI - hedef konumda block entity yok`);
    if (entities.length) {
      console.log('[probe] ornek kayit:', JSON.stringify(entities[0]).slice(0, 160));
    }
  }
  setTimeout(() => { client.end(); process.exit(0); }, 300);
}

client.on('level_chunk_with_light', handle);
client.on('map_chunk', handle);
client.on('error', (e) => { console.error('[probe] hata:', e.message); process.exit(1); });
setTimeout(() => {
  console.error(`[probe] chunk ${cx},${cz} hic gelmedi`);
  process.exit(1);
}, 20000);
