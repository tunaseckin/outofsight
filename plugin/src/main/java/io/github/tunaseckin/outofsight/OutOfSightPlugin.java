package io.github.tunaseckin.outofsight;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.tunaseckin.outofsight.reach.ReachCheck;
import io.github.tunaseckin.outofsight.shield.BlockEntityShield;
import io.github.tunaseckin.outofsight.shield.DecoyCorrector;
import io.github.tunaseckin.outofsight.shield.DecoyService;
import io.github.tunaseckin.outofsight.shield.HiddenIndex;
import io.github.tunaseckin.outofsight.shield.ShieldIndexer;
import io.github.tunaseckin.outofsight.xray.XrayAudit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin entry point.
 *
 * <p>The modules represent two different kinds of defence:
 * <ul>
 *   <li>{@link io.github.tunaseckin.outofsight.shield.BlockEntityShield} - against information cheats.
 *       They cannot be detected, so the data is simply not sent.</li>
 *   <li>{@link XrayAudit} - measures what still leaks, and <em>proves</em>
 *       whether the data left the server.</li>
 *   <li>{@link ReachCheck} - against action cheats. These break physics and are
 *       detectable; this validates them with latency compensation.</li>
 * </ul>
 */
public final class OutOfSightPlugin extends JavaPlugin implements Listener {

    /** Should match anti-xray's {@code max-block-height}. */
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
        shield = new BlockEntityShield(this, hiddenIndex, decoys,
                getConfig().getBoolean("shield.test-mode", false));

        corrector = new DecoyCorrector(this, decoys,
                getConfig().getInt("shield.decoy-correction-radius-chunks", 3));
        getServer().getScheduler().runTaskTimer(this, corrector::run, 20L, 20L);
        if (decoys.enabled()) {
            getLogger().info("decoys on: " + decoys.perChunk() + " per chunk");
        }

        indexer = new ShieldIndexer(this, hiddenIndex,
                getConfig().getInt("shield.sweep-radius-chunks", 4),
                getConfig().getDouble("shield.hide-beyond-blocks", 48.0),
                readProtectedTypes());
        getServer().getPluginManager().registerEvents(indexer, this);
        getServer().getScheduler().runTask(this, indexer::indexLoadedChunks);

        // Safety net for changes that events do not catch.
        long discoverTicks = Math.max(20L, getConfig().getLong("shield.discover-interval-ticks", 40L));
        getServer().getScheduler().runTaskTimer(this, indexer::discover, discoverTicks, discoverTicks);

        long deliverTicks = Math.max(1L, getConfig().getLong("shield.deliver-interval-ticks", 5L));
        getServer().getScheduler().runTaskTimer(this, indexer::sweep, deliverTicks, deliverTicks);

        if (getConfig().getBoolean("shield.enabled", false)) {
            shield.toggle();
            getLogger().info(getConfig().getBoolean("shield.test-mode", false)
                    ? "shield on, test mode: only players with outofsight.shielded are affected"
                    : "shield on for everyone");
        } else {
            getLogger().info("shield off. Auditing works regardless; "
                    + "set shield.enabled to turn it on.");
        }
        PacketEvents.getAPI().getEventManager().registerListener(shield);
        PacketEvents.getAPI().getEventManager().registerListener(xrayAudit);
        PacketEvents.getAPI().getEventManager().registerListener(reachCheck);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, reachCheck::tick, 1L, 1L);

        getLogger().info("OutOfSight enabled - see /outofsight");
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (testers.contains(event.getPlayer().getUniqueId())) {
            applyTestPermission(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reachCheck.forget(event.getPlayer());
        testAttachments.remove(event.getPlayer().getUniqueId());
        // The tester's choice survives; only the attachment goes.
        corrector.forget(event.getPlayer());
        indexer.forget(event.getPlayer());
        xrayAudit.stop(event.getPlayer());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command must be run in game.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§7/outofsight xray [chunks] §8- audit outgoing chunk packets");
            player.sendMessage("§7/outofsight reachsim <distance> §8- test reach with synthetic input");
            player.sendMessage("§7/outofsight reachdebug §8- log the measured distance of every hit");
            player.sendMessage("§7/outofsight hidechest §8- place a buried chest (base finding test)");
            player.sendMessage("§7/outofsight shield §8- toggle the buried block entity shield");
            player.sendMessage("§7/outofsight testme §8- shield yourself only, for testing");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "xray" -> {
                int budget = args.length > 1 ? parseInt(args[1], 16) : 16;
                xrayAudit.start(player, budget);
                player.sendMessage("§bAudit started §7- the next §f" + budget
                        + " §7chunk packets will be inspected. Relogging fills it"
                        + " instantly; otherwise walk into new terrain.");
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
                                "server: %.2f ms/tick, %.2f TPS, %d players",
                                mspt, tps, getServer().getOnlinePlayers().size())}) {
                    player.sendMessage("§7" + line);
                    getLogger().info("[perf] " + line);
                }
            }
            case "perfreset" -> {
                shield.resetStats();
                indexer.resetStats();
                player.sendMessage("§7Counters reset.");
                getLogger().info("[perf] reset");
            }
            case "testme" -> toggleTestPermission(player);
            case "shield" -> {
                boolean on = shield.toggle();
                player.sendMessage(on
                        ? "§aShield ON §7- buried containers removed from packets."
                        : "§7Shield off.");
                getLogger().info("shield " + (on ? "ON" : "off"));
            }
            case "reachdebug" -> {
                boolean on = reachCheck.toggleDebug(player);
                player.sendMessage(on
                        ? "§aReach probe ON §7- every hit is logged here and to console."
                        : "§7Reach probe off.");
            }
            case "reachsim" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /outofsight reachsim <distance>");
                    return true;
                }
                simulateReach(player, parseDouble(args[1], 3.0));
            }
            default -> player.sendMessage("§cUnknown subcommand: " + args[0]);
        }
        return true;
    }

    /**
     * Exercises the reach decision path with synthetic input, without a cheat.
     *
     * <p>A victim box is fabricated at the given distance and the same code path
     * computes the verdict, so the threshold can be seen in game.
     */
    private void simulateReach(Player player, double distance) {
        var eye = player.getEyeLocation();
        // Place the victim box exactly 'distance' blocks along the player's look.
        var dir = eye.getDirection().normalize();
        double cx = eye.getX() + dir.getX() * distance;
        double cy = eye.getY() + dir.getY() * distance;
        double cz = eye.getZ() + dir.getZ() * distance;

        double measured = io.github.tunaseckin.outofsight.reach.ReachMath.distanceToBox(
                eye.getX(), eye.getY(), eye.getZ(),
                cx - 0.3, cy - 0.9, cz - 0.3,
                cx + 0.3, cy + 0.9, cz + 0.3);

        var attribute = player.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        double allowed = (attribute != null ? attribute.getValue() : 3.0) + 0.03;

        player.sendMessage("§8§m                                        ");
        player.sendMessage("§b§lReach simulation");
        player.sendMessage("§7Target centre:  §f" + String.format("%.2f", distance) + " blocks");
        player.sendMessage("§7To bounding box: §f" + String.format("%.2f", measured) + " blocks");
        player.sendMessage("§7Allowed:         §f" + String.format("%.2f", allowed) + " blocks");
        player.sendMessage(measured > allowed
                ? "§c✘ VIOLATION - this hit would be flagged"
                : "§a✔ Clean - this hit would count as legitimate");
        player.sendMessage("§8§m                                        ");
    }

    /**
     * Places a chest sealed in stone on all six sides.
     *
     * <p>A controlled experiment: a chest in a village house is already exposed
     * and sending it is correct. Measuring whether base finding works needs a
     * chest with no visible face - a hidden base.
     */
    private void hideChest(Player player) {
        // Built at world spawn: the headless test client appears there, so
        // verification can repeat without waiting for a person.
        var world = player.getWorld();
        var spawn = world.getSpawnLocation();
        // Aligned to the chunk centre: a block on the border has neighbours in
        // another chunk, where enclosure cannot be decided from one column.
        int cx = (spawn.getBlockX() & ~15) + 8;
        int cz = (spawn.getBlockZ() & ~15) + 8;
        int cy = Math.max(world.getMinHeight() + 5, spawn.getBlockY() - 12);

        // A 3x5x3 stone shell so both the chest and the ore stay sealed.
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
                new io.github.tunaseckin.outofsight.xray.XrayAudit.WatchPos(cx, cy, cz, "chest", "Chest"),
                new io.github.tunaseckin.outofsight.xray.XrayAudit.WatchPos(cx, cy + 2, cz, "diamond_ore",
                        "Diamond ore (control)")));

        player.sendMessage("§8§m                                        ");
        player.sendMessage("§b§lBuried chest + control ore placed");
        player.sendMessage("§7Position: §f" + cx + ", " + cy + ", " + cz);
        player.sendMessage("§7Both sealed in the same stone shell, visible from nowhere.");
        player.sendMessage("§7Watch armed: the result is reported whoever this");
        player.sendMessage("§7chunk is sent to. A relogging bot triggers it too.");
        player.sendMessage("§8§m                                        ");
    }

    /**
     * Builds a dense field of buried chests.
     *
     * <p>Measuring on an empty world is misleading - the shield's cheap path
     * (nothing indexed, return at once) runs on almost every packet. A real
     * server has hundreds of bases, so the expensive path runs constantly.
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
        String msg = placed + " buried chests placed, centred at " + originX + "," + y
                + "," + originZ;
        player.sendMessage("§b" + msg);
        getLogger().info("[stress] " + msg);
    }

    /** Attachments granting the test permission, one per online player. */
    private final java.util.Map<java.util.UUID, org.bukkit.permissions.PermissionAttachment>
            testAttachments = new java.util.HashMap<>();

    /**
     * Who asked to be shielded, kept across sessions.
     *
     * <p>An attachment dies with the connection, but seeing the shield work means
     * relogging, since chunk packets are only sent on join. Dropping the choice at
     * that moment would make the command useless.
     */
    private final java.util.Set<java.util.UUID> testers = new java.util.HashSet<>();

    /**
     * Grants or revokes the test permission for the caller.
     *
     * <p>Test mode needs a permission, and a small server often has no permissions
     * plugin to grant one with. Without this, trying the shield safely would mean
     * installing another plugin first, which is a strange thing to ask of someone
     * who only wants to know whether this one works.
     */
    private void toggleTestPermission(Player player) {
        if (testers.remove(player.getUniqueId())) {
            var existing = testAttachments.remove(player.getUniqueId());
            if (existing != null) {
                player.removeAttachment(existing);
            }
            player.sendMessage("§7No longer shielded. Other players are unaffected either way.");
            return;
        }
        testers.add(player.getUniqueId());
        applyTestPermission(player);
        player.sendMessage("§aShielded. §7Relog, then check you can still find and open");
        player.sendMessage("§7your own containers. Nobody else is affected while");
        player.sendMessage("§7shield.test-mode is on.");
    }

    private void applyTestPermission(Player player) {
        testAttachments.computeIfAbsent(player.getUniqueId(), k -> player.addAttachment(this,
                io.github.tunaseckin.outofsight.shield.ShieldIndexer.SHIELDED_PERMISSION, true));
    }

    /** Reads block names from config, warning about unrecognised ones. */
    private java.util.Set<org.bukkit.Material> readProtectedTypes() {
        java.util.Set<org.bukkit.Material> types = java.util.EnumSet.noneOf(org.bukkit.Material.class);
        for (String name : getConfig().getStringList("shield.protected-blocks")) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(name);
            if (material == org.bukkit.Material.SHULKER_BOX) {
                // Only the undyed box is called shulker_box. Most boxes on a
                // server are dyed, and listing all seventeen is easy to get
                // wrong, so the one name covers every colour.
                types.addAll(org.bukkit.Tag.SHULKER_BOXES.getValues());
            } else if (material == null) {
                getLogger().warning("shield.protected-blocks: unknown block '" + name + "'");
            } else {
                types.add(material);
            }
        }
        if (types.isEmpty()) {
            getLogger().warning("shield.protected-blocks is empty - the shield will hide nothing.");
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
