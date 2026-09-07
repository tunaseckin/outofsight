// Gomulu sandigin ustundeki tasi kirar ve sandigin ortaya cikip cikmadigini dinler.
//
// Gizlemek tek basina yeterli degil: oyuncu duvari kirdiginda sunucu yalnizca
// kirilan blogun guncellemesini gonderir, sandik istemcide tas olarak kalir.
// Bu betik tam olarak o senaryoyu kosar ve gercek blok guncellemesinin
// istemciye ulasip ulasmadigini olcer.

import mc from 'minecraft-protocol';

const [, , X, Y, Z] = process.argv;
const cxx = Number(X), cyy = Number(Y), czz = Number(Z);
const wall = { x: cxx, y: cyy + 1, z: czz };   // sandigin ustundeki tas

const client = mc.createClient({
  host: '127.0.0.1', port: 25565,
  username: 'aclab_digger', auth: 'offline', version: '1.21.11',
});

let seq = 1;
let revealed = false;
let pos = null;

// Sunucu, onaylanmamis isinlanmadan sonra oyuncuyu eski konumunda sayar ve
// uzaktan kazma girisimini reddeder. Isinlanmayi onaylamak sart.
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
      // 1.21.9'dan itibaren onGround yerine bitfield.
      flags: { _value: 0, onGround: false, horizontalCollision: false },
    });
  } catch (e) {
    console.error('[dig] konum paketi gonderilemedi:', e.message);
  }
}
setInterval(sendPos, 100);

function watchUpdate(packet) {
  const loc = packet.location;
  if (!loc) return;
  if (loc.x === cxx && loc.y === cyy && loc.z === czz) {
    revealed = true;
    console.log(`[dig] SANDIK ORTAYA CIKTI - blok guncellemesi geldi (stateId=${packet.type ?? packet.blockId})`);
  }
}

client.on('block_update', watchUpdate);
client.on('block_change', watchUpdate);

client.on('login', () => {
  console.log('[dig] giris yapildi');
  // 1.21'de istemci "yuklendim" demeden sunucu oyun eylemlerini yok sayar.
  setTimeout(() => {
    try {
      client.write('player_loaded', {});
      console.log('[dig] player_loaded gonderildi');
    } catch (e) {
      console.error('[dig] player_loaded gonderilemedi:', e.message);
    }
  }, 600);
  setTimeout(() => {
    client.write('chat_command', { command: 'gamemode creative' });
  }, 1200);
  setTimeout(() => {
    client.write('chat_command', { command: `tp @s ${cxx} ${cyy + 3} ${czz}` });
  }, 2000);
  setTimeout(() => {
    console.log(`[dig] kaziliyor: ${wall.x},${wall.y},${wall.z}`);
    client.write('block_dig', { status: 0, location: wall, face: 1, sequence: seq++ });
    client.write('block_dig', { status: 2, location: wall, face: 1, sequence: seq++ });
  }, 4200);
  setTimeout(() => {
    console.log(revealed ? '[dig] SONUC: ortaya cikti' : '[dig] SONUC: HALA GIZLI - oyuncu kendi sandigini goremez');
    client.end();
    process.exit(revealed ? 0 : 2);
  }, 12000);
});

client.on('error', (e) => { console.error('[dig] hata:', e.message); process.exit(1); });
