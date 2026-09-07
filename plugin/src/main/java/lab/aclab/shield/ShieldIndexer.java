package lab.aclab.shield;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dizini gunceller ve blok acildiginda sandigi ortaya cikarir.
 *
 * <p>Gizlemek tek basina yeterli degil: oyuncu duvari kirip sandiga ulastiginda
 * sunucu yalnizca kirilan blogun guncellemesini gonderir, sandik istemcide tas
 * olarak kalir ve oyuncu kendi sandigini goremez. Bu yuzden bir blok her
 * degistiginde komsulari yeniden degerlendirilir; artik gorunur olan konum
 * dizinden dusurulur ve yakindaki oyunculara gercek blok gonderilir.
 */
public final class ShieldIndexer implements Listener {


    private static final BlockFace[] SIX = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final Plugin plugin;
    private final HiddenIndex index;

    /**
     * Gizlenecek blok turleri.
     *
     * <p>Bir us sandiktan ibaret degil: firin, huni ve isaret feneri de en az
     * sandik kadar acik bir "burada biri yasiyor" isaretidir. Liste
     * yapilandirmadan gelir, cunku hangi bloklarin ele verici sayilacagi
     * sunucunun oyun tarzina gore degisir.
     */
    private final Set<org.bukkit.Material> protectedTypes;

    /** Ortaya cikan blogun gonderilecegi azami mesafe (blok). */
    private final double revealRange;

    /** Guvenlik agi taramasinin oyuncu cevresinde kapsadigi chunk yaricapi. */
    private final int sweepRadius;

    public ShieldIndexer(Plugin plugin, HiddenIndex index, double revealRange, int sweepRadius,
                         Set<org.bukkit.Material> protectedTypes) {
        this.plugin = plugin;
        this.index = index;
        this.protectedTypes = protectedTypes;
        this.revealRange = revealRange;
        this.sweepRadius = sweepRadius;
    }

    /** Eklenti acildiginda halihazirda yuklu chunk'lari tarar. */
    public void indexLoadedChunks() {
        int chunks = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                indexChunk(chunk);
                chunks++;
            }
        }
        plugin.getLogger().info("kalkan dizini: " + chunks + " chunk tarandi, "
                + index.size() + " gomulu block entity");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        indexChunk(event.getChunk());
        // Kenardaki bloklarin komsulari bu chunk'ta olabilir; komsulari da tazele.
        World world = event.getWorld();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (world.isChunkLoaded(cx + o[0], cz + o[1])) {
                indexChunk(world.getChunkAt(cx + o[0], cz + o[1]));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        index.clearChunk(event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        scheduleReevaluate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        scheduleReevaluate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::scheduleReevaluate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::scheduleReevaluate);
    }

    /**
     * Oyunculara yakin gizli kayitlari periyodik olarak yeniden degerlendirir.
     *
     * <p>Olaylar her degisikligi yakalayamaz: komutla, eklentiyle, WorldEdit ile,
     * pistonla ya da akan suyla acilan bir sandik hicbir {@code BlockBreakEvent}
     * uretmez. Yanlislikla gizli kalan bir sandik oyuncunun esyasini kaybetmesi
     * demek oldugu icin, olaya ek olarak bir guvenlik agi gerekir: kayit sayisi
     * kucuk oldugundan bu tarama ucuzdur.
     */
    private final java.util.concurrent.atomic.AtomicLong sweeps =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sweepNanos =
            new java.util.concurrent.atomic.AtomicLong();

    /** Ana is parcaciginda gecen sure - TPS'i etkileyen kisim budur. */
    public String stats() {
        long n = sweeps.get();
        return String.format(java.util.Locale.ROOT,
                "tarama(ana): %d tur, ortalama %.2f ms/tur, dizinde %d kayit",
                n, n == 0 ? 0.0 : sweepNanos.get() / 1_000_000.0 / n, index.size());
    }

    public void resetStats() {
        sweeps.set(0);
        sweepNanos.set(0);
    }

    public void sweep() {
        long started = System.nanoTime();
        try {
            doSweep();
        } finally {
            sweeps.incrementAndGet();
            sweepNanos.addAndGet(System.nanoTime() - started);
        }
    }

    private void doSweep() {
        var players = plugin.getServer().getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (var player : players) {
            World world = player.getWorld();
            int pcx = player.getLocation().getBlockX() >> 4;
            int pcz = player.getLocation().getBlockZ() >> 4;

            for (int dx = -sweepRadius; dx <= sweepRadius; dx++) {
                for (int dz = -sweepRadius; dz <= sweepRadius; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (!done.add(HiddenIndex.chunkKey(cx, cz)) || !world.isChunkLoaded(cx, cz)) {
                        continue;
                    }
                    indexChunk(world.getChunkAt(cx, cz));
                }
            }
        }
    }

    /** Degisiklik olay bittikten sonra gecerli olur, o yuzden bir tick bekleriz. */
    private void scheduleReevaluate(Block block) {
        Location loc = block.getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            reevaluate(loc.getBlock());
            for (BlockFace face : SIX) {
                reevaluate(loc.getBlock().getRelative(face));
            }
        });
    }

    /** Tek bir konumun gizli olmasi gerekip gerekmedigini yeniden karara baglar. */
    private void reevaluate(Block block) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        boolean shouldHide = protectedTypes.contains(block.getType()) && enclosed(block);

        if (shouldHide) {
            index.hide(x, y, z);
            return;
        }
        if (index.reveal(x, y, z)) {
            revealToPlayers(block);
        }
    }

    private void indexChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(false)) {
            Block block = state.getBlock();
            if (protectedTypes.contains(block.getType())) {
                reevaluate(block);
            }
        }
    }

    /**
     * Alti komsusu da isik gecirmiyorsa blok gomuludur.
     *
     * <p>Yuklu olmayan bir komsu chunk icin karar verilmez ve blok gizlenmez:
     * yanlislikla gizlemek oyuncunun kendi sandigini kaybetmesi demektir, o
     * chunk yuklendiginde zaten yeniden degerlendirilecek.
     */
    private boolean enclosed(Block block) {
        World world = block.getWorld();
        for (BlockFace face : SIX) {
            Block neighbour = block.getRelative(face);
            if (neighbour.getY() < world.getMinHeight() || neighbour.getY() >= world.getMaxHeight()) {
                continue;
            }
            if (!world.isChunkLoaded(neighbour.getX() >> 4, neighbour.getZ() >> 4)) {
                return false;
            }
            if (!neighbour.getType().isOccluding()) {
                return false;
            }
        }
        return true;
    }

    /** Artik gorunur olan blogu yakindaki oyunculara gercek haliyle gonderir. */
    private void revealToPlayers(Block block) {
        Location loc = block.getLocation();
        var data = block.getBlockData();
        BlockState state = block.getState();
        List<Player> players = block.getWorld().getPlayers();

        for (Player player : players) {
            if (player.getLocation().distance(loc) > revealRange) {
                continue;
            }
            player.sendBlockChange(loc, data);
            if (state instanceof TileState tileState) {
                player.sendBlockUpdate(loc, tileState);
            }
        }
        plugin.getLogger().info(String.format(Locale.ROOT,
                "kalkan: %d,%d,%d artik gorunur - gercek blok gonderildi",
                block.getX(), block.getY(), block.getZ()));
    }
}
