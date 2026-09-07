# Changelog

## 0.1.0, unreleased

First public build. Not yet tested by anyone other than the author.

### Shield
- Off by default, so installing the plugin changes nothing until you turn it on.
- `shield.test-mode` limits the shield to holders of `outofsight.shielded`, and
  `/outofsight testme` grants that permission without a permissions plugin, so an
  admin can verify on themselves before covering a live server.
- Removes containers a player cannot see from outgoing chunk packets, both the
  block-entity record and the block state.
- Default deny: a container is sent only once the main thread decides the player
  may see it, which needs distance and an unobstructed line of sight.
- Hiding on enclosure alone was tried first and protected almost nothing, because
  a chest anyone can open has air above it. The enclosure test now only skips the
  ray for blocks sealed in stone.
- The ray runs once per container per player, and a player who has not moved in a
  world that has not changed is skipped entirely.
- A slower sweep re-reads nearby chunks to find containers no event reported, and
  drops ones that no longer exist.
- Never touches a chunk column carrying biome data, since losing it would be worse
  than leaving the leak.

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
