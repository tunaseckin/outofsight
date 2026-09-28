package io.github.tunaseckin.outofsight.shield;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells staff when a player digs into the spot where a decoy chest was shown.
 *
 * <p>Information cheats send nothing to the server, so there is nothing to catch
 * them with directly. Decoys change that: a decoy exists only in packets, sealed
 * in stone, so only a cheat ever sees one. A player who breaks the exact block
 * where a decoy sat most likely came for it.
 *
 * <p>It only alerts and never punishes. A player strip mining through enough
 * stone can land on a decoy spot by chance, which is why one hit is not enough by
 * default and a person makes the call.
 */
public final class DecoyHoneypot implements Listener {

    /** Held by players who receive the alerts. */
    public static final String ALERTS_PERMISSION = "outofsight.alerts";

    private final Plugin plugin;
    private final DecoyService decoys;
    private final int threshold;
    private final long windowMillis;

    /** Recent hit times per player, oldest first. */
    private final Map<UUID, Deque<Long>> hits = new ConcurrentHashMap<>();

    public DecoyHoneypot(Plugin plugin, DecoyService decoys, int threshold, long windowMillis) {
        this.plugin = plugin;
        this.decoys = decoys;
        this.threshold = Math.max(1, threshold);
        this.windowMillis = Math.max(60_000L, windowMillis);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!decoys.enabled()) {
            return;
        }
        Block block = event.getBlock();
        long pos = HiddenIndex.posKey(block.getX(), block.getY(), block.getZ());
        if (!decoys.wasPlanted(block.getWorld().getUID(), pos)) {
            return;
        }
        Player player = event.getPlayer();
        int count = record(player.getUniqueId(), System.currentTimeMillis());
        if (count < threshold) {
            return;
        }
        String message = String.format(java.util.Locale.ROOT,
                "[OutOfSight] %s dug into a decoy chest spot at %d %d %d in %s (%d in the last %d h)."
                        + " Decoys are only visible to cheats; a chance hit while strip mining is possible.",
                player.getName(), block.getX(), block.getY(), block.getZ(), block.getWorld().getName(),
                count, windowMillis / 3_600_000L);
        plugin.getLogger().warning(message);
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission(ALERTS_PERMISSION)) {
                staff.sendMessage("§c" + message);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Kept across relogs on purpose: logging out must not reset the count.
        prune(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /** Adds a hit and returns how many fall inside the window. */
    int record(UUID player, long now) {
        Deque<Long> times = hits.computeIfAbsent(player, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
                times.removeFirst();
            }
            return times.size();
        }
    }

    private void prune(UUID player, long now) {
        Deque<Long> times = hits.get(player);
        if (times == null) {
            return;
        }
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
                times.removeFirst();
            }
            if (times.isEmpty()) {
                hits.remove(player);
            }
        }
    }
}
