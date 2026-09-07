// Yerel test sunucusuna baglanip chunk paketlerini alan bassiz istemci.
//
// Amaci dogrulamayi tekrarlanabilir kilmak: sunucu chunk'i ancak bir istemci
// bagliyken gonderir, dolayisiyla her test turu bir "cik-gir" gerektirir. Bunu
// elle yapmak dogrulamayi insan hizina baglar. Bot sadece baglanir, chunk'lari
// bekler ve cikar - denetimi sunucudaki eklenti yapar.

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
  console.log(`[bot] giris yapildi (${USERNAME})`);
  if (COMMAND) {
    setTimeout(() => {
      console.log(`[bot] komut: /${COMMAND}`);
      client.write('chat_command', { command: COMMAND });
    }, 1500);
  }
  setTimeout(() => {
    console.log(`[bot] ${chunks} chunk paketi alindi, cikiliyor`);
    client.end();
    process.exit(0);
  }, WAIT_MS);
});

client.on('kick_disconnect', (p) => {
  console.error('[bot] sunucu attı:', JSON.stringify(p).slice(0, 300));
  process.exit(1);
});

client.on('error', (err) => {
  console.error('[bot] hata:', err.message);
  process.exit(1);
});

client.on('end', (reason) => {
  if (!spawned) {
    console.error('[bot] giris yapilamadan koptu:', reason);
    process.exit(1);
  }
});

setTimeout(() => {
  console.error('[bot] zaman asimi - giris yapilamadi');
  process.exit(1);
}, WAIT_MS + 20000);
