package io.github.tunaseckin.outofsight.shield;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides storage vehicles and other telltale entities a player cannot see.
 *
 * <p>A chest minecart, a hopper minecart or a chest boat is an entity, not a
 * block, so {@link BlockEntityShield} never sees it. Storage ESP draws them all
 * the same, and a row of hopper minecarts under a farm points at a base as
 * clearly as a chest does.
 *
 * <p>The rule is the one the block shield uses, default deny: an entity is sent
 * to a player only once the main thread has found an unobstructed line to it.
 * Distance is not part of the rule. An entity in plain view stays visible as
 * far as the client draws it, so item frames on a wall or a boat on open water
 * look exactly as they do without this plugin.
 *
 * <p>Two layers keep it closed. Paper's {@code hideEntity} stops the tracker
 * from sending anything about the entity, and it is per plugin, so it does not
 * interfere with other plugins hiding or showing the same entity. It cannot act
 * before the tracker's very first spawn packet, though, so a packet listener
 * drops that packet for any entity not yet shown.
 *
 * <p>Players are never hidden: {@code hideEntity} on a player also removes them
 * from the tab list, which no one would expect from an anti-ESP measure.
 */
public final class EntityShield extends PacketListenerAbstract implements Listener {

    private final Plugin plugin;
    private final BlockEntityShield blockShield;
    private final HiddenIndex index;
    private final boolean testMode;

    /** Entity types to hide, for the main thread. */
    private final Set<EntityType> types;

    /** The same types as registry keys, for the network thread. */
    private final Set<String> typeKeys;

    /** Classes to ask the world for, so a scan skips every other entity. */
    private final Class<?>[] typeClasses;

    /** How far around a player entities are checked. Beyond it they stay hidden. */
    private final double radius;

    /** Upper bound on line of sight rays per player per sweep. */
    private final int rayBudget;

    /** Entities each player has been allowed to see. Read on network threads. */
    private final Map<UUID, Set<UUID>> shown = new ConcurrentHashMap<>();

    /** Players the shield currently applies to. Read on network threads. */
    private final Set<UUID> managed = ConcurrentHashMap.newKeySet();

    public EntityShield(Plugin plugin, BlockEntityShield blockShield, HiddenIndex index,
                        boolean testMode, Set<EntityType> types, double radius, int rayBudget) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.blockShield = blockShield;
        this.index = index;
        this.testMode = testMode;
        this.types = types;
        this.radius = radius;
        this.rayBudget = Math.max(1, rayBudget);
        Set<String> keys = new HashSet<>();
        Set<Class<?>> classes = new HashSet<>();
        for (EntityType type : types) {
            keys.add(type.getKey().toString());
            if (type.getEntityClass() != null) {
                classes.add(type.getEntityClass());
            }
        }
        this.typeKeys = Set.copyOf(keys);
        this.typeClasses = classes.toArray(new Class<?>[0]);
    }

    public boolean isEmpty() {
        return types.isEmpty();
    }

    // --- network thread ---------------------------------------------------

    /** Drops the tracker's first spawn packet for anything not yet shown. */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SPAWN_ENTITY) {
            return;
        }
        UUID viewer = event.getUser().getUUID();
        if (viewer == null || !managed.contains(viewer)) {
            return;
        }
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
        var type = spawn.getEntityType();
        if (type == null || !typeKeys.contains(type.getName().toString())) {
            return;
        }
        UUID entity = spawn.getUUID().orElse(null);
        if (entity == null) {
            return;
        }
        Set<UUID> allowed = shown.get(viewer);
        if (allowed == null || !allowed.contains(entity)) {
            event.setCancelled(true);
        }
    }

    // --- events -----------------------------------------------------------

    /**
     * Starts hiding before the first entities are tracked for this player.
     *
     * <p>Runs after the LOWEST handlers that grant the test permission, and reads
     * the permission directly instead of the cache the sweep fills.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean applies = blockShield.isEnabled()
                && index.isWorldShielded(player.getWorld().getName())
                && ShieldIndexer.covers(player, testMode);
        if (applies) {
            startManaging(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        managed.remove(id);
        shown.remove(id);
    }

    /** A newly loaded or spawned entity starts out hidden from everyone managed. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        Entity entity = event.getEntity();
        if (!types.contains(entity.getType())) {
            return;
        }
        for (UUID id : managed) {
            Player player = plugin.getServer().getPlayer(id);
            Set<UUID> allowed = shown.get(id);
            if (player != null && (allowed == null || !allowed.contains(entity.getUniqueId()))) {
                player.hideEntity(plugin, entity);
            }
        }
    }

    /**
     * Forgets an entity that left the world, so nothing is kept for it.
     *
     * <p>It is removed from {@link #shown} before being un-hidden, so if the
     * un-hide makes the tracker send a spawn packet, the listener drops it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        Entity entity = event.getEntity();
        if (!types.contains(entity.getType())) {
            return;
        }
        for (UUID id : managed) {
            Set<UUID> allowed = shown.get(id);
            if (allowed != null) {
                allowed.remove(entity.getUniqueId());
            }
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                player.showEntity(plugin, entity);
            }
        }
    }

    // --- sweep ------------------------------------------------------------

    /** Runs on the main thread, on the same timer as container delivery. */
    public void sweep() {
        if (types.isEmpty()) {
            return;
        }
        // A player riding a protected vehicle is visible from far away. Hiding
        // the vehicle under them would show a player floating on nothing.
        Map<UUID, List<Entity>> riddenByWorld = new HashMap<>();
        for (Player rider : plugin.getServer().getOnlinePlayers()) {
            Entity vehicle = rider.getVehicle();
            if (vehicle != null && types.contains(vehicle.getType())) {
                riddenByWorld.computeIfAbsent(vehicle.getWorld().getUID(), k -> new ArrayList<>())
                        .add(vehicle);
            }
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean applies = blockShield.isEnabled()
                    && index.isWorldShielded(player.getWorld().getName())
                    && ShieldIndexer.covers(player, testMode);
            if (!applies) {
                if (managed.contains(id)) {
                    stopManaging(player);
                }
                continue;
            }
            if (!managed.contains(id)) {
                startManaging(player);
            }
            Set<UUID> allowed = shown.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());

            for (Entity vehicle : riddenByWorld.getOrDefault(player.getWorld().getUID(), List.of())) {
                if (!allowed.contains(vehicle.getUniqueId())) {
                    reveal(player, vehicle, allowed);
                }
            }

            Location loc = player.getLocation();
            Collection<Entity> nearby = player.getWorld().getNearbyEntities(
                    BoundingBox.of(loc, radius, radius, radius),
                    e -> types.contains(e.getType()));
            Set<UUID> nearbyIds = new HashSet<>();
            List<Entity> candidates = new ArrayList<>();
            for (Entity entity : nearby) {
                nearbyIds.add(entity.getUniqueId());
                if (!allowed.contains(entity.getUniqueId())) {
                    candidates.add(entity);
                }
            }
            // More candidates than the budget: a random subset each sweep still
            // reaches every one of them within a few sweeps.
            if (candidates.size() > rayBudget) {
                Collections.shuffle(candidates);
                candidates = candidates.subList(0, rayBudget);
            }
            for (Entity entity : candidates) {
                if (!entity.getPassengers().isEmpty() || hasLineOfSight(player, entity)) {
                    reveal(player, entity, allowed);
                }
            }

            // Left behind: hide again, so it is withheld the next time it is tracked.
            for (UUID entityId : List.copyOf(allowed)) {
                if (!nearbyIds.contains(entityId)) {
                    allowed.remove(entityId);
                    Entity entity = plugin.getServer().getEntity(entityId);
                    if (entity != null && entity.getVehicle() == null
                            && entity.getPassengers().isEmpty()) {
                        player.hideEntity(plugin, entity);
                    } else if (entity != null) {
                        allowed.add(entityId); // Ridden: keep it visible.
                    }
                }
            }
        }
    }

    /**
     * Sends an entity to a player.
     *
     * <p>Hiding and then showing again makes the tracker send a fresh spawn
     * packet. A plain show would not, if the only thing that kept it from the
     * client was the dropped first packet.
     */
    private void reveal(Player player, Entity entity, Set<UUID> allowed) {
        allowed.add(entity.getUniqueId());
        player.hideEntity(plugin, entity);
        player.showEntity(plugin, entity);
    }

    private void startManaging(Player player) {
        UUID id = player.getUniqueId();
        shown.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        managed.add(id);
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntitiesByClasses(typeClasses)) {
                if (types.contains(entity.getType())) {
                    player.hideEntity(plugin, entity);
                }
            }
        }
    }

    /** Shield switched off, or test mode no longer covers this player. */
    private void stopManaging(Player player) {
        UUID id = player.getUniqueId();
        managed.remove(id);
        shown.remove(id);
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntitiesByClasses(typeClasses)) {
                if (types.contains(entity.getType())) {
                    player.showEntity(plugin, entity);
                }
            }
        }
    }

    /** Rays to the centre and the top of the entity's box; either one is enough. */
    private boolean hasLineOfSight(Player player, Entity entity) {
        if (!player.getWorld().equals(entity.getWorld())) {
            return false;
        }
        Location eye = player.getEyeLocation();
        BoundingBox box = entity.getBoundingBox();
        Vector centre = box.getCenter();
        Vector top = new Vector(centre.getX(), box.getMaxY() - 0.05, centre.getZ());
        return rayClear(eye, centre) || rayClear(eye, top);
    }

    private boolean rayClear(Location eye, Vector target) {
        Vector direction = target.clone().subtract(eye.toVector());
        double distance = direction.length();
        if (distance < 0.1) {
            return true;
        }
        var hit = eye.getWorld().rayTraceBlocks(eye, direction.normalize(), distance,
                FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }
}
