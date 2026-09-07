package lab.aclab.xray;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sunucudan giden chunk paketlerini, dunyanin gercek icerigiyle karsilastirir.
 *
 * <p>Bu bir <em>tespit</em> degil, bir <em>denetim</em>. X-ray ve Block ESP gibi
 * bilgi hileleri oyuncuya fizik kurali cignetmez: sunucu cevherin yerini zaten
 * gondermistir, hile onu ekrana cizer. Paket akisinda anormal hicbir sey yoktur,
 * dolayisiyla bu hileler tespit edilemez. Tek gercek savunma veriyi hic
 * gondermemektir - ve bu sinif gonderilip gonderilmedigini olcer.
 *
 * <p>Paket iki ayri kanal tasir ve <em>ikisi de</em> denetlenmelidir:
 * <ul>
 *   <li>Blok state'leri - anti-xray'in obfuscate ettigi yer.</li>
 *   <li>Block entity listesi - sandik ve spawner gibi bloklar konumlarini
 *       burada <em>ayrica</em> bildirir. Blok verisi gizlense bile bu liste
 *       acikta kalabilir, o yuzden ayri sayilir.</li>
 * </ul>
 */
public final class XrayAudit extends PacketListenerAbstract {

    /** Us bulmanin ve X-ray'in hedefledigi bloklar. */
    private static final Set<String> SENSITIVE = Set.of(
            "diamond_ore", "deepslate_diamond_ore",
            "emerald_ore", "deepslate_emerald_ore",
            "gold_ore", "deepslate_gold_ore",
            "iron_ore", "deepslate_iron_ore",
            "lapis_ore", "deepslate_lapis_ore",
            "redstone_ore", "deepslate_redstone_ore",
            "ancient_debris",
            "chest", "trapped_chest", "ender_chest", "barrel",
            "spawner", "budding_amethyst");

    /** Bunlarin konumu block entity listesinde de gecer. */
    private static final Set<String> BLOCK_ENTITIES = Set.of(
            "chest", "trapped_chest", "ender_chest", "barrel", "spawner");

    /** Anti-xray'in ust sinirina denk gelir; ustunu taramanin anlami yok. */
    private final int maxScanY;

    private final Plugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /**
     * Oyuncuya degil <em>konuma</em> bagli gozcu.
     *
     * <p>Kime gonderilirse gonderilsin ayni chunk denetlenir, ve tek seferlik
     * degildir. Boylece dogrulama, bir insanin cik-gir yapmasini beklemeden
     * bassiz bir test istemcisiyle tekrarlanabilir.
     */
    private volatile Watch globalWatch;

    public XrayAudit(Plugin plugin, int maxScanY) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
        this.maxScanY = maxScanY;
    }

    public void start(Player player, int chunkBudget) {
        sessions.put(player.getUniqueId(),
                new Session(chunkBudget, player.getWorld().getMinHeight()));
    }

    public void stop(Player player) {
        sessions.remove(player.getUniqueId());
    }

    /**
     * Tek bir konumu gozler ve o chunk gonderildiginde raporlar.
     *
     * <p>Ornekleme tabanli denetim sirali bir yaris iceriyor: chunk yagmuru
     * denetim acilmadan once biterse hedef chunk ornege hic girmez. Gozcu bunu
     * ortadan kaldirir - hangi sirada gelirse gelsin dogru chunk yakalanir.
     * Cikis yapinca silinmez, cunku testin geregi cik-gir yapmaktir.
     */
    public void watch(org.bukkit.World world, java.util.List<WatchPos> positions) {
        WatchPos first = positions.get(0);
        globalWatch = new Watch(first.x() >> 4, first.z() >> 4, world.getMinHeight(), positions);
    }

    public void clearWatch() {
        globalWatch = null;
    }

    /** Gozlenen tek konum: beklenen blok adi ve rapor etiketi. */
    public record WatchPos(int x, int y, int z, String expected, String label) {
    }

    private record Watch(int chunkX, int chunkZ, int minY, java.util.List<WatchPos> positions) {
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) {
            return;
        }
        UUID id = event.getUser().getUUID();
        if (id == null) {
            return;
        }
        Session session = sessions.get(id);
        Watch watch = globalWatch;
        boolean wantSession = session != null && session.claimChunk();
        if (!wantSession && watch == null) {
            return;
        }

        // Paketi ag is parcaciginda cozmek zorundayiz - tampon yalnizca burada
        // gecerli. Cozulmus veri sonrasinda guvenle tasinabilir.
        Column column = new WrapperPlayServerChunkData(event).getColumn();
        int chunkX = column.getX();
        int chunkZ = column.getZ();

        if (watch != null && chunkX == watch.chunkX() && chunkZ == watch.chunkZ()) {
            reportWatch(id, watch, column);
        }
        if (!wantSession) {
            return;
        }

        Map<Long, String> packetBlocks = scanPacket(column, session.minY);
        Set<Long> packetTileEntities = scanTileEntities(column);

        plugin.getServer().getScheduler().runTask(plugin, () -> compareAgainstWorld(
                id, chunkX, chunkZ, packetBlocks, packetTileEntities, session));
    }

    /** Pakette gorunen hassas bloklarin konumlarini ve turlerini toplar. */
    private Map<Long, String> scanPacket(Column column, int minY) {
        Map<Long, String> found = new HashMap<>();
        BaseChunk[] sections = column.getChunks();

        for (int i = 0; i < sections.length; i++) {
            BaseChunk section = sections[i];
            if (section == null || section.isEmpty()) {
                continue;
            }
            int baseY = minY + i * 16;
            if (baseY > maxScanY) {
                break;
            }
            for (int y = 0; y < 16; y++) {
                int worldY = baseY + y;
                if (worldY > maxScanY) {
                    break;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        WrappedBlockState state = section.get(x, y, z);
                        if (state == null) {
                            continue;
                        }
                        String name = stripNamespace(state.getType().getName());
                        if (SENSITIVE.contains(name)) {
                            found.put(key(x, worldY, z), name);
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * Paketin block entity listesindeki konumlar.
     *
     * <p>Bu liste blok obfuscation'indan bagimsizdir: sandigin blogu taşa
     * cevrilse bile, burada bir kayit kalirsa konum yine ele verilmis olur.
     */
    private Set<Long> scanTileEntities(Column column) {
        Set<Long> positions = new HashSet<>();
        TileEntity[] tiles = column.getTileEntities();
        if (tiles == null) {
            return positions;
        }
        for (TileEntity tile : tiles) {
            if (tile != null) {
                positions.add(key(tile.getX() & 0xF, tile.getY(), tile.getZ() & 0xF));
            }
        }
        return positions;
    }

    private void compareAgainstWorld(UUID playerId, int chunkX, int chunkZ,
                                     Map<Long, String> packetBlocks,
                                     Set<Long> packetTileEntities, Session session) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        ChunkSnapshot snap = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false);

        int minY = world.getMinHeight();
        int topY = Math.min(maxScanY, world.getMaxHeight() - 1);

        int real = 0;
        int leaked = 0;
        int leakedExposed = 0;
        int leakedBuried = 0;
        int wrongType = 0;
        int tileLeak = 0;
        int tileReal = 0;
        int tileExposed = 0;
        int tileBuried = 0;
        Set<Long> realPositions = new HashSet<>();
        Map<String, Integer> buriedTypes = new HashMap<>();

        for (int y = minY; y <= topY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material m = snap.getBlockType(x, y, z);
                    String name = m.name().toLowerCase(Locale.ROOT);
                    if (!SENSITIVE.contains(name)) {
                        continue;
                    }
                    real++;
                    long k = key(x, y, z);
                    realPositions.add(k);

                    // Block entity kanali: blok gizlense bile konum burada sizabilir.
                    // Tamamen gomulu bir sandik, oyuncunun gizledigi us demektir -
                    // us bulmanin calisip calismadigi tam olarak bu sayida gorulur.
                    if (BLOCK_ENTITIES.contains(name)) {
                        tileReal++;
                        if (packetTileEntities.contains(k)) {
                            tileLeak++;
                            if (isExposed(world, (chunkX << 4) + x, y, (chunkZ << 4) + z)) {
                                tileExposed++;
                            } else {
                                tileBuried++;
                            }
                        }
                    }

                    String packetName = packetBlocks.get(k);
                    if (packetName == null) {
                        continue; // Pakette hassas blok yok - gizlenmis.
                    }
                    if (!packetName.equals(name)) {
                        // Yerinde baska bir cevher gorunuyor: obfuscation calismis.
                        wrongType++;
                        continue;
                    }
                    leaked++;
                    // Acikta duran blogu gizlemek dunyayi bozardi: oyuncu onu zaten
                    // gozuyle gorur. Asil sorun, tamamen gomulu oldugu halde sizan blok.
                    if (isExposed(world, (chunkX << 4) + x, y, (chunkZ << 4) + z)) {
                        leakedExposed++;
                    } else {
                        leakedBuried++;
                        buriedTypes.merge(name, 1, Integer::sum);
                    }
                }
            }
        }

        // Sahte: pakette hassas blok var ama dunyada yok - anti-xray'in gurultusu.
        int fake = 0;
        for (long pos : packetBlocks.keySet()) {
            if (!realPositions.contains(pos)) {
                fake++;
            }
        }

        session.record(real, leaked, leakedExposed, leakedBuried, wrongType, fake, tileLeak,
                tileReal, tileExposed, tileBuried, buriedTypes);
        if (session.isFinished()) {
            sessions.remove(playerId);
            session.report(player);
            plugin.getLogger().info(session.summary(player.getName()));
        }
    }

    /** Gozlenen konumlarin iki kanalda da sizip sizmadigini bildirir. */
    private void reportWatch(UUID playerId, Watch watch, Column column) {
        BaseChunk[] sections = column.getChunks();
        Set<Long> tiles = scanTileEntities(column);
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<String> plain = new java.util.ArrayList<>();

        for (WatchPos pos : watch.positions()) {
            int localX = pos.x() & 0xF;
            int localZ = pos.z() & 0xF;
            String seen = "(bolum yok)";
            int index = (pos.y() - watch.minY()) >> 4;
            if (index >= 0 && index < sections.length && sections[index] != null) {
                WrappedBlockState state = sections[index].get(localX, pos.y() & 0xF, localZ);
                if (state != null) {
                    seen = stripNamespace(state.getType().getName());
                }
            }
            boolean blockLeak = pos.expected().equals(seen);
            boolean tileLeak = tiles.contains(key(localX, pos.y(), localZ));

            plain.add(String.format(Locale.ROOT, "%s [%d,%d,%d] blok=%s(%s) block-entity=%s",
                    pos.label(), pos.x(), pos.y(), pos.z(),
                    blockLeak ? "SIZDI" : "gizlendi", seen,
                    tileLeak ? "SIZDI" : "yok"));
            lines.add("§7" + pos.label() + ": blok "
                    + (blockLeak ? "§cSIZDI" : "§agizlendi")
                    + " §7(pakette: §f" + seen + "§7)"
                    + (tileLeak ? "  §7block-entity §cSIZDI" : ""));
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(playerId);
            String who = player != null ? player.getName() : playerId.toString().substring(0, 8);
            plugin.getLogger().info("gizli us testi [" + who + "] | " + String.join(" | ", plain));
            if (player == null) {
                return;
            }
            player.sendMessage("§8§m                                        ");
            player.sendMessage("§b§lGizli us testi §7(ayni kabuk, iki blok turu)");
            lines.forEach(player::sendMessage);
            player.sendMessage("§8§m                                        ");
        });
    }

    /** Blogun alti komsusundan biri gorusu kesmiyorsa blok aciktadir. */
    private static boolean isExposed(World world, int x, int y, int z) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            int ny = y + o[1];
            if (ny < minY || ny > maxY) {
                continue;
            }
            if (!world.getBlockAt(x + o[0], ny, z + o[2]).getType().isOccluding()) {
                return true;
            }
        }
        return false;
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        String bare = colon >= 0 ? name.substring(colon + 1) : name;
        return bare.toLowerCase(Locale.ROOT);
    }

    /** Chunk-yerel (x,z) 0..15, y ise dunya koordinati. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0xF) << 40) | ((long) (z & 0xF) << 36) | ((y + 2048) & 0xFFFFFFFFL);
    }

    private static final class Session {
        private final int minY;
        private int remaining;
        private int chunks;
        private int realTotal;
        private int leakedTotal;
        private int exposedTotal;
        private int buriedTotal;
        private int wrongTypeTotal;
        private int fakeTotal;
        private int tileLeakTotal;
        private int tileRealTotal;
        private int tileExposedTotal;
        private int tileBuriedTotal;
        private final Map<String, Integer> buriedByType = new HashMap<>();

        Session(int budget, int minY) {
            this.remaining = budget;
            this.minY = minY;
        }

        synchronized boolean claimChunk() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        synchronized void record(int real, int leaked, int exposed, int buried, int wrong,
                                 int fake, int tileLeak, int tileReal, int tileExposed,
                                 int tileBuried, Map<String, Integer> buriedTypes) {
            tileRealTotal += tileReal;
            tileExposedTotal += tileExposed;
            tileBuriedTotal += tileBuried;
            chunks++;
            realTotal += real;
            leakedTotal += leaked;
            exposedTotal += exposed;
            buriedTotal += buried;
            wrongTypeTotal += wrong;
            fakeTotal += fake;
            tileLeakTotal += tileLeak;
            buriedTypes.forEach((k, v) -> buriedByType.merge(k, v, Integer::sum));
        }

        synchronized boolean isFinished() {
            return remaining <= 0;
        }

        private String topBuriedTypes() {
            return buriedByType.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(8)
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("yok");
        }

        synchronized String summary(String playerName) {
            String verdict;
            if (realTotal == 0) {
                verdict = "hassas blok yok";
            } else if (leakedTotal == 0) {
                verdict = "TUR-ESLESEN SIZINTI YOK";
            } else {
                verdict = String.format(Locale.ROOT, "sizinti %.1f%%",
                        100.0 * leakedTotal / realTotal);
            }
            int seen = fakeTotal + wrongTypeTotal + leakedTotal;
            double signal = seen == 0 ? 0 : 100.0 * leakedTotal / seen;
            return String.format(Locale.ROOT,
                    "xray denetimi [%s] %d chunk: gercek=%d TUR-ESLESEN=%d (acikta=%d gomulu=%d) "
                            + "yanlis-tur=%d sahte=%d | block-entity: gercek=%d sizan=%d "
                            + "(acikta=%d GOMULU=%d) -> %s | sinyal/gurultu=%.2f%% | "
                            + "gomulu dagilimi: %s",
                    playerName, chunks, realTotal, leakedTotal, exposedTotal, buriedTotal,
                    wrongTypeTotal, fakeTotal, tileRealTotal, tileLeakTotal, tileExposedTotal,
                    tileBuriedTotal, verdict, signal, topBuriedTypes());
        }

        synchronized void report(Player player) {
            player.sendMessage("§8§m                                        ");
            player.sendMessage("§b§lX-ray sizinti denetimi §7(" + chunks + " chunk)");
            player.sendMessage("§7Dunyadaki gercek hassas blok: §f" + realTotal);
            player.sendMessage("§7Tur eslesen sizinti:          "
                    + (leakedTotal == 0 ? "§a0" : "§c" + leakedTotal));
            player.sendMessage("§7  · acikta (kacinilmaz):      §f" + exposedTotal);
            player.sendMessage("§7  · gomulu:                   §f" + buriedTotal);
            player.sendMessage("§7Yanlis tur (obfuscate):       §f" + wrongTypeTotal);
            player.sendMessage("§7Sahte (gurultu):              §f" + fakeTotal);
            player.sendMessage("§7Block entity (sandik/spawner): §f" + tileRealTotal
                    + " §7adet, sizan: §f" + tileLeakTotal);
            player.sendMessage("§7  · acikta (kacinilmaz):      §f" + tileExposedTotal);
            player.sendMessage("§7  · GOMULU (gizli us):        "
                    + (tileBuriedTotal == 0 ? "§a0" : "§c" + tileBuriedTotal));

            int seen = fakeTotal + wrongTypeTotal + leakedTotal;
            if (seen > 0) {
                player.sendMessage(String.format(Locale.ROOT,
                        "§7X-ray'in gordugu cevherin §f%.2f%%§7'i gercek",
                        100.0 * leakedTotal / seen));
            }
            player.sendMessage("§8§m                                        ");
        }
    }
}
