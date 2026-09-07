// Breaks the stone above a buried chest and listens for the chest to appear.
//
// Hiding alone is not enough: when a player breaks the wall the server only
// sends an update for the broken block, leaving the chest as stone on the
// client. This script runs exactly that scenario and measures whether the real
// block update reaches the client.

import mc from 'minecraft-protocol';

const [, , X, Y, Z] = process.argv;
const cxx = Number(X), cyy = Number(Y), czz = Number(Z);
const wall = { x: cxx, y: cyy + 1, z: czz };   // the stone above the chest

const client = mc.createClient({
  host: '127.0.0.1', port: 25565,
  username: 'oos_digger', auth: 'offline', version: '1.21.11',
});

let seq = 1;
let revealed = false;
let pos = null;

// After an unconfirmed teleport the server still places the player at the old
// position and rejects the dig as out of range. Confirming is required.
client.on('position', (packet) => {
  client.write('teleport_confirm', { teleportId: packet.teleportId });
  pos = { x: packet.x, y: packet.y, z: packet.z, yaw: packet.yaw, pitch: packet.pitch };
  sendPos();
});

function sendPos() {
  if (!pos) return;
  try {
    client.write('position_look', {
      x: pos.x, y: pos.y, z: pos.z,
      yaw: pos.yaw ?? 0, pitch: pos.pitch ?? 0,
      // A bitfield replaced onGround in 1.21.9.
      flags: { _value: 0, onGround: false, horizontalCollision: false },
    });
  } catch (e) {
    console.error('[dig] could not send position packet:', e.message);
  }
}
setInterval(sendPos, 100);

function watchUpdate(packet) {
  const loc = packet.location;
  if (!loc) return;
  if (loc.x === cxx && loc.y === cyy && loc.z === czz) {
    revealed = true;
    console.log(`[dig] CHEST REVEALED - block update received (stateId=${packet.type ?? packet.blockId})`);
  }
}

client.on('block_update', watchUpdate);
client.on('block_change', watchUpdate);

client.on('login', () => {
  console.log('[dig] logged in');
  // In 1.21 the server ignores gameplay actions until the client reports loaded.
  setTimeout(() => {
    try {
      client.write('player_loaded', {});
      console.log('[dig] player_loaded sent');
    } catch (e) {
      console.error('[dig] could not send player_loaded:', e.message);
    }
  }, 600);
  setTimeout(() => {
    client.write('chat_command', { command: 'gamemode creative' });
  }, 1200);
  setTimeout(() => {
    client.write('chat_command', { command: `tp @s ${cxx} ${cyy + 3} ${czz}` });
  }, 2000);
  setTimeout(() => {
    console.log(`[dig] digging: ${wall.x},${wall.y},${wall.z}`);
    client.write('block_dig', { status: 0, location: wall, face: 1, sequence: seq++ });
    client.write('block_dig', { status: 2, location: wall, face: 1, sequence: seq++ });
  }, 4200);
  setTimeout(() => {
    console.log(revealed ? '[dig] RESULT: revealed' : '[dig] RESULT: STILL HIDDEN - the player cannot see their own chest');
    client.end();
    process.exit(revealed ? 0 : 2);
  }, 12000);
});

client.on('error', (e) => { console.error('[dig] error:', e.message); process.exit(1); });
