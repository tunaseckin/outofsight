# Changelog

## 0.1.0-beta.2, unreleased

### Added
- `outofsight.bypass` exempts staff from the shield and from vehicle hiding.
  `/outofsight bypass` grants it without a permissions plugin and saves the
  list to `bypass.txt`.
- `shield.disabled-worlds` leaves chosen worlds alone.
- Decoy honeypot: breaking the exact block where a decoy was shown alerts
  holders of `outofsight.alerts` (default op) and the console, after
  `shield.honeypot.threshold` hits within `window-minutes`. Alerts only.
- Tab completion for `/outofsight`.

### Changed
- A container withheld from a chunk packet is checked on the next tick instead
  of waiting for the sweep, so chests appear almost at once after a join or a
  teleport.

### Fixed
- With test mode on, a player who had just run `/outofsight testme` received
  their first chunks unshielded after relogging, because the permission was read
  before it was granted.

## 0.1.0-beta.1, 2026-09-27

First public build. Checked on a real Paper 1.21.11 server by the end-to-end
suite in CI, but not yet run by other server owners.

### Shield
- Off by default, so installing the plugin changes nothing until you turn it on.
- `shield.test-mode` limits the shield to holders of `outofsight.shielded`, and
  `/outofsight testme` grants that permission without a permissions plugin, so an
  admin can verify on themselves before covering a live server.
- Removes containers a player cannot see from outgoing chunk packets, both the
  block-entity record and the block state.
- Default deny: a container is sent only once the main thread decides the player
  may see it, which needs distance and an unobstructed line of sight.
- Line of sight tries the centre of the block and then the faces turned towards
  the player. A ray that stops on the other half of a double chest or bed counts
  as reaching it, so both halves arrive together.
- Hiding on enclosure alone was tried first and protected almost nothing, because
  a chest anyone can open has air above it. The enclosure test only skips the ray
  for blocks sealed in stone.
- The ray runs once per container per player, and the discovery sweep skips a
  player who has not moved in a world that has not changed.
- A slower sweep re-reads nearby chunks to find containers no event reported, and
  drops ones that no longer exist.
- Works per world. The world floor is read for every packet (-64 in the
  Overworld, 0 in the Nether and the End) and the index keeps each dimension
  apart.
- `protected-blocks` accepts block tags such as `#beds`. Chests, copper chests,
  barrels, shulker boxes of every colour, beds, signs and banners are protected
  by default, along with furnaces, hoppers and other base furniture.
- Never touches a chunk column carrying biome data, since losing it would be worse
  than leaving the leak.

### Storage vehicles
- Chest minecarts, hopper minecarts and chest boats are hidden from players with
  no line of sight to them, using Paper's per-plugin `hideEntity` plus a packet
  listener that drops the first spawn packet. Ridden vehicles and players are
  never hidden. More types can be added in `shield.protected-entities`.

### Decoys (off by default)
- Plants deterministic fake buried chests to poison base-finding data.
- Quietly reverts to the real block as a player approaches, so only a cheat ever
  sees one. Chunks a player walks away from and comes back to are corrected again.
- Thinned to one chunk in four; sparse decoys poison just as well and cut
  re-serialised packets from 100% to 28%.

### Audit
- Compares outgoing chunk packets against the real world, per block type.
- Separates exposed leaks (unavoidable) from buried ones (genuine).
- Inspects both the block states and the block-entity list.

### Config advisor
- On startup and on `/outofsight advise`, reports when Paper anti-xray is off,
  when `hidden-blocks` lacks `ancient_debris`, `spawner`, `barrel`,
  `trapped_chest` and similar, and when feature seeds are not randomised.
  Reads only; never changes Paper's config.

### Reach check
- Ping-compensated, measured to the bounding box, with violation decay.
- Range read from `ENTITY_INTERACTION_RANGE` rather than hardcoded.

### Testing
- CI builds the jar, runs the unit tests and then `harness/e2e.mjs`, which
  starts Paper with PacketEvents and checks from a headless client what the
  shield sends and withholds, with a shield-off control run.

### Known findings
- `budding_amethyst`, `spawner`, `barrel` and `trapped_chest` are absent from
  Paper's default `hidden-blocks` list and leak fully until added.
