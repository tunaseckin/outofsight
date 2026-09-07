package lab.aclab.shield;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Removes containers a player cannot see from outgoing chunk packets.
 *
 * <p>This was the measured gap: Paper's anti-xray obfuscates ores but never
 * touches blocks that carry a block entity. A chest's NBT travels in a separate
 * list; obfuscating the block state while leaving that list would desync the
 * client, so Paper sends both verbatim. Base finding lives in exactly that gap.
 *
 * <p>Closing both channels together is required: dropping only the list is not
 * enough, because a {@code chest} block with no block entity still renders as a
 * chest, and changing only the block is not enough either, because a cheat reads
 * the raw list.
 *
 * <p>What counts as unseeable is decided in {@link HiddenIndex}: distance first,
 * enclosure second.
 */
public final class BlockEntityShield extends PacketListenerAbstract {

    private final Plugin plugin;
    private final int worldMinY;
    private final HiddenIndex index;
    private final DecoyService decoys;

    private final AtomicBoolean warnedBiome = new AtomicBoolean();

    /** Toggleable for A/B testing; off by default. */
    private volatile boolean enabled;

    // Measurement: the shield runs on network threads, so this time shows up as
    // latency rather than TPS. Main-thread cost is measured in the sweep.
    private final java.util.concurrent.atomic.AtomicLong packets =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong nanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong modified =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong planted =
            new java.util.concurrent.atomic.AtomicLong();

    public String stats() {
        long p = packets.get();
        long n = nanos.get();
        return String.format(java.util.Locale.ROOT,
                "shield(net): %d chunk packets, %d modified, %d decoys planted, "
                        + "%.1f us/packet average",
                p, modified.get(), planted.get(), p == 0 ? 0.0 : n / 1000.0 / p);
    }

    public void resetStats() {
        packets.set(0);
        nanos.set(0);
        modified.set(0);
        planted.set(0);
    }

    public BlockEntityShield(Plugin plugin, int worldMinY, HiddenIndex index,
                             DecoyService decoys) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.worldMinY = worldMinY;
        this.index = index;
        this.decoys = decoys;
    }

    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!enabled || event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) {
            return;
        }
        long started = System.nanoTime();
        packets.incrementAndGet();
        try {
            process(event);
        } finally {
            nanos.addAndGet(System.nanoTime() - started);
        }
    }

    private void process(PacketSendEvent event) {
        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        Column column = wrapper.getColumn();
        TileEntity[] tiles = column.getTileEntities();
        if (tiles == null) {
            tiles = new TileEntity[0];
        }
        boolean anythingToHide = index.hasChunk(column.getX(), column.getZ());
        // Returning early on an empty chunk missed most decoy sites: the vast
        // majority of chunks hold no block entity at all, and that is exactly
        // where decoys belong.
        boolean carriesDecoys = decoys.carriesDecoys(column.getX(), column.getZ());
        if (tiles.length == 0 && !anythingToHide && !carriesDecoys) {
            return;
        }

        // Rebuilding a column that carries biome data would lose it. Doing
        // nothing to such a packet beats sending a corrupt chunk.
        if (column.hasBiomeData()) {
            if (warnedBiome.compareAndSet(false, true)) {
                plugin.getLogger().warning(
                        "Column carries biome data - the shield leaves these packets alone.");
            }
            return;
        }

        BaseChunk[] sections = column.getChunks();
        List<TileEntity> keep = new ArrayList<>(tiles.length);
        List<int[]> toHide = new ArrayList<>();
        int baseX = column.getX() << 4;
        int baseZ = column.getZ() << 4;

        for (TileEntity tile : tiles) {
            int lx = tile.getX() & 0xF;
            int lz = tile.getZ() & 0xF;
            int y = tile.getY();

            // Learn the type BEFORE hiding: if every nearby chest is hidden,
            // learning only from ones we keep would never run.
            WrappedBlockState existing = stateAt(sections, lx, y, lz);
            if (existing != null && existing.getType() == StateTypes.CHEST) {
                decoys.learnChestType(tile.getType());
            }

            if (!shouldHide(event.getUser().getUUID(), baseX + lx, y, baseZ + lz)) {
                keep.add(tile);
                continue;
            }
            toHide.add(new int[]{lx, y, lz});
        }

        List<int[]> toPlant = planDecoys(sections, column.getX(), column.getZ());

        if (toHide.isEmpty() && toPlant.isEmpty()) {
            return;
        }

        for (int[] pos : toHide) {
            WrappedBlockState filler = fillerFor(sections, pos[0], pos[1], pos[2]);
            if (filler != null) {
                setState(sections, pos[0], pos[1], pos[2], filler);
            }
        }
        for (int[] pos : toPlant) {
            setState(sections, pos[0], pos[1], pos[2],
                    WrappedBlockState.getDefaultState(StateTypes.CHEST));
            keep.add(new TileEntity((byte) (((pos[0] & 0xF) << 4) | (pos[2] & 0xF)),
                    (short) pos[1], decoys.chestTypeId(), new NBTCompound()));
            planted.incrementAndGet();
        }

        // The column is rebuilt once, after everything else is settled.
        Column rebuilt = rebuild(column, keep.toArray(new TileEntity[0]));
        if (rebuilt == null) {
            return;
        }
        wrapper.setColumn(rebuilt);
        event.markForReEncode(true);
        modified.incrementAndGet();
    }

    /**
     * Whether this container should be left out of the packet for this player.
     *
     * <p>Default deny: a container goes out only once the main thread has decided
     * this player may see it. Judging that needs the world, to measure distance
     * and to trace a line of sight, and a network thread cannot read the world.
     * So the packet answers the one question it can answer alone.
     */
    private boolean shouldHide(java.util.UUID player, int x, int y, int z) {
        if (!index.isContainer(x, y, z)) {
            return false;
        }
        return player == null || !index.isDelivered(player, x, y, z);
    }

    /**
     * Picks valid positions for decoys.
     *
     * <p>A candidate is used only if it sits in a fully buried solid block: an
     * exposed decoy would be visible to legitimate players too, which breaks the
     * asymmetry the defence rests on.
     */
    private List<int[]> planDecoys(BaseChunk[] sections, int chunkX, int chunkZ) {
        if (!decoys.ready() || !decoys.carriesDecoys(chunkX, chunkZ)) {
            return List.of();
        }
        List<int[]> chosen = new ArrayList<>(decoys.perChunk());
        for (int[] candidate : decoys.candidates(chunkX, chunkZ, worldMinY, 60)) {
            if (chosen.size() >= decoys.perChunk()) {
                break;
            }
            if (buriedSolid(sections, candidate[0], candidate[1], candidate[2])) {
                chosen.add(candidate);
            }
        }
        return chosen;
    }

    /** Is the position and all six neighbours solid? Read from the packet itself. */
    private boolean buriedSolid(BaseChunk[] sections, int lx, int y, int lz) {
        int[][] offsets = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
                {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            WrappedBlockState state = stateAt(sections, lx + o[0], y + o[1], lz + o[2]);
            if (state == null) {
                return false;
            }
            var type = state.getType();
            if (type.isAir() || !type.isSolid() || !type.isBlocking()) {
                return false;
            }
        }
        return true;
    }

    /** Replacement block: the neighbour above, matching the surrounding stone. */
    private WrappedBlockState fillerFor(BaseChunk[] sections, int lx, int y, int lz) {
        WrappedBlockState above = stateAt(sections, lx, y + 1, lz);
        return above != null ? above : stateAt(sections, lx, y - 1, lz);
    }

    private WrappedBlockState stateAt(BaseChunk[] sections, int lx, int y, int lz) {
        int index = (y - worldMinY) >> 4;
        if (index < 0 || index >= sections.length || sections[index] == null) {
            return null;
        }
        return sections[index].get(lx, y & 0xF, lz);
    }

    private void setState(BaseChunk[] sections, int lx, int y, int lz, WrappedBlockState state) {
        int index = (y - worldMinY) >> 4;
        if (index >= 0 && index < sections.length && sections[index] != null) {
            sections[index].set(lx, y & 0xF, lz, state);
        }
    }

    /**
     * Rebuilds the column with the same data but a different block entity list.
     *
     * <p>The {@code tileEntities} field is {@code final} and cannot be replaced
     * in place. Which heightmap form a column carries varies by version; if
     * neither is recognised this returns {@code null} and the packet is left alone.
     */
    private Column rebuild(Column c, TileEntity[] tiles) {
        try {
            var heightmaps = c.getHeightmaps();
            if (heightmaps != null && !heightmaps.isEmpty()) {
                return new Column(c.getX(), c.getZ(), c.isFullChunk(), c.getChunks(), tiles,
                        heightmaps);
            }
        } catch (RuntimeException ignored) {
            // A different heightmap form on this version - fall through.
        }
        try {
            if (c.hasHeightMaps()) {
                return new Column(c.getX(), c.getZ(), c.isFullChunk(), c.getChunks(), tiles,
                        c.getHeightMaps());
            }
        } catch (RuntimeException ignored) {
            // Ignored.
        }
        return new Column(c.getX(), c.getZ(), c.isFullChunk(), c.getChunks(), tiles);
    }

}
