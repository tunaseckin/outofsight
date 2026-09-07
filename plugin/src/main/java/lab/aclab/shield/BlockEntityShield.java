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
 * Tamamen gomulu sandik ve spawner'larin konumunu giden chunk paketinden siler.
 *
 * <p>Olculen acik buydu: Paper'in anti-xray'i cevherleri obfuscate ederken block
 * entity'si olan bloklara hic dokunmaz. Sandigin NBT'si ayri bir listede gider;
 * blok state'i karartilip liste birakilsaydi istemci desenkron olurdu, bu yuzden
 * Paper ikisini de oldugu gibi gonderir. Us bulma tam olarak bu boslukta calisir.
 *
 * <p>Iki kanali birlikte kapatmak sart: sadece listeyi silmek yetmez, cunku
 * block entity'siz bir {@code chest} blogu istemcide yine sandik olarak cizilir.
 * Sadece blogu degistirmek de yetmez, cunku hile ham paketteki listeyi okur.
 *
 * <p>Gomululuk karari paketin <em>kendi</em> verisinden verilir - dunyaya
 * erisim gerekmez, dolayisiyla ag is parcaciginda guvenlidir.
 */
public final class BlockEntityShield extends PacketListenerAbstract {

    private final Plugin plugin;
    private final int worldMinY;
    private final HiddenIndex index;
    private final DecoyService decoys;
    private final AtomicBoolean warnedBiome = new AtomicBoolean();

    /** A/B testi icin acilip kapanabilir; varsayilan kapali. */
    private volatile boolean enabled;

    // Olcum: kalkan ag is parcaciklarinda calisir, yani bu sure TPS'e degil
    // ag gecikmesine yansir. Ana is parcacigi maliyeti taramada olculur.
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
                "kalkan(ag): %d chunk paketi, %d degistirildi, %d tuzak kondu, "
                        + "ortalama %.1f us/paket",
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
        // Bos bir chunk'tan cikmak, tuzak konacak yerlerin cogunu kaciriyordu:
        // chunk'larin buyuk cogunlugunda hic block entity yoktur ve tuzak asil
        // oralara konmalidir.
        boolean carriesDecoys = decoys.carriesDecoys(column.getX(), column.getZ());
        if (tiles.length == 0 && !anythingToHide && !carriesDecoys) {
            return;
        }

        // Biome verisi tasiyan kolonu yeniden insa edersek onu kaybederiz.
        // Boyle bir pakette hicbir sey yapmamak, bozuk chunk gondermekten iyidir.
        if (column.hasBiomeData()) {
            if (warnedBiome.compareAndSet(false, true)) {
                plugin.getLogger().warning(
                        "Kolon biome verisi tasiyor - kalkan bu paketlere dokunmuyor.");
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

            // Tipi gizlemeden ONCE ogren: yakindaki sandiklarin hepsi gizliyse
            // "gizlemedigimizden ogren" mantigi hicbir zaman calismaz.
            WrappedBlockState existing = stateAt(sections, lx, y, lz);
            if (existing != null && existing.getType() == StateTypes.CHEST) {
                decoys.learnChestType(tile.getType());
            }

            if (!index.isHidden(baseX + lx, y, baseZ + lz)) {
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

        // Kolon bir kez, her sey hazir olduktan sonra kurulur.
        Column rebuilt = rebuild(column, keep.toArray(new TileEntity[0]));
        if (rebuilt == null) {
            return;
        }
        wrapper.setColumn(rebuilt);
        event.markForReEncode(true);
        modified.incrementAndGet();
    }

    /**
     * Tuzaklarin konacagi gecerli konumlari secer.
     *
     * <p>Aday ancak tamamen gomulu, kati bir blokta ise kullanilir: acikta duran
     * bir tuzak mesru oyuncuya da gorunur, ki bu savunmanin dayandigi asimetriyi
     * bozar.
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

    /** Konum ve alti komsusu da kati mi? Paketin kendi verisinden bakilir. */
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

    /** Yerine konacak blok: ustundeki komsu. Cevresiyle ayni tasi/derin tasi verir. */
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
     * Kolonu ayni verilerle, farkli bir block entity listesiyle yeniden kurar.
     *
     * <p>{@code tileEntities} alani {@code final} oldugu icin yerinde
     * degistirilemez. Hangi heightmap bicimi tasindigi surume gore degisir;
     * ikisini de taniyamazsak {@code null} donup pakete dokunmuyoruz.
     */
    private Column rebuild(Column c, TileEntity[] tiles) {
        try {
            var heightmaps = c.getHeightmaps();
            if (heightmaps != null && !heightmaps.isEmpty()) {
                return new Column(c.getX(), c.getZ(), c.isFullChunk(), c.getChunks(), tiles,
                        heightmaps);
            }
        } catch (RuntimeException ignored) {
            // Bu surumde harita bicimi farkli - asagidaki yola dus.
        }
        try {
            if (c.hasHeightMaps()) {
                return new Column(c.getX(), c.getZ(), c.isFullChunk(), c.getChunks(), tiles,
                        c.getHeightMaps());
            }
        } catch (RuntimeException ignored) {
            // Yok sayilir.
        }
        return new Column(c.getX(), c.getZ(), c.isFullChunk(), c.getChunks(), tiles);
    }

}
