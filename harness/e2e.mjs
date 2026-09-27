// End-to-end test: starts a real Paper server, builds known scenes with console
// commands, joins with a headless client and checks what the client was sent.
//
// The client's view is the only one that counts, because it is exactly what a
// cheat sees. Every check reads the decoded packets: block states, the block
// entity list, later block updates and entity spawns.
//
// Usage: node e2e.mjs <server dir>   (the dir holds paper.jar and plugins/)
// E2E_CLIENT_VERSION picks the protocol the client speaks (default 1.21.11).
// Exit code 0 means every check passed.

import { spawn } from 'node:child_process';
import mc from 'minecraft-protocol';
import prismarineChunk from 'prismarine-chunk';
import prismarineRegistry from 'prismarine-registry';
import { Vec3 } from 'vec3';

const SERVER_DIR = process.argv[2] ?? 'server';
const VERSION = process.env.E2E_CLIENT_VERSION ?? '1.21.11';
const BOT = 'oos_probe';
const registry = prismarineRegistry(VERSION);
const Chunk = prismarineChunk(registry);

const OVERWORLD = { minY: -64, worldHeight: 384 };
const NETHER = { minY: 0, worldHeight: 256 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- server ---------------------------------------------------------------

let log = '';
const server = spawn('java', ['-Xmx2G', '-jar', 'paper.jar', '--nogui'], { cwd: SERVER_DIR });
server.stdout.on('data', (d) => { log += d; process.stdout.write(`[server] ${d}`); });
server.stderr.on('data', (d) => { log += d; process.stdout.write(`[server!] ${d}`); });
let serverExited = false;
server.on('exit', (code) => { serverExited = true; console.log(`[e2e] server exited (${code})`); });

function console_(command) {
  console.log(`[e2e] console> ${command}`);
  server.stdin.write(`${command}\n`);
}

async function waitForLog(pattern, ms, from = 0) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (pattern.test(log.slice(from))) return true;
    if (serverExited) return false;
    await sleep(250);
  }
  return false;
}

// --- client view ------------------------------------------------------------

/** Connects and records everything the client is told about the world. */
function connect() {
  const view = {
    dims: OVERWORLD,
    chunks: new Map(),
    blockEntities: new Set(),
    tileUpdates: [],
    spawns: [],
    kicked: null,
    ready: null,
  };
  const client = mc.createClient({
    host: '127.0.0.1', port: 25565, username: BOT, auth: 'offline', version: VERSION,
  });
  view.client = client;
  view.ready = new Promise((resolve, reject) => {
    client.once('login', resolve);
    client.once('error', reject);
    setTimeout(() => reject(new Error('login timed out')), 30000);
  });

  const chunkKey = (x, z) => `${x},${z}`;
  const posKey = (x, y, z) => `${x},${y},${z}`;

  const onChunk = (packet) => {
    const chunk = new Chunk(view.dims);
    try {
      chunk.load(packet.chunkData);
    } catch (e) {
      console.error(`[e2e] could not decode chunk ${packet.x},${packet.z}: ${e.message}`);
      return;
    }
    view.chunks.set(chunkKey(packet.x, packet.z), chunk);
    // A resent chunk replaces what the client knew about its block entities.
    for (const key of [...view.blockEntities]) {
      const [x, , z] = key.split(',').map(Number);
      if (x >> 4 === packet.x && z >> 4 === packet.z) view.blockEntities.delete(key);
    }
    for (const e of packet.blockEntities ?? []) {
      const lx = e.x ?? (e.packedXZ !== undefined ? (e.packedXZ >> 4) & 15 : null);
      const lz = e.z ?? (e.packedXZ !== undefined ? e.packedXZ & 15 : null);
      if (lx === null || lz === null) continue;
      view.blockEntities.add(posKey(packet.x * 16 + lx, e.y, packet.z * 16 + lz));
    }
  };
  client.on('map_chunk', onChunk);
  client.on('level_chunk_with_light', onChunk);

  client.on('block_change', (p) => {
    const { x, y, z } = p.location;
    const chunk = view.chunks.get(chunkKey(x >> 4, z >> 4));
    if (chunk) chunk.setBlockStateId(new Vec3(x & 15, y, z & 15), p.type);
  });
  client.on('tile_entity_data', (p) => {
    const { x, y, z } = p.location;
    view.blockEntities.add(posKey(x, y, z));
    view.tileUpdates.push(posKey(x, y, z));
  });
  client.on('spawn_entity', (p) => {
    const type = registry.entities[p.type]?.name ?? `#${p.type}`;
    view.spawns.push({ type, x: p.x, y: p.y, z: p.z });
  });
  client.on('respawn', () => {
    view.chunks.clear();
    view.blockEntities.clear();
  });
  client.on('kick_disconnect', (p) => { view.kicked = JSON.stringify(p).slice(0, 300); });
  client.on('disconnect', (p) => { view.kicked = JSON.stringify(p).slice(0, 300); });

  view.stateAt = (x, y, z) => {
    const chunk = view.chunks.get(chunkKey(x >> 4, z >> 4));
    if (!chunk) return null;
    const id = chunk.getBlockStateId(new Vec3(x & 15, y, z & 15));
    return registry.blocksByStateId[id]?.name ?? `#${id}`;
  };
  view.hasBlockEntity = (x, y, z) => view.blockEntities.has(posKey(x, y, z));
  view.spawnedNear = (type, x, y, z) => view.spawns.some((s) => s.type === type
      && Math.abs(s.x - x) < 1.5 && Math.abs(s.y - y) < 1.5 && Math.abs(s.z - z) < 1.5);
  view.chat = (command) => client.write('chat_command', { command });
  view.leave = async () => { client.end(); await sleep(1500); };
  return view;
}

// --- checks -----------------------------------------------------------------

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`[e2e] ${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` - ${detail}` : ''}`);
}

/** A container the client should not know about: no block entity and no chest block. */
function expectHidden(view, name, [x, y, z]) {
  const state = view.stateAt(x, y, z);
  check(`${name}: hidden`, state !== null && state !== 'chest' && !view.hasBlockEntity(x, y, z),
      `block=${state}, blockEntity=${view.hasBlockEntity(x, y, z)}`);
}

/** A container the client should have: the chest block and its block entity. */
function expectVisible(view, name, [x, y, z]) {
  const state = view.stateAt(x, y, z);
  check(`${name}: visible`, state === 'chest' && view.hasBlockEntity(x, y, z),
      `block=${state}, blockEntity=${view.hasBlockEntity(x, y, z)}`);
}

// Scene. The bot stands at (0.5, -60, 0.5) on a superflat world.
const BURIED = [6, -62, 6];          // Under the grass, sealed on all sides.
const OPEN_NEAR = [5, -60, 0];       // On the grass, in plain view, 5 blocks away.
const OPEN_FAR = [60, -60, 0];       // In plain view but beyond hide-beyond-blocks.
const DOUBLE_NEAR = [0, -60, 8];     // A double chest seen end-on: the ray to
const DOUBLE_FAR = [0, -60, 9];      // the far half hits the near half first.
const CART_BURIED = [10.5, -62, 10.5];
const CART_OPEN = [3.5, -60, -3.5];
const NETHER_BURIED = [0, 62, 0];    // Nether floor is y=0, not -64.

async function main() {
  if (!(await waitForLog(/Done \(/, 600000))) {
    throw new Error('server did not start');
  }

  console_(`op ${BOT}`);
  console_('forceload add -32 -32 80 32');
  console_(`setblock ${BURIED.join(' ')} minecraft:chest`);
  console_(`setblock ${OPEN_NEAR.join(' ')} minecraft:chest`);
  console_(`setblock ${OPEN_FAR.join(' ')} minecraft:chest`);
  console_(`setblock ${DOUBLE_NEAR.join(' ')} minecraft:chest[facing=east,type=right]`);
  console_(`setblock ${DOUBLE_FAR.join(' ')} minecraft:chest[facing=east,type=left]`);
  console_('setblock 10 -62 10 minecraft:air');
  console_(`summon minecraft:chest_minecart ${CART_BURIED.join(' ')}`);
  console_(`summon minecraft:chest_minecart ${CART_OPEN.join(' ')}`);
  const inNether = 'execute in minecraft:the_nether run';
  console_(`${inNether} forceload add -16 -16 16 16`);
  console_(`${inNether} fill -5 58 -5 5 72 5 minecraft:stone`);
  console_(`${inNether} fill -4 67 -4 4 71 4 minecraft:air`);
  console_(`${inNether} setblock ${NETHER_BURIED.join(' ')} minecraft:chest`);
  await sleep(3000);

  // Priming visit. Blocks placed by commands fire no event, so the plugin
  // finds them with its periodic sweep around players. That is by design; a
  // player placing a chest is caught at once. The visit also fixes where the
  // bot spawns next time.
  let view = connect();
  await view.ready;
  await sleep(1500);
  console_(`tp ${BOT} 0.5 -60 0.5 0 0`);
  await sleep(3500);
  console_(`${inNether} tp ${BOT} 0.5 67 0.5`);
  await sleep(3500);
  console_(`execute in minecraft:overworld run tp ${BOT} 0.5 -60 0.5 0 0`);
  await sleep(2000);
  await view.leave();

  // --- shield on, overworld ---
  view = connect();
  await view.ready;
  await sleep(4000);
  check('overworld chunks decoded', view.chunks.size > 20, `${view.chunks.size} chunks`);
  expectHidden(view, 'buried chest', BURIED);
  expectVisible(view, 'chest in plain view', OPEN_NEAR);
  expectHidden(view, 'chest beyond hide-beyond-blocks', OPEN_FAR);
  expectVisible(view, 'double chest near half', DOUBLE_NEAR);
  expectVisible(view, 'double chest far half (end-on)', DOUBLE_FAR);
  check('buried chest minecart: not sent',
      !view.spawnedNear('chest_minecart', ...CART_BURIED), JSON.stringify(view.spawns));
  check('chest minecart in plain view: sent',
      view.spawnedNear('chest_minecart', ...CART_OPEN), JSON.stringify(view.spawns));

  // --- shield on, nether ---
  view.dims = NETHER;
  console_(`${inNether} tp ${BOT} 0.5 67 0.5`);
  await sleep(4000);
  check('nether chunks decoded', view.chunks.size > 20, `${view.chunks.size} chunks`);
  expectHidden(view, 'nether buried chest', NETHER_BURIED);
  // Read back with the Nether's own floor: if the plugin used the Overworld's,
  // the edit lands 64 blocks off and the chest stays a chest.
  const floor = view.stateAt(0, 66, 0);
  check('nether: blocks around it untouched', floor === 'stone', `block under the bot=${floor}`);

  // --- shield off: the same scene must leak, or the checks above prove nothing ---
  view.dims = OVERWORLD;
  console_(`execute in minecraft:overworld run tp ${BOT} 0.5 -60 0.5 0 0`);
  await sleep(1500);
  view.chat('oos shield');
  await sleep(1000);
  view.chat('oos advise');
  await sleep(1000);
  await view.leave();

  view = connect();
  await view.ready;
  await sleep(4000);
  const leaked = view.stateAt(...BURIED) === 'chest' && view.hasBlockEntity(...BURIED);
  check('control: with the shield off the buried chest leaks', leaked,
      `block=${view.stateAt(...BURIED)}, blockEntity=${view.hasBlockEntity(...BURIED)}`);
  check('control: with the shield off the buried minecart is sent',
      view.spawnedNear('chest_minecart', ...CART_BURIED), JSON.stringify(view.spawns));
  check('client never kicked', view.kicked === null, view.kicked ?? '');
  await view.leave();

  // --- server log ---
  check('advisor ran', /\[advise\]/.test(log));
  const ours = log.split('\n').filter((line) => /io\.github\.tunaseckin|Could not pass event .*OutOfSight|Error occurred while .*OutOfSight/.test(line));
  check('no errors from the plugin in the server log', ours.length === 0, ours.slice(0, 5).join(' | '));
}

let failed = false;
try {
  await main();
} catch (e) {
  console.error(`[e2e] aborted: ${e.stack ?? e}`);
  failed = true;
}
console_('stop');
await sleep(5000);
if (!serverExited) server.kill('SIGKILL');

console.log('\n==================== results ====================');
for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : `  (${r.detail})`}`);
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} passed${failed ? ', run aborted' : ''}`);
process.exit(failed || passed !== results.length || results.length === 0 ? 1 : 0);
