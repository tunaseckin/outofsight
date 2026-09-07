package lab.aclab.reach;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import net.kyori.adventure.text.Component;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ping-compensated hit distance validation.
 *
 * <p>Unlike X-ray this is a genuinely detectable class of cheat: reaching
 * further than allowed requires sending the server a physically impossible
 * packet, and that packet arrives no matter how well the client hides itself.
 *
 * <p>The hard part is not catching cheats but not catching legitimate players.
 * Three deliberate choices serve that:
 * <ul>
 *   <li>Distance is measured to the target's <em>bounding box</em>, not its centre.</li>
 *   <li>Attacker and victim are both rewound by the attacker's latency, and the
 *       <em>closest</em> moment in that window is used.</li>
 *   <li>A single violation carries no penalty; violations accumulate and decay.</li>
 * </ul>
 */
public final class ReachCheck extends PacketListenerAbstract {

    /** Slack for floating point error and vanilla's own box expansion. */
    private static final double EPSILON = 0.03;

    /** Fixed padding added to the rewind window, in milliseconds. */
    private static final int REWIND_PADDING_MS = 100;

    /** Violation level that must accumulate before an alert is raised. */
    private static final double ALERT_THRESHOLD = 5.0;

    /** Radius around a player whose entities are tracked; must exceed reach. */
    private static final double TRACK_RADIUS = 10.0;

    /** History is dropped for entities unseen for longer than this. */
    private static final long STALE_MS = 5_000;

    private final Plugin plugin;
    private final Map<Integer, Tracked> victims = new ConcurrentHashMap<>();
    private final Map<UUID, PositionHistory> attackerEyes = new ConcurrentHashMap<>();
    private final Map<UUID, ViolationTracker> violations = new ConcurrentHashMap<>();

    /** Probe mode reporting every hit, including legitimate ones. */
    private final java.util.Set<UUID> debug = ConcurrentHashMap.newKeySet();

    public ReachCheck(Plugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    /** An entity's box history and when it was last seen. */
    private static final class Tracked {
        final PositionHistory history = new PositionHistory(40);
        volatile long lastSeenMs;
        volatile String name = "?";
    }

    // --- called on the main thread ----------------------------------------

    /**
     * Runs every tick, recording players and the living entities around them.
     *
     * <p>Tracking only players is not enough: aura cheats mostly hit mobs, and
     * ve tek basina test edilebilmesi de buna bagli.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            record(p, now);
            var eye = p.getEyeLocation();
            attackerEyes.computeIfAbsent(p.getUniqueId(), k -> new PositionHistory(40))
                    .add(new PositionHistory.Sample(now,
                            eye.getX(), eye.getY(), eye.getZ(),
                            eye.getX(), eye.getY(), eye.getZ()));

            for (Entity e : p.getNearbyEntities(TRACK_RADIUS, TRACK_RADIUS, TRACK_RADIUS)) {
                if (e instanceof LivingEntity) {
                    record(e, now);
                }
            }
        }
        pruneStale(now);
    }

    private void record(Entity entity, long now) {
        Tracked tracked = victims.computeIfAbsent(entity.getEntityId(), k -> new Tracked());
        BoundingBox box = entity.getBoundingBox();
        tracked.history.add(new PositionHistory.Sample(now,
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ()));
        tracked.lastSeenMs = now;
        tracked.name = entity.getType().name().toLowerCase(Locale.ROOT);
    }

    private void pruneStale(long now) {
        victims.entrySet().removeIf(e -> now - e.getValue().lastSeenMs > STALE_MS);
    }

    /** Toggles probe mode and returns whether it is now on. */
    public boolean toggleDebug(Player player) {
        UUID id = player.getUniqueId();
        if (!debug.remove(id)) {
            debug.add(id);
            return true;
        }
        return false;
    }

    public void forget(Player player) {
        attackerEyes.remove(player.getUniqueId());
        violations.remove(player.getUniqueId());
        victims.remove(player.getEntityId());
    }

    // --- called on a network thread ---------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        if (packet.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }
        if (!(event.getPlayer() instanceof Player attacker)) {
            return;
        }
        int victimId = packet.getEntityId();
        if (victimId == attacker.getEntityId()) {
            return;
        }
        Tracked victim = victims.get(victimId);
        if (victim == null) {
            return; // No history - no accusation without data.
        }

        double distance = closestApproach(attacker.getUniqueId(), victim, attacker.getPing());
        if (Double.isNaN(distance)) {
            return;
        }

        double allowed = allowedReach(attacker) + EPSILON;

        if (debug.contains(attacker.getUniqueId())) {
            // Prints on legitimate hits too: without writing a cheat, this is the
            // only way to see that the packet path actually runs.
            String line = String.format(Locale.ROOT,
                    "reach probe [%s] target=%s distance=%.3f allowed=%.3f ping=%dms -> %s",
                    attacker.getName(), victim.name, distance, allowed, attacker.getPing(),
                    distance > allowed ? "VIOLATION" : "clean");
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getLogger().info(line);
                attacker.sendMessage("§8[tani] §7" + line.substring(line.indexOf("hedef=")));
            });
        }

        if (distance <= allowed) {
            return;
        }

        double level = violations
                .computeIfAbsent(attacker.getUniqueId(), k -> new ViolationTracker(0.5))
                .add(1.0, System.currentTimeMillis());

        String message = String.format(Locale.ROOT,
                "§c[aclab] §f%s §7reach violation: §f%.2f §7blocks (allowed: %.2f, target: %s, "
                        + "ping: %dms, level: %.1f)",
                attacker.getName(), distance, allowed, victim.name, attacker.getPing(), level);

        boolean alert = level >= ALERT_THRESHOLD;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info(message.replaceAll("§.", ""));
            if (alert) {
                plugin.getServer().broadcast(Component.text(message), "aclab.admin");
            }
        });
    }

    /**
     * Closest approach between the attacker's eye and the victim's box within
     * the rewind window.
     *
     * @return the shortest distance, or {@link Double#NaN} without enough history
     */
    private double closestApproach(UUID attackerId, Tracked victim, int pingMs) {
        PositionHistory eyes = attackerEyes.get(attackerId);
        if (eyes == null) {
            return Double.NaN;
        }
        long now = System.currentTimeMillis();
        long from = now - pingMs - REWIND_PADDING_MS;

        List<PositionHistory.Sample> eyeSamples = eyes.samplesWithin(from, now);
        if (eyeSamples.isEmpty()) {
            return Double.NaN;
        }

        double best = Double.NaN;
        for (PositionHistory.Sample eye : eyeSamples) {
            double d = victim.history.minDistanceWithin(from, now,
                    eye.minX(), eye.minY(), eye.minZ());
            if (!Double.isNaN(d) && (Double.isNaN(best) || d < best)) {
                best = d;
            }
        }
        return best;
    }

    /**
     * The player's legitimate interaction range.
     *
     * <p>Read from the player's own attribute rather than hardcoded to 3.0:
     * creative mode and attribute-modifying items give a different range, and
     * missing that produces false positives directly.
     */
    private double allowedReach(Player player) {
        var attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        return attribute != null ? attribute.getValue() : 3.0;
    }
}
