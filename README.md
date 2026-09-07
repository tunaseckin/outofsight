# aclab

A Paper plugin that stops base finding, the cheat capability Minecraft's own
anti-xray does not cover.

[Türkçe README](README.tr.md)

## The problem

Paper ships anti-xray, and for ores it works. Measured on a test server with
`engine-mode: 2`, an X-ray user sees roughly 196,000 "ores" of which 215 are real,
about 1 in 900.

Chests are a different story. A chest goes into the chunk packet twice, once as a
block state and once as an entry in a separate block-entity list. Anti-xray works
on block states, so that list goes out untouched with the coordinates in it.

The same experiment, a chest and a diamond ore sealed in one stone shell, both in
the default `hidden-blocks` list, run once per engine mode:

```
engine-mode 1
  chest        block=hidden(stone)                block-entity=LEAKED
  diamond ore  block=hidden(stone)                block-entity=none

engine-mode 2
  chest        block=LEAKED(chest)                block-entity=LEAKED
  diamond ore  block=hidden(deepslate_copper_ore) block-entity=none
```

The ore is hidden either way. The chest gives its position away either way. Mode 1
at least replaces the block with stone; mode 2, the stronger mode for ores, does
not even do that.

Block ESP and chunk finders work in that gap.

| Target | engine-mode 1 | engine-mode 2 |
|---|---|---|
| Ores | hidden | hidden, 1 real in ~900 |
| Chests, spawners, barrels | block hidden, position still leaks | both leak |

## What this plugin does

### Shield

Removes fully buried chests, spawners, furnaces and similar blocks from outgoing
chunk packets. It drops the block-entity record and replaces the block state with a
neighbouring block. Both steps are needed, since dropping only the record still
renders a chest, and changing only the block leaves the position readable in the
raw list.

Enclosure is decided from a main-thread index rather than from the packet, for two
reasons. A packet carries a single chunk column, so a block on a chunk border has
neighbours you cannot see, about 23% of positions. And a decision made from the
packet is only valid at send time, which leaves nobody to announce that the chest
became visible once a player breaks the wall.

Revealing matters as much as hiding. When a player digs through to a chest the
server only sends an update for the broken block, so the chest would stay stone on
the client and the player could not see their own container. Every block change
therefore re-evaluates its neighbours, and a position that became visible is
dropped from the index and sent to nearby players as the real block.

Events cannot catch every change, since commands, WorldEdit, pistons and flowing
water produce no `BlockBreakEvent`. A periodic sweep runs alongside them and works
in both directions: it hides newly buried blocks and reveals newly exposed ones. A
chest left hidden by mistake means a player loses their items, which is what the
sweep exists to prevent.

### Decoys (optional, off by default)

The server plants fake buried chests, so someone hunting bases digs sixty blocks
and finds nothing. After a few of those the cheat's data stops being worth acting
on.

A decoy is only ever visible to a cheat, because legitimate play is local and
cheating is global. A block sealed in stone has no line of sight to a normal
player, while a cheat reads every loaded chunk at once. The only risk is a player
digging into one by chance, so decoys quietly revert to the real block as a player
approaches. Chunk packets are sent from 100+ blocks away and correction runs within
3 chunks, which leaves no window to reach one.

A cheater who notices that chests vanish on approach ends up trusting only what is
close, which is what a legitimate player sees anyway.

Positions are deterministic. Random ones would move every time a chunk is resent,
which flickers and signals that the server is fabricating data.

Decoys go in one chunk in four rather than every chunk. Sparse decoys poison the
data just as well, and most of the cost is re-serialising every packet you touch.
Thinning drops that from 100% of packets to 28%.

### Audit

`XrayAudit` compares outgoing chunk packets against the real world and reports what
leaks, per block type. It separates exposed leaks, which a player can see anyway,
from buried ones, which are the genuine problem. Both channels are inspected: block
states and the block-entity list.

Type comparison is essential: `engine-mode: 2` replaces each buried ore with a
*random different* ore, so asking only "is there an ore here" reports a false 58%
leak rate. This project made that exact mistake before fixing it.

### Reach check

A ping-compensated hit distance check, included as a worked example.
[Grim](https://grim.ac/) reimplements vanilla movement tick by tick and nothing
here approaches that. Three deliberate choices, all about not flagging legitimate
players:

1. Distance is measured to the target's bounding box, not its centre.
2. Attacker and victim are both rewound by the attacker's latency, and the closest
   moment in that window is used, so doubt favours the player.
3. A single violation carries no penalty. Violations accumulate and decay.

Range is read from the player's `ENTITY_INTERACTION_RANGE` attribute rather than
hardcoded. That showed up immediately in testing: creative allows 5.03, survival
3.03. A hardcoded 3.0 would flag every creative hit.

## Requirements

- Paper 1.21.11
- [PacketEvents](https://github.com/retrooper/packetevents) 2.13.0+

## Commands

All require `aclab.admin` (op by default).

| Command | Purpose |
|---|---|
| `/aclab xray [chunks]` | Audit outgoing chunk packets for leaks |
| `/aclab hidechest` | Place a buried chest + control ore, then relog to test |
| `/aclab shield` | Toggle the shield |
| `/aclab reachdebug` | Log the measured distance of every hit |
| `/aclab reachsim <d>` | Show where the reach threshold sits |
| `/aclab stress <n>` | Build a dense field of buried chests for load testing |
| `/aclab perf` / `perfreset` | Cost measurements |

## Performance

Measured with bots that teleport constantly to force chunk loading, a far heavier
load than normal play, at roughly 400 to 700 chunk packets per second.

| Scenario | Packets | Modified | Network (µs/pkt) | Main thread | MSPT / TPS |
|---|---|---|---|---|---|
| 20 bots, empty world | 37 992 | 94 | 11.9 | 1.24 ms | 19.90 / 20.00 |
| 20 bots, 256 buried chests | 25 838 | 866 | 14.9 | 0.84 ms | 8.94 / 20.00 |
| 40 bots, 256 buried chests | 41 287 | 1 466 | 13.5 | 0.79 ms | 10.76 / 20.00 |
| 20 bots, decoys on (1 in 4) | 22 325 | 6 209 | 18.4 | 0.68 ms | 9.11 / 20.00 |

The shield runs on network threads, so it costs latency rather than TPS. At 40
bots that is roughly 0.9% of one core. The only main-thread work is the sweep, at
0.8 ms every two seconds, or 0.04% of a 50 ms tick budget. TPS stayed at 20.00 in
every run, with no exceptions.

## Honest limits

- Re-serialisation cost is not measured. The counters cover only this plugin's own
  code, and rewriting the packet happens inside PacketEvents after listeners run.
  Enabling decoys touches more packets and raises it. TPS was unaffected because
  the work is on network threads, but there is no measured figure for it.
- Tested up to 40 bots on one world. A 100+ player server has not been measured.
- Chest-type registry ids are learned from live packets. If that fails, set
  `shield.decoy-block-entity-type` manually.
- The reach check does not replace a real anticheat. No movement simulation, so no
  fly, speed or noslow detection.
- `engine-mode: 2` costs CPU on its own. Measure your tick time before enabling it.

## Verification harness

`harness/` holds headless clients that verify behaviour from the client's point of
view. That is the only view that works here, because the server-side audit reads
the raw buffer and never sees the shield's changes.

```
node relog.mjs <name> <ms> ["command"]   # connect, run a command, leave
node inspect.mjs <x> <y> <z>             # what the client receives at a position
node dig.mjs <x> <y> <z>                 # break the wall, watch for the reveal
node decoytest.mjs                       # decoy distribution, near vs far
node loadtest.mjs <bots> <secs>          # load generation
```

## Building

```
cd plugin && gradle build
```

Unit tests cover the reach math, weighted towards false-positive cases:

```
cd plugin && gradle test
```

## Credits

Testers who reported a problem, or shared what their server actually leaked, are
named here. If you filed something that changed the code, open a pull request
adding yourself, or say so on the issue and it will be added.

(nobody yet)

## License

GPL-3.0. PacketEvents is GPL-3.0 and this plugin links against it, so the combined
work is GPL-3.0 and its source must remain available to anyone who receives it.
