// Connects and reports whether a block update arrives for one position.
//
// The chunk packet is only half the story: a container withheld at send time is
// delivered later as a block update once the player may see it. Reading the
// chunk alone would miss that.

import mc from 'minecraft-protocol';

const [, , X, Y, Z] = process.argv;
const bx = Number(X), by = Number(Y), bz = Number(Z);

const client = mc.createClient({
  host: '127.0.0.1', port: 25565,
  username: 'aclab_watch', auth: 'offline', version: '1.21.11',
});

let got = false;
const onUpdate = (p) => {
  const l = p.location;
  if (l && l.x === bx && l.y === by && l.z === bz) {
    got = true;
    console.log(`[watch] DELIVERED - block update at ${bx},${by},${bz}`);
  }
};
client.on('block_update', onUpdate);
client.on('block_change', onUpdate);

client.on('login', () => {
  client.write('player_loaded', {});
  setTimeout(() => {
    if (!got) console.log(`[watch] NOT DELIVERED - no update for ${bx},${by},${bz}`);
    client.end();
    process.exit(0);
  }, 6000);
});
client.on('error', (e) => { console.error('[watch] error:', e.message); process.exit(1); });
