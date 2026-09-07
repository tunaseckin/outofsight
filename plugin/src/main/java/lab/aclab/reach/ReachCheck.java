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
 * Ping telafili vurus mesafesi dogrulamasi.
 *
 * <p>X-ray'in aksine bu gercekten tespit edilebilir bir hile sinifi: fazladan
 * mesafeden vurmak sunucuya fiziksel olarak imkansiz bir paket gondermeyi
 * gerektirir, ve client kendini ne kadar iyi gizlerse gizlesin o paket gelir.
 *
 * <p>Zor olan kisim yakalamak degil, mesru oyuncuyu yakalamamaktir. Uc bilincli
 * tercih bunun icin:
 * <ul>
 *   <li>Mesafe hedefin <em>kutusuna</em> olculur, merkezine degil.</li>
 *   <li>Hem saldiran hem kurban, saldiranin gecikmesi kadar geri sarilir ve
 *       aradaki <em>en yakin</em> an esas alinir.</li>
 *   <li>Tek ihlal ceza dogurmaz; ihlaller birikir ve temiz surede sonumlenir.</li>
 * </ul>
 */
public final class ReachCheck extends PacketListenerAbstract {

    /** Kayan nokta hatasi ve vanilla'nin kendi kutu genislemesi icin pay. */
    private static final double EPSILON = 0.03;

    /** Geri sarma penceresine eklenen sabit tampon (ms). */
    private static final int REWIND_PADDING_MS = 100;

    /** Uyari verilmeden once birikmesi gereken ihlal seviyesi. */
    private static final double ALERT_THRESHOLD = 5.0;

    /** Oyuncunun cevresinde gecmisi tutulan yaricap - reach'ten genis olmali. */
    private static final double TRACK_RADIUS = 10.0;

    /** Bu sureden uzun goruilmeyen varligin gecmisi dusurulur. */
    private static final long STALE_MS = 5_000;

    private final Plugin plugin;
    private final Map<Integer, Tracked> victims = new ConcurrentHashMap<>();
    private final Map<UUID, PositionHistory> attackerEyes = new ConcurrentHashMap<>();
    private final Map<UUID, ViolationTracker> violations = new ConcurrentHashMap<>();

    /** Her vurusu raporlayan tani modu - mesru vurusta da cikti uretir. */
    private final java.util.Set<UUID> debug = ConcurrentHashMap.newKeySet();

    public ReachCheck(Plugin plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    /** Bir varligin kutu gecmisi ve en son ne zaman goruldugusu. */
    private static final class Tracked {
        final PositionHistory history = new PositionHistory(40);
        volatile long lastSeenMs;
        volatile String name = "?";
    }

    // --- ana is parcaciginda cagrilir -------------------------------------

    /**
     * Her tick calisir; oyunculari ve cevrelerindeki canli varliklari kaydeder.
     *
     * <p>Sadece oyunculari izlemek yetmez: aura hilesi cogunlukla mob'lara vurur,
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

    /** Tani modunu acar/kapatir; acik olup olmadigini dondurur. */
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

    // --- ag is parcaciginda cagrilir --------------------------------------

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
            return; // Gecmisi yok - veri yokken suclama yapilmaz.
        }

        double distance = closestApproach(attacker.getUniqueId(), victim, attacker.getPing());
        if (Double.isNaN(distance)) {
            return;
        }

        double allowed = allowedReach(attacker) + EPSILON;

        if (debug.contains(attacker.getUniqueId())) {
            // Mesru vurusta da yazar: paket yolunun gercekten calistigini gormenin
            // hile yazmadan tek yolu bu.
            String line = String.format(Locale.ROOT,
                    "reach tani [%s] hedef=%s mesafe=%.3f izin=%.3f ping=%dms -> %s",
                    attacker.getName(), victim.name, distance, allowed, attacker.getPing(),
                    distance > allowed ? "IHLAL" : "temiz");
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
                "§c[aclab] §f%s §7reach ihlali: §f%.2f §7blok (izin: %.2f, hedef: %s, "
                        + "ping: %dms, seviye: %.1f)",
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
     * Saldiranin gozu ile kurbanin kutusu arasinda, geri sarma penceresindeki
     * en yakin yaklasma mesafesi.
     *
     * @return en kisa mesafe, ya da yeterli gecmis yoksa {@link Double#NaN}
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
     * Oyuncunun mesru erisim mesafesi.
     *
     * <p>Sabit 3.0 yazmak yerine oyuncunun kendi niteliginden okunur: yaratici
     * moddaki oyuncunun ve nitelik degistiren esyalarin menzili farklidir, ve
     * bunu gozden kacirmak dogrudan yanlis pozitif uretir.
     */
    private double allowedReach(Player player) {
        var attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        return attribute != null ? attribute.getValue() : 3.0;
    }
}
