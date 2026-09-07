package lab.aclab;

import com.github.retrooper.packetevents.PacketEvents;
import lab.aclab.reach.ReachCheck;
import lab.aclab.shield.BlockEntityShield;
import lab.aclab.shield.DecoyCorrector;
import lab.aclab.shield.DecoyService;
import lab.aclab.shield.HiddenIndex;
import lab.aclab.shield.ShieldIndexer;
import lab.aclab.xray.XrayAudit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Anticheat laboratuvari.
 *
 * <p>Iki modul, iki farkli savunma turunu temsil ediyor:
 * <ul>
 *   <li>{@link XrayAudit} - bilgi hilelerine karsi. Tespit edilemezler, o yuzden
 *       veri hic gonderilmez; bu modul gonderilmedigini <em>ispatlar</em>.</li>
 *   <li>{@link ReachCheck} - eylem hilelerine karsi. Fizik cignendigi icin
 *       tespit edilebilirler; bu modul ping telafisiyle dogrular.</li>
 * </ul>
 */
public final class AclabPlugin extends JavaPlugin implements Listener {

    /** Anti-xray'in {@code max-block-height} degeriyle ayni olmali. */
    private static final int MAX_SCAN_Y = 128;

    private XrayAudit xrayAudit;
    private ReachCheck reachCheck;
    private BlockEntityShield shield;
    private HiddenIndex hiddenIndex;
    private ShieldIndexer indexer;
    private DecoyCorrector corrector;

    @Override
    public void onEnable() {
        xrayAudit = new XrayAudit(this, MAX_SCAN_Y);
        reachCheck = new ReachCheck(this);

        saveDefaultConfig();
        hiddenIndex = new HiddenIndex();
        DecoyService decoys = new DecoyService(
                getConfig().getInt("shield.decoys-per-chunk", 0),
                getServer().getWorlds().get(0).getSeed(),
                getConfig().getInt("shield.decoy-block-entity-type", -1),
                getConfig().getInt("shield.decoy-chunk-interval", 4));
        shield = new BlockEntityShield(this,
                getServer().getWorlds().get(0).getMinHeight(), hiddenIndex, decoys);

        corrector = new DecoyCorrector(this, decoys,
                getConfig().getInt("shield.decoy-correction-radius-chunks", 3));
        getServer().getScheduler().runTaskTimer(this, corrector::run, 20L, 20L);
        if (decoys.enabled()) {
            getLogger().info("tuzak acik: chunk basina " + decoys.perChunk());
        }

        indexer = new ShieldIndexer(this, hiddenIndex,
                getConfig().getDouble("shield.reveal-range", 128.0),
                getConfig().getInt("shield.sweep-radius-chunks", 4),
                readProtectedTypes());
        getServer().getPluginManager().registerEvents(indexer, this);
        getServer().getScheduler().runTask(this, indexer::indexLoadedChunks);

        // Olaylarin kacirdigi degisiklikler icin guvenlik agi.
        long sweepTicks = Math.max(20L, getConfig().getLong("shield.sweep-interval-ticks", 40L));
        getServer().getScheduler().runTaskTimer(this, indexer::sweep, sweepTicks, sweepTicks);

        if (getConfig().getBoolean("shield.enabled", true)) {
            shield.toggle();
            getLogger().info("kalkan acik (config: shield.enabled)");
        }
        PacketEvents.getAPI().getEventManager().registerListener(shield);
        PacketEvents.getAPI().getEventManager().registerListener(xrayAudit);
        PacketEvents.getAPI().getEventManager().registerListener(reachCheck);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, reachCheck::tick, 1L, 1L);

        getLogger().info("aclab etkin - /aclab xray, /aclab reachsim");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reachCheck.forget(event.getPlayer());
        corrector.forget(event.getPlayer());
        xrayAudit.stop(event.getPlayer());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut oyun icinden calistirilmali.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§7/aclab xray [chunk] §8- giden chunk paketlerini denetle");
            player.sendMessage("§7/aclab reachsim <mesafe> §8- reach dogrulamasini sentetik girdiyle sina");
            player.sendMessage("§7/aclab reachdebug §8- her vurusun olculen mesafesini yaz");
            player.sendMessage("§7/aclab hidechest §8- tasa gomulu bir sandik koy (us bulma testi)");
            player.sendMessage("§7/aclab shield §8- gomulu block entity kalkanini ac/kapat");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "xray" -> {
                int budget = args.length > 1 ? parseInt(args[1], 16) : 16;
                xrayAudit.start(player, budget);
                player.sendMessage("§bDenetim acildi §7- sonraki §f" + budget
                        + " §7chunk paketi incelenecek. Yeni chunk yuklemek icin yuru"
                        + " (ya da yer altina in, orada hassas blok bulunur).");
            }
            case "hidechest" -> hideChest(player);
            case "stress" -> {
                int count = args.length > 1 ? parseInt(args[1], 200) : 200;
                stressChests(player, count);
            }
            case "perf" -> {
                String shieldStats = shield.stats();
                String sweepStats = indexer.stats();
                double mspt = getServer().getAverageTickTime();
                double tps = getServer().getTPS()[0];
                for (String line : new String[]{shieldStats, sweepStats,
                        String.format(java.util.Locale.ROOT,
                                "sunucu: %.2f ms/tick, %.2f TPS, %d oyuncu",
                                mspt, tps, getServer().getOnlinePlayers().size())}) {
                    player.sendMessage("§7" + line);
                    getLogger().info("[perf] " + line);
                }
            }
            case "perfreset" -> {
                shield.resetStats();
                indexer.resetStats();
                player.sendMessage("§7Olcumler sifirlandi.");
                getLogger().info("[perf] sifirlandi");
            }
            case "shield" -> {
                boolean on = shield.toggle();
                player.sendMessage(on
                        ? "§aKalkan ACIK §7- gomulu sandik/spawner paketten siliniyor."
                        : "§7Kalkan kapatildi.");
                getLogger().info("kalkan " + (on ? "ACIK" : "kapali"));
            }
            case "reachdebug" -> {
                boolean on = reachCheck.toggleDebug(player);
                player.sendMessage(on
                        ? "§aReach tani modu ACIK §7- her vurus konsola ve buraya yazilacak."
                        : "§7Reach tani modu kapatildi.");
            }
            case "reachsim" -> {
                if (args.length < 2) {
                    player.sendMessage("§cKullanim: /aclab reachsim <mesafe>");
                    return true;
                }
                simulateReach(player, parseDouble(args[1], 3.0));
            }
            default -> player.sendMessage("§cBilinmeyen alt komut: " + args[0]);
        }
        return true;
    }

    /**
     * Reach dogrulamasinin karar yolunu, hile yazmadan sentetik girdiyle sinar.
     *
     * <p>Verilen mesafede duran bir kurban kutusu uydurulur ve gercek kod yoluyla
     * ayni karar hesaplanir. Boylece esigin nerede oldugu oyun icinde gorulebilir.
     */
    private void simulateReach(Player player, double distance) {
        var eye = player.getEyeLocation();
        // Kurbanin kutusunu, oyuncunun bakis yonunde tam 'distance' blok oteye koy.
        var dir = eye.getDirection().normalize();
        double cx = eye.getX() + dir.getX() * distance;
        double cy = eye.getY() + dir.getY() * distance;
        double cz = eye.getZ() + dir.getZ() * distance;

        double measured = lab.aclab.reach.ReachMath.distanceToBox(
                eye.getX(), eye.getY(), eye.getZ(),
                cx - 0.3, cy - 0.9, cz - 0.3,
                cx + 0.3, cy + 0.9, cz + 0.3);

        var attribute = player.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        double allowed = (attribute != null ? attribute.getValue() : 3.0) + 0.03;

        player.sendMessage("§8§m                                        ");
        player.sendMessage("§b§lReach simulasyonu");
        player.sendMessage("§7Hedef merkezi:   §f" + String.format("%.2f", distance) + " blok");
        player.sendMessage("§7Kutuya mesafe:   §f" + String.format("%.2f", measured) + " blok");
        player.sendMessage("§7Izin verilen:    §f" + String.format("%.2f", allowed) + " blok");
        player.sendMessage(measured > allowed
                ? "§c✘ IHLAL - bu vurus isaretlenirdi"
                : "§a✔ Temiz - bu vurus mesru sayilirdi");
        player.sendMessage("§8§m                                        ");
    }

    /**
     * Alti tarafi tasla kapali bir sandik yerlestirir.
     *
     * <p>Kontrollu deney: koy evindeki sandik zaten havaya aciktir ve
     * gonderilmesi normaldir. Us bulmanin calisip calismadigini olcmek icin
     * hicbir yuzu gorunmeyen bir sandik gerekir - oyuncunun gizledigi us budur.
     */
    private void hideChest(Player player) {
        // Testi dunya dogum noktasina kuruyoruz: bassiz test istemcisi orada
        // dogar, dolayisiyla dogrulama insan beklemeden tekrarlanabilir.
        var world = player.getWorld();
        var spawn = world.getSpawnLocation();
        // Chunk ortasina hizala: kenardaki blogun komsulari baska chunk'ta kalir
        // ve tek kolondan gomululuk karari verilemez.
        int cx = (spawn.getBlockX() & ~15) + 8;
        int cz = (spawn.getBlockZ() & ~15) + 8;
        int cy = Math.max(world.getMinHeight() + 5, spawn.getBlockY() - 12);

        // 3x5x3 tas kabuk: hem sandik hem cevher her yonden kapali kalsin.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getBlockAt(cx + dx, cy + dy, cz + dz)
                            .setType(org.bukkit.Material.STONE, false);
                }
            }
        }
        world.getBlockAt(cx, cy, cz).setType(org.bukkit.Material.CHEST, false);
        world.getBlockAt(cx, cy + 2, cz).setType(org.bukkit.Material.DIAMOND_ORE, false);

        xrayAudit.watch(world, java.util.List.of(
                new lab.aclab.xray.XrayAudit.WatchPos(cx, cy, cz, "chest", "Sandik"),
                new lab.aclab.xray.XrayAudit.WatchPos(cx, cy + 2, cz, "diamond_ore",
                        "Elmas cevheri (kontrol)")));

        player.sendMessage("§8§m                                        ");
        player.sendMessage("§b§lGomulu sandik + kontrol cevheri yerlestirildi");
        player.sendMessage("§7Konum: §f" + cx + ", " + cy + ", " + cz);
        player.sendMessage("§7Ikisi de ayni tas kabugun icinde, hicbir acidan gorunmuyor.");
        player.sendMessage("§7Gozcu kuruldu: bu chunk kime gonderilirse gonderilsin");
        player.sendMessage("§7sonuc raporlanir. Cik-gir yapan bot da tetikler.");
        player.sendMessage("§8§m                                        ");
    }

    /**
     * Yogun bir "us tarlasi" kurar: cok sayida gomulu sandik.
     *
     * <p>Bos bir dunyada olcum yaniltici olur - kalkanin ucuz yolu (dizinde kayit
     * yoksa hemen cik) neredeyse her pakette calisir. Gercek bir sunucuda yuzlerce
     * us vardir ve pahali yol (kolonu yeniden insa etme) surekli devreye girer.
     */
    private void stressChests(Player player, int count) {
        var world = player.getWorld();
        int originX = (world.getSpawnLocation().getBlockX() & ~15) - 64;
        int originZ = (world.getSpawnLocation().getBlockZ() & ~15) - 64;
        int y = Math.max(world.getMinHeight() + 8, 50);
        int side = (int) Math.ceil(Math.sqrt(count));
        int placed = 0;

        for (int i = 0; i < side && placed < count; i++) {
            for (int j = 0; j < side && placed < count; j++) {
                int cx = originX + i * 4;
                int cz = originZ + j * 4;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            world.getBlockAt(cx + dx, y + dy, cz + dz)
                                    .setType(org.bukkit.Material.STONE, false);
                        }
                    }
                }
                world.getBlockAt(cx, y, cz).setType(org.bukkit.Material.CHEST, false);
                placed++;
            }
        }
        String msg = placed + " gomulu sandik yerlestirildi, merkez " + originX + "," + y
                + "," + originZ;
        player.sendMessage("§b" + msg);
        getLogger().info("[stress] " + msg);
    }

    /** Yapilandirmadaki blok adlarini okur; taninmayanlari atlayip uyarir. */
    private java.util.Set<org.bukkit.Material> readProtectedTypes() {
        java.util.Set<org.bukkit.Material> types = java.util.EnumSet.noneOf(org.bukkit.Material.class);
        for (String name : getConfig().getStringList("shield.protected-blocks")) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(name);
            if (material == null) {
                getLogger().warning("shield.protected-blocks: taninmayan blok '" + name + "'");
            } else {
                types.add(material);
            }
        }
        if (types.isEmpty()) {
            getLogger().warning("shield.protected-blocks bos - kalkan hicbir sey gizlemeyecek.");
        }
        return types;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Math.max(1, Math.min(256, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
