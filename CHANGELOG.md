# Changelog

## 0.1.0 — unreleased

First public build. Not yet tested by anyone other than the author.

### Shield
- Removes fully buried chests, spawners, barrels, furnaces and similar blocks from
  outgoing chunk packets — both the block-entity record and the block state.
- Enclosure is decided from a main-thread index, so chunk borders are covered
  (roughly 23% of positions a packet-only decision would miss).
- Reveals a block as soon as it becomes visible, so players can still see and open
  their own containers.
- Periodic symmetric sweep as a safety net for changes that fire no event
  (commands, WorldEdit, pistons, flowing water).
- Never touches a chunk column carrying biome data — losing it would be worse than
  leaving the leak.

### Decoys (off by default)
- Plants deterministic fake buried chests to poison base-finding data.
- Quietly reverts to the real block as a player approaches, so only a cheat ever
  sees one.
- Thinned to one chunk in four; sparse decoys poison just as well and cut
  re-serialised packets from 100% to 28%.

### Audit
- Compares outgoing chunk packets against the real world, per block type.
- Separates exposed leaks (unavoidable) from buried ones (genuine).
- Inspects both the block states and the block-entity list.

### Reach check
- Ping-compensated, measured to the bounding box, with violation decay.
- Range read from `ENTITY_INTERACTION_RANGE` rather than hardcoded.

### Known findings
- `budding_amethyst`, `spawner`, `barrel` and `trapped_chest` are absent from
  Paper's default `hidden-blocks` list and leak fully until added.
